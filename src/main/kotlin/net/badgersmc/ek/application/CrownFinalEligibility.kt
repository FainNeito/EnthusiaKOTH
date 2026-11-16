package net.badgersmc.ek.application

import net.badgersmc.ek.domain.CrownEventEvidence
import net.badgersmc.ek.domain.CrownEventPolicy
import net.badgersmc.ek.domain.CrownEventPurpose
import net.badgersmc.ek.domain.CrownResultDecision
import java.util.UUID

/** A disabled-by-default candidate check; never authorizes item delivery or inventory changes. */
data class CrownQuestRequirements(val enabled: Boolean = false, val wins: Int, val scoringSeconds: Long, val distinctDays: Int, val recoveryRequiresQuest: Boolean? = null) {
    init { require(wins > 0 && scoringSeconds > 0 && distinctDays > 0) }
}

data class CrownVerifiedProgress(val wins: Int, val scoringSeconds: Long, val distinctDays: Int) {
    init { require(wins >= 0 && scoringSeconds >= 0 && distinctDays >= 0) }
}

enum class CrownFinalEligibility { DISABLED, REJECTED, HOLD_FOR_REVIEW, QUEST_INCOMPLETE, FIRST_CANDIDATE, RECOVERY_CANDIDATE }

object CrownFinalEligibilityPolicy {
    fun evaluate(event: CrownEventEvidence, progress: CrownVerifiedProgress, rules: CrownQuestRequirements): CrownFinalEligibility {
        if (!rules.enabled) return CrownFinalEligibility.DISABLED
        if (event.purpose == CrownEventPurpose.ORDINARY) return CrownFinalEligibility.REJECTED
        return when (CrownEventPolicy.evaluate(event)) {
            CrownResultDecision.HOLD_FOR_REVIEW -> CrownFinalEligibility.HOLD_FOR_REVIEW
            CrownResultDecision.FIRST_AWARD_CANDIDATE -> {
                if (progress.wins >= rules.wins && progress.scoringSeconds >= rules.scoringSeconds &&
                    progress.distinctDays >= rules.distinctDays) CrownFinalEligibility.FIRST_CANDIDATE
                else CrownFinalEligibility.QUEST_INCOMPLETE
            }
            CrownResultDecision.RECOVERY_AWARD_CANDIDATE -> when (rules.recoveryRequiresQuest) {
                null -> CrownFinalEligibility.HOLD_FOR_REVIEW
                false -> CrownFinalEligibility.RECOVERY_CANDIDATE
                true -> if (progress.wins >= rules.wins && progress.scoringSeconds >= rules.scoringSeconds &&
                    progress.distinctDays >= rules.distinctDays) CrownFinalEligibility.RECOVERY_CANDIDATE
                    else CrownFinalEligibility.QUEST_INCOMPLETE
            }
            else -> CrownFinalEligibility.REJECTED
        }
    }
}

/** Identifiers must come from a single consistent read of the durable records. */
data class CrownFinalBinding(
    val occurrenceMatchId: UUID,
    val resultMatchId: UUID,
    val winnerId: UUID,
    val progressOwnerId: UUID,
    val requiredQuestRevision: String,
    val progressRevision: String,
)

object CrownBoundFinalEligibility {
    fun evaluate(binding: CrownFinalBinding, event: CrownEventEvidence, progress: CrownVerifiedProgress,
                 rules: CrownQuestRequirements): CrownFinalEligibility {
        if (!rules.enabled) return CrownFinalEligibility.DISABLED
        if (binding.requiredQuestRevision.isBlank() || binding.requiredQuestRevision != binding.progressRevision ||
            binding.occurrenceMatchId != binding.resultMatchId || binding.winnerId != binding.progressOwnerId) {
            return CrownFinalEligibility.HOLD_FOR_REVIEW
        }
        return CrownFinalEligibilityPolicy.evaluate(event, progress, rules)
    }
}
