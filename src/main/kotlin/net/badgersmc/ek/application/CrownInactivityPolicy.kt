package net.badgersmc.ek.application

import java.time.Duration
import java.time.Instant
import java.util.UUID
import net.badgersmc.ek.domain.*

data class CrownTenure(val instance: UUID, val holder: UUID, val generation: Long) {
    init { require(generation >= 0) }
}

/** Disabled until all production thresholds and the protected observation adapter are configured. */
data class CrownInactivityPolicy(
    val enabled: Boolean = false,
    val revision: String = "",
    val window: Duration? = null,
    val minimumOpportunities: Int? = null,
) {
    fun validate() {
        if (!enabled) return
        require(revision.matches(Regex("[a-zA-Z0-9_.-]{1,64}")))
        require(window != null && !window.isNegative && !window.isZero && window <= Duration.ofDays(365))
        require(minimumOpportunities != null && minimumOpportunities in 1..1000)
    }
}

data class CrownActivitySnapshot(
    val tenure: CrownTenure,
    val revision: String,
    val observedFrom: Instant,
    val lastObserved: Instant,
    val opportunities: List<CrownOpportunity>,
    val conflicted: Boolean,
)

object CrownInactivityAssessment {
    /** Login/online time is deliberately not proof of meaningful special-event participation. */
    fun assess(policy: CrownInactivityPolicy, snapshot: CrownActivitySnapshot, now: Instant,
               evidenceComplete: Boolean): CrownActivityDecision {
        policy.validate()
        if (!policy.enabled) return CrownActivityDecision.WINDOW_OPEN
        if (snapshot.conflicted || snapshot.revision != policy.revision || now.isBefore(snapshot.lastObserved) ||
            now.isBefore(snapshot.observedFrom) || !evidenceComplete) return CrownActivityDecision.HOLD_FOR_REVIEW
        if (snapshot.opportunities.any { it.at.isAfter(now) || it.status == CrownOpportunityStatus.UNKNOWN }) {
            return CrownActivityDecision.HOLD_FOR_REVIEW
        }
        val lastParticipation = snapshot.opportunities.filter {
            it.status == CrownOpportunityStatus.VERIFIED_ELIGIBLE && it.participationVerified &&
                !it.at.isBefore(snapshot.observedFrom)
        }.maxOfOrNull { it.at }
        val start = lastParticipation ?: snapshot.observedFrom
        // A qualifying participation renews the window; it cannot satisfy its next opportunity count.
        val rows = snapshot.opportunities.filter { lastParticipation == null || it.at.isAfter(lastParticipation) }
        if(now.isBefore(start.plus(policy.window!!)))return CrownActivityDecision.WINDOW_OPEN
        val eligible=rows.filter { !it.at.isBefore(start) && !it.at.isAfter(now) && it.status==CrownOpportunityStatus.VERIFIED_ELIGIBLE }
        // Insufficient opportunities extend the grace period, rather than stranding an expired window forever.
        return if(eligible.distinctBy{it.occurrenceId}.size<policy.minimumOpportunities!!) CrownActivityDecision.INSUFFICIENT_OPPORTUNITIES
            else CrownActivityDecision.INACTIVITY_REVIEW
    }
}
