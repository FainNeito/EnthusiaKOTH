package net.badgersmc.ek.infrastructure.persistence

import net.badgersmc.ek.domain.CrownEventPurpose
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

enum class CrownOccurrenceState { PLANNED, STARTED, COMPLETED, CANCELLED }
enum class CrownOccurrenceWrite { CREATED, ALREADY_EXISTS, CONFLICT }

data class CrownOpenOccurrencePage(val occurrences: List<CrownOccurrence>, val nextCursor: String?)

data class CrownOccurrence(
    val occurrenceId: String,
    val arenaId: String,
    val scheduledAt: Instant,
    val purpose: CrownEventPurpose,
    val policyRevision: String,
    val state: CrownOccurrenceState = CrownOccurrenceState.PLANNED,
    val matchId: UUID? = null,
)

/** Inert event journal. A record never starts an event or authorizes an award. */
class SqlCrownEventStore(private val source: DataSource) {
    /** Idempotent acknowledgement of a durably discarded queue entry; conflicting starts remain held. */
    fun acknowledgeDiscard(id:String,arena:String,scheduledAt:Instant,activation:UUID?):Boolean = source.connection.use { c ->
        c.autoCommit=false
        try {
            c.prepareStatement("UPDATE koth_crown_occurrences SET state='CANCELLED' WHERE occurrence_id=? AND arena_id=? AND scheduled_at=? AND (state='PLANNED' OR (state='STARTED' AND match_id=?))").use { s ->
                s.setString(1,id);s.setString(2,arena);s.setString(3,scheduledAt.toString());s.setString(4,activation?.toString());s.executeUpdate()
            }
            val result=c.prepareStatement("SELECT arena_id,scheduled_at,state,match_id FROM koth_crown_occurrences WHERE occurrence_id=?").use { s ->
                s.setString(1,id);s.executeQuery().use { r ->
                    if(!r.next()) true else r.getString(1)==arena && r.getString(2)==scheduledAt.toString() &&
                        (r.getString(3)=="COMPLETED" || (r.getString(3)=="CANCELLED" &&
                            (r.getString(4)==null || r.getString(4)==activation?.toString())))
                }
            }
            if(result)c.commit() else c.rollback()
            result
        } catch(e:Exception){c.rollback();throw e}
    }
    fun init() = source.connection.use { c -> c.createStatement().use { s ->
        s.execute("""CREATE TABLE IF NOT EXISTS koth_crown_occurrences (
            occurrence_id TEXT PRIMARY KEY, arena_id TEXT NOT NULL, scheduled_at TEXT NOT NULL,
            purpose TEXT NOT NULL, policy_revision TEXT NOT NULL, state TEXT NOT NULL,
            match_id TEXT UNIQUE
        )""".trimIndent())
        s.execute("""CREATE TABLE IF NOT EXISTS koth_crown_final_consent (
            occurrence_id TEXT NOT NULL, player TEXT NOT NULL, policy_revision TEXT NOT NULL,
            PRIMARY KEY(occurrence_id,player,policy_revision)
        )""".trimIndent())
        s.execute("""CREATE TABLE IF NOT EXISTS koth_crown_first_award (
            singleton INTEGER PRIMARY KEY CHECK(singleton=1), occurrence_id TEXT NOT NULL,
            match_id TEXT NOT NULL, winner_id TEXT NOT NULL, quest_revision TEXT NOT NULL
        )""".trimIndent())
    } }

    /** Reusing an occurrence ID with different immutable metadata is a conflict, not a retry. */
    fun plan(item: CrownOccurrence): CrownOccurrenceWrite {
        require(item.occurrenceId.isNotBlank() && item.arenaId.isNotBlank() && item.policyRevision.isNotBlank())
        require(item.purpose != CrownEventPurpose.ORDINARY && item.state == CrownOccurrenceState.PLANNED && item.matchId == null)
        return source.connection.use { c ->
            val inserted = c.prepareStatement("INSERT OR IGNORE INTO koth_crown_occurrences VALUES(?,?,?,?,?,?,NULL)").use { s ->
                s.setString(1, item.occurrenceId); s.setString(2, item.arenaId)
                s.setString(3, item.scheduledAt.toString()); s.setString(4, item.purpose.name)
                s.setString(5, item.policyRevision); s.setString(6, CrownOccurrenceState.PLANNED.name)
                s.executeUpdate() == 1
            }
            if (inserted) CrownOccurrenceWrite.CREATED else {
                val existing = read(item.occurrenceId)
                if (existing != null && existing.copy(state = CrownOccurrenceState.PLANNED, matchId = null) == item) {
                    CrownOccurrenceWrite.ALREADY_EXISTS
                } else CrownOccurrenceWrite.CONFLICT
            }
        }
    }

    fun read(id: String): CrownOccurrence? = source.connection.use { c ->
        c.prepareStatement("SELECT arena_id,scheduled_at,purpose,policy_revision,state,match_id FROM koth_crown_occurrences WHERE occurrence_id=?").use { s ->
            s.setString(1, id)
            s.executeQuery().use { r ->
                if (!r.next()) null else CrownOccurrence(id, r.getString(1), Instant.parse(r.getString(2)),
                    CrownEventPurpose.valueOf(r.getString(3)), r.getString(4),
                    CrownOccurrenceState.valueOf(r.getString(5)), r.getString(6)?.let(UUID::fromString))
            }
        }
    }

    /** Bounded restart reconciliation, ordered by immutable ID, without starting or cancelling events. */
    fun listOpenPage(afterId: String?, limit: Int): CrownOpenOccurrencePage {
        require(limit in 1..200)
        require(afterId == null || afterId.isNotBlank())
        return source.connection.use { c ->
            c.prepareStatement("""SELECT occurrence_id,arena_id,scheduled_at,purpose,policy_revision,state,match_id
                FROM koth_crown_occurrences WHERE state IN ('PLANNED','STARTED')
                AND (? IS NULL OR occurrence_id > ?) ORDER BY occurrence_id LIMIT ?""".trimIndent()).use { s ->
                s.setString(1, afterId); s.setString(2, afterId); s.setInt(3, limit + 1)
                s.executeQuery().use { r ->
                    val items = mutableListOf<CrownOccurrence>()
                    while (r.next()) items.add(CrownOccurrence(r.getString(1), r.getString(2), Instant.parse(r.getString(3)),
                        CrownEventPurpose.valueOf(r.getString(4)), r.getString(5), CrownOccurrenceState.valueOf(r.getString(6)),
                        r.getString(7)?.let(UUID::fromString)))
                    val hasMore = items.size > limit
                    val page = items.take(limit)
                    CrownOpenOccurrencePage(page, if (hasMore) page.last().occurrenceId else null)
                }
            }
        }
    }

    /** The same database serializes consent and the transition out of PLANNED. */
    fun acceptHelmetSacrifice(id: String, revision: String, player: UUID): Boolean = accept(id,revision,player,"FIRST_CROWN_FINAL")
    fun acceptRecoveryBinding(id: String, revision: String, player: UUID): Boolean = accept(id,revision,player,"RECOVERY_CROWN_FINAL")
    private fun accept(id:String,revision:String,player:UUID,purpose:String):Boolean=source.connection.use { c ->
        c.prepareStatement("""INSERT INTO koth_crown_final_consent(occurrence_id,player,policy_revision)
            SELECT occurrence_id,?,policy_revision FROM koth_crown_occurrences
            WHERE occurrence_id=? AND policy_revision=? AND state='PLANNED' AND purpose=?
            ON CONFLICT(occurrence_id,player,policy_revision) DO UPDATE SET policy_revision=excluded.policy_revision""".trimIndent()).use { s ->
            s.setString(1, player.toString())
            s.setString(2, id)
            s.setString(3, revision)
            s.setString(4,purpose)
            s.executeUpdate() == 1
        }
    }

    /** Ordinary slots pass through. A restarted final must resume its original activation UUID. */
    fun bindScheduledMatch(id: String, arena: String, scheduledAt: Instant, solo: Boolean, match: UUID): Boolean {
        val slot = read(id) ?: return true
        if (!solo || slot.arenaId != arena || slot.scheduledAt != scheduledAt ||
            slot.purpose == CrownEventPurpose.ORDINARY) return false
        if (slot.state == CrownOccurrenceState.PLANNED && start(id, match)) return true
        val latest = read(id) ?: return false
        return latest.state == CrownOccurrenceState.STARTED && latest.matchId == match
    }

    /** Claiming a start is a compare-and-set; match identity cannot be swapped after a restart. */
    fun start(id: String, matchId: UUID): Boolean = source.connection.use { c ->
        c.prepareStatement("UPDATE OR IGNORE koth_crown_occurrences SET state=?,match_id=? WHERE occurrence_id=? AND state=? AND match_id IS NULL").use { s ->
            s.setString(1, CrownOccurrenceState.STARTED.name); s.setString(2, matchId.toString())
            s.setString(3, id); s.setString(4, CrownOccurrenceState.PLANNED.name); s.executeUpdate() == 1
        }
    }

    fun finish(id: String, matchId: UUID, cancelled: Boolean): Boolean = source.connection.use { c ->
        c.prepareStatement("UPDATE koth_crown_occurrences SET state=? WHERE occurrence_id=? AND state=? AND match_id=?").use { s ->
            s.setString(1, if (cancelled) CrownOccurrenceState.CANCELLED.name else CrownOccurrenceState.COMPLETED.name)
            s.setString(2, id); s.setString(3, CrownOccurrenceState.STARTED.name)
            s.setString(4, matchId.toString()); s.executeUpdate() == 1
        }
    }

    /** Route the protected completion by the match ID assigned when the final started. */
    fun processProtectedResult(matchId: UUID, creditRules: CrownCreditRules,
                               requirements: net.badgersmc.ek.application.CrownQuestRequirements,
                               credits: SqlCrownQuestCredits): CrownFirstAwardIntent? {
        val occurrence = source.connection.use { c ->
            c.prepareStatement("SELECT occurrence_id FROM koth_crown_occurrences WHERE match_id=?").use { s ->
                s.setString(1, matchId.toString())
                s.executeQuery().use { r -> if (r.next()) r.getString(1) else null }
            }
        }
        if (occurrence == null) {
            credits.credit(matchId, creditRules)
            return null
        }
        if(read(occurrence)?.purpose==CrownEventPurpose.HOLDER_CHALLENGE) {
            credits.credit(matchId,creditRules)
            return null
        }
        return finishFirstFinal(occurrence, matchId, creditRules.revision, requirements)
    }

    /** Complete a first-final callback. A retry after completion must carry the same recorded match ID. */
    fun finishFirstFinal(id: String, matchId: UUID, questRevision: String,
                         rules: net.badgersmc.ek.application.CrownQuestRequirements): CrownFirstAwardIntent? {
        val occurrence = read(id) ?: return null
        if (occurrence.matchId != matchId || occurrence.purpose != CrownEventPurpose.FIRST_CROWN_FINAL) return null
        if (!finish(id, matchId, false)) {
            val completed = read(id) ?: return null
            if (completed.state != CrownOccurrenceState.COMPLETED || completed.matchId != matchId) return null
        }
        return SqlCrownFirstAward(source).prepare(id, questRevision, rules)
    }
    fun cancelPlanned(id: String): Boolean = source.connection.use { c ->
        c.prepareStatement("UPDATE koth_crown_occurrences SET state=? WHERE occurrence_id=? AND state=? AND match_id IS NULL").use { s ->
            s.setString(1, CrownOccurrenceState.CANCELLED.name); s.setString(2, id)
            s.setString(3, CrownOccurrenceState.PLANNED.name); s.executeUpdate() == 1
        }
    }
}
