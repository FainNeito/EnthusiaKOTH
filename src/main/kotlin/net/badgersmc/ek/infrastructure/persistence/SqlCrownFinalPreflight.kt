package net.badgersmc.ek.infrastructure.persistence

import javax.sql.DataSource
import net.badgersmc.ek.application.CrownCustodyCapabilityPort
import net.badgersmc.ek.application.CrownQuestRequirements
import net.badgersmc.ek.application.CustodyCapability
import net.badgersmc.ek.domain.CrownEventPurpose

enum class CrownFinalPreflightResult {
    DISABLED, UNAVAILABLE, QUEST_INCOMPLETE, RECOVERY_POLICY_UNSET, PROVIDER_UNSUPPORTED, PROVIDER_ERROR,
}

/** Read-only bridge: never delivers a Crown or authorizes equipment changes. Run off the server thread. */
class SqlCrownFinalPreflight(private val source: DataSource, private val custody: CrownCustodyCapabilityPort) {
    fun check(occurrenceId: String, revision: String, rules: CrownQuestRequirements): CrownFinalPreflightResult {
        if (!rules.enabled) return CrownFinalPreflightResult.DISABLED
        val final = SqlCrownFinalObservation(source).read(occurrenceId, revision) ?: return CrownFinalPreflightResult.UNAVAILABLE
        val requiresQuest = when (final.purpose) {
            CrownEventPurpose.FIRST_CROWN_FINAL -> true
            CrownEventPurpose.RECOVERY_CROWN_FINAL -> rules.recoveryRequiresQuest
                ?: return CrownFinalPreflightResult.RECOVERY_POLICY_UNSET
            CrownEventPurpose.ORDINARY, CrownEventPurpose.HOLDER_CHALLENGE -> return CrownFinalPreflightResult.UNAVAILABLE
        }
        val totals = final.questTotals
        if (requiresQuest && (totals.wins < rules.wins || totals.scoringSeconds < rules.scoringSeconds ||
                totals.distinctUtcDays < rules.distinctDays)) return CrownFinalPreflightResult.QUEST_INCOMPLETE
        return try {
            when (custody.inspect()) { CustodyCapability.UNSUPPORTED -> CrownFinalPreflightResult.PROVIDER_UNSUPPORTED }
        } catch (_: Exception) { CrownFinalPreflightResult.PROVIDER_ERROR }
    }
}
