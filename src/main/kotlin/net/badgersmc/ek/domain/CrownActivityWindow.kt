package net.badgersmc.ek.domain

import java.time.Instant

enum class CrownOpportunityStatus { VERIFIED_ELIGIBLE, CANCELLED_OR_INELIGIBLE, UNKNOWN }

data class CrownOpportunity(
    val occurrenceId: String,
    val at: Instant,
    val status: CrownOpportunityStatus,
    /** Must be backed by independently verified, non-AFK participation evidence. */
    val participationVerified: Boolean,
)

enum class CrownActivityDecision { PARTICIPATED, WINDOW_OPEN, INSUFFICIENT_OPPORTUNITIES, INACTIVITY_REVIEW, HOLD_FOR_REVIEW }

/** Pure assessment; it cannot confiscate, transfer, schedule or mint the Crown. */
object CrownActivityWindow {
    fun evaluate(
        start: Instant,
        end: Instant,
        now: Instant,
        minimumOpportunities: Int,
        opportunities: List<CrownOpportunity>,
        evidenceComplete: Boolean,
    ): CrownActivityDecision {
        require(end.isAfter(start))
        require(minimumOpportunities > 0)
        if (!evidenceComplete || opportunities.any { it.occurrenceId.isBlank() }) return CrownActivityDecision.HOLD_FOR_REVIEW
        val byId = opportunities.groupBy { it.occurrenceId }
        if (byId.values.any { rows -> rows.distinct().size != 1 }) return CrownActivityDecision.HOLD_FOR_REVIEW
        val inWindow = byId.values.map { it.first() }.filter { !it.at.isBefore(start) && it.at.isBefore(end) }
        if (inWindow.any { it.at.isAfter(now) || it.status == CrownOpportunityStatus.UNKNOWN }) {
            return CrownActivityDecision.HOLD_FOR_REVIEW
        }
        val eligible = inWindow.filter { it.status == CrownOpportunityStatus.VERIFIED_ELIGIBLE }
        if (eligible.any { it.participationVerified }) return CrownActivityDecision.PARTICIPATED
        if (now.isBefore(end)) return CrownActivityDecision.WINDOW_OPEN
        if (eligible.size < minimumOpportunities) return CrownActivityDecision.INSUFFICIENT_OPPORTUNITIES
        return CrownActivityDecision.INACTIVITY_REVIEW
    }
}
