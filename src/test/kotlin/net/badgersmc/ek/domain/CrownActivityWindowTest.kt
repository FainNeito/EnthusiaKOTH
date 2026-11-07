package net.badgersmc.ek.domain

import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CrownActivityWindowTest {
    private val start = Instant.parse("2026-01-01T00:00:00Z")
    private val end = Instant.parse("2026-02-01T00:00:00Z")
    private val event = CrownOpportunity("event-1", Instant.parse("2026-01-15T00:00:00Z"), CrownOpportunityStatus.VERIFIED_ELIGIBLE, false)
    private fun evaluate(rows: List<CrownOpportunity>, now: Instant = end, complete: Boolean = true, minimum: Int = 1) =
        CrownActivityWindow.evaluate(start, end, now, minimum, rows, complete)

    @Test fun `verified participation satisfies window even before deadline`() {
        assertEquals(CrownActivityDecision.PARTICIPATED, evaluate(listOf(event.copy(participationVerified = true)), event.at))
        assertEquals(CrownActivityDecision.WINDOW_OPEN, evaluate(listOf(event), event.at))
    }

    @Test fun `cancelled events and duplicates cannot satisfy opportunities`() {
        assertEquals(CrownActivityDecision.INSUFFICIENT_OPPORTUNITIES, evaluate(listOf(event.copy(status = CrownOpportunityStatus.CANCELLED_OR_INELIGIBLE, participationVerified = true))))
        assertEquals(CrownActivityDecision.INSUFFICIENT_OPPORTUNITIES, evaluate(listOf(event, event), minimum = 2))
        assertEquals(CrownActivityDecision.INACTIVITY_REVIEW, evaluate(listOf(event)))
    }

    @Test fun `conflicting incomplete and future records fail closed`() {
        assertEquals(CrownActivityDecision.HOLD_FOR_REVIEW, evaluate(listOf(event), complete = false))
        assertEquals(CrownActivityDecision.HOLD_FOR_REVIEW, evaluate(listOf(event.copy(status = CrownOpportunityStatus.UNKNOWN))))
        assertEquals(CrownActivityDecision.HOLD_FOR_REVIEW, evaluate(listOf(event, event.copy(participationVerified = true))))
        assertEquals(CrownActivityDecision.HOLD_FOR_REVIEW, evaluate(listOf(event), start))
    }

    @Test fun `window boundaries exclude a later event`() {
        assertEquals(CrownActivityDecision.INSUFFICIENT_OPPORTUNITIES, evaluate(listOf(event.copy(at = end, participationVerified = true))))
    }
}
