package net.badgersmc.ek.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CrownEventPolicyTest {
    private val eligible = CrownEventEvidence(
        purpose = CrownEventPurpose.ORDINARY,
        teamMode = TeamMode.SOLO,
        source = EventKind.SCHEDULED,
        isPrivateTest = false,
        state = EventState.COMPLETED,
        hasWinner = true,
        winnerIsEligible = true,
        arenaApproved = true,
        durableResultVerified = true,
        protectionPolicyAccepted = true,
        oppositionVerified = true,
        finalAcknowledgementVerified = true,
    )

    @Test fun `ordinary accepted solo result may be considered for progress`() {
        assertEquals(CrownResultDecision.QUEST_PROGRESS, CrownEventPolicy.evaluate(eligible))
    }

    @Test fun `private admin guild cancelled and no winner events cannot qualify`() {
        val cases = listOf(
            eligible.copy(isPrivateTest = true), eligible.copy(source = EventKind.PRIVATE_TEST),
            eligible.copy(source = EventKind.ADMIN), eligible.copy(teamMode = TeamMode.GUILD),
            eligible.copy(state = EventState.CANCELLED), eligible.copy(state = EventState.ACTIVE),
            eligible.copy(hasWinner = false), eligible.copy(winnerIsEligible = false),
            eligible.copy(arenaApproved = false),
        )
        cases.forEach { assertEquals(CrownResultDecision.REJECTED, CrownEventPolicy.evaluate(it)) }
    }

    @Test fun `uncertain results opposition and protections hold all event purposes`() {
        for (purpose in CrownEventPurpose.entries) {
            val e = eligible.copy(purpose = purpose)
            listOf(e.copy(durableResultVerified = false), e.copy(protectionPolicyAccepted = false),
                e.copy(oppositionVerified = false)).forEach {
                assertEquals(CrownResultDecision.HOLD_FOR_REVIEW, CrownEventPolicy.evaluate(it))
            }
        }
    }

    @Test fun `finals need acknowledgement and distinguish original award from recovery`() {
        for (purpose in listOf(CrownEventPurpose.FIRST_CROWN_FINAL, CrownEventPurpose.RECOVERY_CROWN_FINAL)) {
            assertEquals(CrownResultDecision.REJECTED, CrownEventPolicy.evaluate(
                eligible.copy(purpose = purpose, finalAcknowledgementVerified = false),
            ))
        }
        assertEquals(CrownResultDecision.FIRST_AWARD_CANDIDATE,
            CrownEventPolicy.evaluate(eligible.copy(purpose = CrownEventPurpose.FIRST_CROWN_FINAL)))
        assertEquals(CrownResultDecision.RECOVERY_AWARD_CANDIDATE,
            CrownEventPolicy.evaluate(eligible.copy(purpose = CrownEventPurpose.RECOVERY_CROWN_FINAL)))
    }

    @Test fun `inactive crown cannot return until its original is securely fenced`() {
        val ready = CrownRecoveryEvidence(true, true, true, true, true)
        assertEquals(CrownCustodyDecision.RETAIN_ORIGINAL, CrownRecoveryPolicy.evaluate(ready.copy(inactivityEstablished = false)))
        assertEquals(CrownCustodyDecision.RETAIN_ORIGINAL, CrownRecoveryPolicy.evaluate(ready.copy(opportunitiesSufficient = false)))
        assertEquals(CrownCustodyDecision.RETAIN_ORIGINAL, CrownRecoveryPolicy.evaluate(ready.copy(expiryReached = false)))
        assertEquals(CrownCustodyDecision.HOLD_FOR_REVIEW, CrownRecoveryPolicy.evaluate(ready.copy(originalInFencedCustody = false)))
        assertEquals(CrownCustodyDecision.HOLD_FOR_REVIEW, CrownRecoveryPolicy.evaluate(ready.copy(originalCannotReappearElsewhereProven = false)))
        assertEquals(CrownCustodyDecision.READY_FOR_RECOVERY_EVENT, CrownRecoveryPolicy.evaluate(ready))
    }
}
