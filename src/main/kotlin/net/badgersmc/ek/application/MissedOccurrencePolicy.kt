package net.badgersmc.ek.application

import net.badgersmc.ek.infrastructure.persistence.OperationalSnapshot
import net.badgersmc.ek.infrastructure.persistence.ScheduleClaimStatus
import java.time.Duration
import java.time.Instant

/** MISSED alone permits the PLANNED-to-CANCELLED CAS; every other outcome keeps the slot bound. */
enum class MissedOccurrenceDecision { MISSED, NOT_YET, SCHEDULED, HOLD }

/**
 * A special occurrence counts as never enqueued only when strict scheduler and queue evidence
 * agree it is absent and the scheduler's durable cursor has passed its catch-up window, after
 * which [ScheduleService] can no longer make it due. Elapsed time, an empty in-memory queue or a
 * missing claim on its own never proves a missed event.
 */
object MissedOccurrencePolicy {
    private val SETTLE_MARGIN: Duration = Duration.ofMinutes(1)

    fun assess(
        occurrenceId: String,
        scheduledAt: Instant,
        now: Instant,
        snapshot: OperationalSnapshot?,
        liveQueue: Set<String>?,
    ): MissedOccurrenceDecision {
        val closesAt = scheduledAt.plus(ScheduleService.MAX_CATCH_UP).plus(SETTLE_MARGIN)
        if (now.isBefore(closesAt)) return MissedOccurrenceDecision.NOT_YET
        if (snapshot == null || liveQueue == null) return MissedOccurrenceDecision.HOLD
        // The running queue and its durable file must agree; either alone may be stale.
        if (liveQueue != snapshot.queuedOccurrences) return MissedOccurrenceDecision.HOLD
        if (occurrenceId in liveQueue) return MissedOccurrenceDecision.SCHEDULED
        when (snapshot.startClaims["start:$occurrenceId"]) {
            ScheduleClaimStatus.PENDING -> return MissedOccurrenceDecision.SCHEDULED
            // Handed off yet neither queued nor started: inconsistent, so staff must reconcile.
            ScheduleClaimStatus.COMMITTED -> return MissedOccurrenceDecision.HOLD
            null -> Unit
        }
        if (snapshot.evaluatedThrough.isAfter(now)) return MissedOccurrenceDecision.HOLD
        if (snapshot.evaluatedThrough.isBefore(closesAt)) return MissedOccurrenceDecision.NOT_YET
        return MissedOccurrenceDecision.MISSED
    }
}
