package net.badgersmc.ek.domain

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class CrownScheduleTest {
    private val ny = ZoneId.of("America/New_York")

    @Test fun `weekly times rotate consistently before and after anchor`() {
        val plan = CrownSchedulePlan(ny, LocalDate.parse("2026-11-08"), DayOfWeek.SUNDAY,
            listOf(LocalTime.of(12, 0), LocalTime.of(20, 0)))
        val slots = CrownSchedule.slots(plan, Instant.parse("2026-11-01T00:00:00Z"), Instant.parse("2026-11-17T00:00:00Z"))
        assertEquals(listOf(1, 0, 1), slots.map { it.rotationIndex })
        assertEquals(listOf("2026-11-02T01:00:00Z", "2026-11-08T17:00:00Z", "2026-11-16T01:00:00Z"), slots.map { it.instant.toString() })
    }

    @Test fun `spring gap is skipped and autumn overlap occurs once at earlier offset`() {
        val spring = CrownSchedulePlan(ny, LocalDate.parse("2026-03-08"), DayOfWeek.SUNDAY, listOf(LocalTime.of(2, 30)))
        assertEquals(emptyList<CrownScheduledSlot>(), CrownSchedule.slots(spring, Instant.parse("2026-03-08T00:00:00Z"), Instant.parse("2026-03-09T00:00:00Z")))
        val autumn = CrownSchedulePlan(ny, LocalDate.parse("2026-11-01"), DayOfWeek.SUNDAY, listOf(LocalTime.of(1, 30)))
        assertEquals(listOf(Instant.parse("2026-11-01T05:30:00Z")), CrownSchedule.slots(autumn, Instant.parse("2026-11-01T00:00:00Z"), Instant.parse("2026-11-02T00:00:00Z")).map { it.instant })
    }

    @Test fun `invalid schedules and unbounded previews are refused`() {
        assertThrows(IllegalArgumentException::class.java) { CrownSchedulePlan(ny, LocalDate.parse("2026-11-02"), DayOfWeek.SUNDAY, listOf(LocalTime.NOON)) }
        assertThrows(IllegalArgumentException::class.java) { CrownSchedulePlan(ny, LocalDate.parse("2026-11-01"), DayOfWeek.SUNDAY, emptyList()) }
        val plan = CrownSchedulePlan(ny, LocalDate.parse("2026-11-01"), DayOfWeek.SUNDAY, listOf(LocalTime.NOON))
        assertThrows(IllegalArgumentException::class.java) { CrownSchedule.slots(plan, Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2028-01-01T00:00:00Z")) }
    }
}
