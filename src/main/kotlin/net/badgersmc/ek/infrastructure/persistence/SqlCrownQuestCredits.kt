package net.badgersmc.ek.infrastructure.persistence

import java.sql.Connection
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import javax.sql.DataSource

data class CrownCreditRules(val enabled: Boolean = false, val cutover: Instant, val revision: String) {
    init { require(revision.matches(Regex("[a-zA-Z0-9_.-]{1,64}"))) }
}
enum class CrownCreditResult { DISABLED, UNAVAILABLE_OR_UNQUALIFIED, CREDITED, ALREADY_CREDITED, CONFLICT }
data class CrownQuestTotals(val wins: Int, val scoringSeconds: Long, val distinctUtcDays: Int)
data class CrownCreditCursor(val at: Instant, val eventId: UUID)
data class CrownCreditPage(val results: Map<UUID, CrownCreditResult>, val next: CrownCreditCursor?)

/** Inert ledger: caller supplies only a match ID; eligibility is read from KOTH's protected records. */
class SqlCrownQuestCredits(private val source: DataSource) {
    fun init() = source.connection.use { c -> c.createStatement().use { s ->
        s.execute("""CREATE TABLE IF NOT EXISTS koth_crown_quest_credits (
            event_id TEXT PRIMARY KEY, player_id TEXT NOT NULL, arena_id TEXT NOT NULL,
            at_ms INTEGER NOT NULL, utc_day TEXT NOT NULL, scoring_seconds INTEGER NOT NULL,
            rule_revision TEXT NOT NULL
        )""".trimIndent())
        s.execute("CREATE INDEX IF NOT EXISTS koth_crown_credits_player ON koth_crown_quest_credits(player_id)")
    } }

    /** Does not create a relic claim, reserve the Crown, or invoke external providers. */
    fun credit(eventId: UUID, rules: CrownCreditRules): CrownCreditResult {
        if (!rules.enabled) return CrownCreditResult.DISABLED
        return source.connection.use { c ->
            c.autoCommit = false
            try {
                // Establish the writer lock before inspecting existing protected records.
                c.createStatement().use { it.executeUpdate("UPDATE koth_crown_quest_credits SET rule_revision=rule_revision WHERE event_id='__lock__'") }
                val result = creditTransaction(c, eventId, rules)
                c.commit()
                result
            } catch (error: Exception) {
                runCatching { c.rollback() }
                throw error
            }
        }
    }

    private fun creditTransaction(c: Connection, id: UUID, rules: CrownCreditRules): CrownCreditResult {
        val row = c.prepareStatement("""SELECT m.arena,m.origin,m.at,m.winner,d.status,d.snapshot,a.accepted
            FROM koth_matches m JOIN koth_match_decisions d ON d.event=m.id
            JOIN koth_reward_audit a ON a.event_id=m.id AND a.arena=m.arena WHERE m.id=?""".trimIndent()).use { s ->
            s.setString(1, id.toString())
            s.executeQuery().use { r -> if (!r.next()) null else listOf(
                r.getString(1), r.getString(2), r.getLong(3), r.getString(4), r.getString(5), r.getString(6), r.getInt(7),
            ) }
        } ?: return CrownCreditResult.UNAVAILABLE_OR_UNQUALIFIED
        val arena = row[0] as String; val origin = row[1] as String; val at = row[2] as Long
        val winner = row[3] as? String ?: return CrownCreditResult.UNAVAILABLE_OR_UNQUALIFIED
        if (row[4] != "APPROVED" || row[6] != 1 || Instant.ofEpochMilli(at).isBefore(rules.cutover) ||
            origin !in setOf("SCHEDULED", "PLAYER_COMMAND", "GUI", "FLARE") || !winner.startsWith("solo:")) {
            return CrownCreditResult.UNAVAILABLE_OR_UNQUALIFIED
        }
        val player = runCatching { UUID.fromString(winner.removePrefix("solo:")) }.getOrNull()
            ?: return CrownCreditResult.UNAVAILABLE_OR_UNQUALIFIED
        val (match, policy) = runCatching { MatchSnapshotCodec.decode(row[5] as String) }.getOrNull()
            ?: return CrownCreditResult.UNAVAILABLE_OR_UNQUALIFIED
        if (match.eventId != id || match.arena != arena || match.source != origin ||
            match.at.toEpochMilli() != at || match.winner != winner || match.relationChanged ||
            !policy.enabled || !policy.integrity.requireContest || !policy.integrity.requireReadiness ||
            !policy.integrity.holdSuspicious || arena !in policy.integrity.acceptedArenas ||
            !policy.integrity.contested(match.evidence) || match.oppositionSeconds < policy.minimumOppositionSeconds ||
            match.opponents.isEmpty()) return CrownCreditResult.UNAVAILABLE_OR_UNQUALIFIED
        val contribution = match.contributions.singleOrNull { it.player == player && it.eligible && it.guild == winner }
            ?: return CrownCreditResult.UNAVAILABLE_OR_UNQUALIFIED
        val storedSeconds = c.prepareStatement("SELECT seconds FROM koth_match_players WHERE event=? AND player=? AND eligible=1").use { s ->
            s.setString(1, id.toString()); s.setString(2, player.toString())
            s.executeQuery().use { if (it.next()) it.getLong(1) else null }
        } ?: return CrownCreditResult.UNAVAILABLE_OR_UNQUALIFIED
        if (storedSeconds != contribution.seconds || storedSeconds < policy.minimumScoringSeconds) return CrownCreditResult.UNAVAILABLE_OR_UNQUALIFIED
        val day = Instant.ofEpochMilli(at).atZone(ZoneOffset.UTC).toLocalDate().toString()
        val inserted = c.prepareStatement("INSERT OR IGNORE INTO koth_crown_quest_credits VALUES(?,?,?,?,?,?,?)").use { s ->
            s.setString(1,id.toString()); s.setString(2,player.toString()); s.setString(3,arena)
            s.setLong(4,at); s.setString(5,day); s.setLong(6,storedSeconds); s.setString(7,rules.revision)
            s.executeUpdate() == 1
        }
        if (inserted) return CrownCreditResult.CREDITED
        return c.prepareStatement("SELECT player_id,arena_id,at_ms,utc_day,scoring_seconds,rule_revision FROM koth_crown_quest_credits WHERE event_id=?").use { s ->
            s.setString(1,id.toString()); s.executeQuery().use { r ->
                if (r.next() && r.getString(1)==player.toString() && r.getString(2)==arena && r.getLong(3)==at &&
                    r.getString(4)==day && r.getLong(5)==storedSeconds && r.getString(6)==rules.revision) CrownCreditResult.ALREADY_CREDITED
                else CrownCreditResult.CONFLICT
            }
        }
    }

    fun totals(player: UUID, revision: String): CrownQuestTotals = source.connection.use { c -> totals(c, player, revision) }

    internal fun totals(c: Connection, player: UUID, revision: String, beforeMillis: Long? = null): CrownQuestTotals {
        require(revision.matches(Regex("[a-zA-Z0-9_.-]{1,64}")))
        return c.prepareStatement("""SELECT COUNT(*),COALESCE(SUM(c.scoring_seconds),0),COUNT(DISTINCT c.utc_day)
            FROM koth_crown_quest_credits c
            JOIN koth_matches m ON m.id=c.event_id AND m.arena=c.arena_id AND m.winner=('solo:' || c.player_id)
            JOIN koth_match_decisions d ON d.event=c.event_id AND d.status='APPROVED'
            JOIN koth_reward_audit a ON a.event_id=c.event_id AND a.arena=c.arena_id AND a.accepted=1
            JOIN koth_match_players p ON p.event=c.event_id AND p.player=c.player_id AND p.eligible=1 AND p.seconds=c.scoring_seconds
            WHERE c.player_id=? AND c.rule_revision=? AND (? IS NULL OR c.at_ms<?)""".trimIndent()).use { s ->
            s.setString(1, player.toString()); s.setString(2, revision)
            if (beforeMillis == null) { s.setNull(3, java.sql.Types.BIGINT); s.setNull(4, java.sql.Types.BIGINT) }
            else { s.setLong(3, beforeMillis); s.setLong(4, beforeMillis) }
            s.executeQuery().use { r -> r.next(); CrownQuestTotals(r.getInt(1),r.getLong(2),r.getInt(3)) }
        }
    }

    /**
     * Bounded restart reconciliation. Call again from the beginning after each completed scan;
     * an old held match may become approved after the previous cursor has advanced.
     * This never delivers an item or changes an existing match's decision.
     */
    fun reconcilePage(rules: CrownCreditRules, after: CrownCreditCursor? = null, limit: Int = 100): CrownCreditPage {
        require(limit in 1..500)
        if (!rules.enabled) return CrownCreditPage(emptyMap(), null)
        val rows = source.connection.use { c ->
            c.prepareStatement("""SELECT m.id,m.at FROM koth_matches m
                JOIN koth_match_decisions d ON d.event=m.id AND d.status='APPROVED'
                JOIN koth_reward_audit a ON a.event_id=m.id AND a.arena=m.arena AND a.accepted=1
                LEFT JOIN koth_crown_quest_credits q ON q.event_id=m.id
                WHERE m.at>=? AND m.winner LIKE 'solo:%' AND (q.event_id IS NULL OR q.rule_revision<>?)
                AND (m.at>? OR (m.at=? AND m.id>?)) ORDER BY m.at,m.id LIMIT ?""".trimIndent()).use { s ->
                val afterMillis = after?.at?.toEpochMilli() ?: Long.MIN_VALUE
                s.setLong(1, rules.cutover.toEpochMilli()); s.setString(2, rules.revision)
                s.setLong(3, afterMillis); s.setLong(4, afterMillis); s.setString(5, after?.eventId?.toString() ?: "")
                s.setInt(6, limit + 1)
                s.executeQuery().use { r -> buildList { while (r.next()) add(CrownCreditCursor(Instant.ofEpochMilli(r.getLong(2)), UUID.fromString(r.getString(1)))) } }
            }
        }
        val page = rows.take(limit)
        val results = linkedMapOf<UUID, CrownCreditResult>()
        page.forEach { results[it.eventId] = credit(it.eventId, rules) }
        return CrownCreditPage(results, if (rows.size > limit) page.last() else null)
    }
}
