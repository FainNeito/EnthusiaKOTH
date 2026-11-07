package net.badgersmc.ek.infrastructure.persistence

import java.util.UUID
import java.time.Instant
import java.sql.Connection
import javax.sql.DataSource
import net.badgersmc.ek.application.ProgressionPolicy
import net.badgersmc.ek.application.VerifiedMatch
import net.badgersmc.ek.domain.CrownEventPurpose

/** A consistent database observation only; it cannot authorize custody or an award. */
data class CrownFinalObservation(val occurrenceId: String, val eventId: UUID, val winnerId: UUID,
    val purpose: CrownEventPurpose, val questRevision: String, val questTotals: CrownQuestTotals)

class SqlCrownFinalObservation(private val source: DataSource) {
    fun read(occurrenceId: String, questRevision: String): CrownFinalObservation? {
        return source.connection.use { c ->
            c.autoCommit = false
            try {
                val result = read(c, occurrenceId, questRevision)
                c.commit()
                result
            } catch (error: Exception) {
                runCatching { c.rollback() }
                throw error
            }
        }
    }

    internal fun read(c: Connection, occurrenceId: String, questRevision: String): CrownFinalObservation? {
        require(occurrenceId.isNotBlank())
        require(questRevision.matches(Regex("[a-zA-Z0-9_.-]{1,64}")))
        return c.prepareStatement("""SELECT o.match_id,o.purpose,m.winner,d.snapshot,o.scheduled_at,m.arena,m.origin,m.at
            FROM koth_crown_occurrences o
            JOIN koth_matches m ON m.id=o.match_id AND m.arena=o.arena_id
            JOIN koth_match_decisions d ON d.event=m.id AND d.status='APPROVED'
            JOIN koth_reward_audit a ON a.event_id=m.id AND a.arena=m.arena AND a.accepted=1
            WHERE o.occurrence_id=? AND o.state='COMPLETED'
            AND m.origin='SCHEDULED'""".trimIndent()).use { s ->
            s.setString(1, occurrenceId)
            s.executeQuery().use { r ->
                if (!r.next()) null else {
                    val eventId = runCatching { UUID.fromString(r.getString(1)) }.getOrNull()
                    val purpose = runCatching { CrownEventPurpose.valueOf(r.getString(2)) }.getOrNull()
                    val winner = r.getString(3)
                    val winnerId = winner?.takeIf { it.startsWith("solo:") }
                        ?.let { runCatching { UUID.fromString(it.removePrefix("solo:")) }.getOrNull() }
                    val snapshot = runCatching { MatchSnapshotCodec.decode(r.getString(4)) }.getOrNull()
                    val scheduled = runCatching { Instant.parse(r.getString(5)) }.getOrNull()
                    val arena = r.getString(6); val origin = r.getString(7); val at = r.getLong(8)
                    if (eventId == null || winnerId == null || purpose == null || purpose == CrownEventPurpose.ORDINARY ||
                        snapshot == null || scheduled == null || Instant.ofEpochMilli(at).isBefore(scheduled) ||
                        snapshot.first.eventId != eventId || snapshot.first.winner != winner ||
                        snapshot.first.arena != arena || snapshot.first.source != origin || snapshot.first.at.toEpochMilli() != at ||
                        !qualifies(c, snapshot.first, snapshot.second, winnerId)) null
                    else CrownFinalObservation(occurrenceId, eventId, winnerId, purpose, questRevision,
                        SqlCrownQuestCredits(source).totals(c, winnerId, questRevision, scheduled.toEpochMilli()))
                }
            }
        }
    }

    /** Recheck the protected snapshot and saved winning contribution, not just its approval label. */
    internal fun qualifies(c: Connection, match: VerifiedMatch, policy: ProgressionPolicy, winner: UUID): Boolean {
        if (!policy.enabled || !policy.integrity.requireContest || !policy.integrity.requireReadiness ||
            !policy.integrity.holdSuspicious || match.arena !in policy.integrity.acceptedArenas ||
            !policy.integrity.contested(match.evidence) || match.relationChanged || match.opponents.isEmpty() ||
            match.oppositionSeconds < policy.minimumOppositionSeconds) return false
        val contribution = match.contributions.singleOrNull {
            it.player == winner && it.eligible && it.guild == "solo:$winner"
        } ?: return false
        if (contribution.seconds < policy.minimumScoringSeconds) return false
        return c.prepareStatement("SELECT seconds FROM koth_match_players WHERE event=? AND player=? AND eligible=1").use { s ->
            s.setString(1, match.eventId.toString()); s.setString(2, winner.toString())
            s.executeQuery().use { r -> r.next() && r.getLong(1) == contribution.seconds }
        }
    }
}
