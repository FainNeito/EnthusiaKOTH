package net.badgersmc.ek.application

import net.badgersmc.ek.domain.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.UUID

class CrownFinalEligibilityTest {
    private val event = CrownEventEvidence(CrownEventPurpose.FIRST_CROWN_FINAL, TeamMode.SOLO, EventKind.SCHEDULED,
        false, EventState.COMPLETED, true, true, true, true, true, true, true)
    private val rules = CrownQuestRequirements(true, 4, 100, 3)
    private val progress = CrownVerifiedProgress(4, 100, 3)

    @Test fun `first final needs enabled rules verified event and all quest thresholds`() {
        assertEquals(CrownFinalEligibility.DISABLED, CrownFinalEligibilityPolicy.evaluate(event, progress, rules.copy(enabled = false)))
        assertEquals(CrownFinalEligibility.FIRST_CANDIDATE, CrownFinalEligibilityPolicy.evaluate(event, progress, rules))
        assertEquals(CrownFinalEligibility.QUEST_INCOMPLETE, CrownFinalEligibilityPolicy.evaluate(event, progress.copy(distinctDays = 2), rules))
        assertEquals(CrownFinalEligibility.HOLD_FOR_REVIEW, CrownFinalEligibilityPolicy.evaluate(event.copy(oppositionVerified = false), progress, rules))
        assertEquals(CrownFinalEligibility.REJECTED, CrownFinalEligibilityPolicy.evaluate(event.copy(teamMode = TeamMode.GUILD), progress, rules))
        assertEquals(CrownFinalEligibility.REJECTED, CrownFinalEligibilityPolicy.evaluate(event.copy(purpose = CrownEventPurpose.ORDINARY), progress, rules))
    }

    @Test fun `recovery remains held until its quest policy is chosen`() {
        val recovery = event.copy(purpose = CrownEventPurpose.RECOVERY_CROWN_FINAL)
        assertEquals(CrownFinalEligibility.HOLD_FOR_REVIEW, CrownFinalEligibilityPolicy.evaluate(recovery, progress, rules))
        assertEquals(CrownFinalEligibility.RECOVERY_CANDIDATE, CrownFinalEligibilityPolicy.evaluate(recovery, progress, rules.copy(recoveryRequiresQuest = false)))
        assertEquals(CrownFinalEligibility.QUEST_INCOMPLETE, CrownFinalEligibilityPolicy.evaluate(recovery, progress.copy(wins = 0), rules.copy(recoveryRequiresQuest = true)))
    }

    @Test fun `a candidate must bind the event result winner and quest revision`() {
        val eventId = UUID.randomUUID(); val player = UUID.randomUUID()
        val binding = CrownFinalBinding(eventId, eventId, player, player, "v1", "v1")
        assertEquals(CrownFinalEligibility.FIRST_CANDIDATE, CrownBoundFinalEligibility.evaluate(binding, event, progress, rules))
        listOf(binding.copy(resultMatchId = UUID.randomUUID()), binding.copy(progressOwnerId = UUID.randomUUID()),
            binding.copy(progressRevision = "v2"), binding.copy(requiredQuestRevision = "", progressRevision = "")).forEach {
            assertEquals(CrownFinalEligibility.HOLD_FOR_REVIEW, CrownBoundFinalEligibility.evaluate(it, event, progress, rules))
        }
    }
}
