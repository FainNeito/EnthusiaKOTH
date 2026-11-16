package net.badgersmc.ek.domain

/** Pure eligibility decisions. This policy neither persists progress nor awards items. */
enum class CrownEventPurpose { ORDINARY, FIRST_CROWN_FINAL, RECOVERY_CROWN_FINAL, HOLDER_CHALLENGE }

enum class CrownResultDecision { REJECTED, HOLD_FOR_REVIEW, QUEST_PROGRESS, FIRST_AWARD_CANDIDATE, RECOVERY_AWARD_CANDIDATE }

data class CrownEventEvidence(
    val purpose: CrownEventPurpose,
    val teamMode: TeamMode,
    val source: EventKind,
    val isPrivateTest: Boolean,
    val state: EventState,
    val hasWinner: Boolean,
    val winnerIsEligible: Boolean,
    val arenaApproved: Boolean,
    val durableResultVerified: Boolean,
    val protectionPolicyAccepted: Boolean,
    val oppositionVerified: Boolean,
    val finalAcknowledgementVerified: Boolean,
)

object CrownEventPolicy {
    /** A candidate is not a delivery authorization; custody and uniqueness remain external gates. */
    fun evaluate(e: CrownEventEvidence): CrownResultDecision {
        if (e.isPrivateTest || e.source == EventKind.PRIVATE_TEST || e.source == EventKind.ADMIN ||
            e.teamMode != TeamMode.SOLO || e.state != EventState.COMPLETED ||
            !e.hasWinner || !e.winnerIsEligible || !e.arenaApproved
        ) return CrownResultDecision.REJECTED

        // Missing trustworthy protection or result evidence must never be treated as a win.
        if (!e.durableResultVerified || !e.protectionPolicyAccepted || !e.oppositionVerified) {
            return CrownResultDecision.HOLD_FOR_REVIEW
        }
        return when (e.purpose) {
            CrownEventPurpose.ORDINARY, CrownEventPurpose.HOLDER_CHALLENGE -> CrownResultDecision.QUEST_PROGRESS
            CrownEventPurpose.FIRST_CROWN_FINAL -> if (e.finalAcknowledgementVerified) {
                CrownResultDecision.FIRST_AWARD_CANDIDATE
            } else CrownResultDecision.REJECTED
            CrownEventPurpose.RECOVERY_CROWN_FINAL -> if (e.finalAcknowledgementVerified) {
                CrownResultDecision.RECOVERY_AWARD_CANDIDATE
            } else CrownResultDecision.REJECTED
        }
    }
}

enum class CrownCustodyDecision { RETAIN_ORIGINAL, HOLD_FOR_REVIEW, READY_FOR_RECOVERY_EVENT }

/** An inactivity deadline is insufficient: the original Crown must be fenced in custody. */
data class CrownRecoveryEvidence(
    val inactivityEstablished: Boolean,
    val opportunitiesSufficient: Boolean,
    val expiryReached: Boolean,
    val originalInFencedCustody: Boolean,
    val originalCannotReappearElsewhereProven: Boolean,
)

object CrownRecoveryPolicy {
    fun evaluate(e: CrownRecoveryEvidence): CrownCustodyDecision {
        if (!e.inactivityEstablished || !e.opportunitiesSufficient || !e.expiryReached) {
            return CrownCustodyDecision.RETAIN_ORIGINAL
        }
        if (!e.originalInFencedCustody || !e.originalCannotReappearElsewhereProven) {
            return CrownCustodyDecision.HOLD_FOR_REVIEW
        }
        return CrownCustodyDecision.READY_FOR_RECOVERY_EVENT
    }
}
