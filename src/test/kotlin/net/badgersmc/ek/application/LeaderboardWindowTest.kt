package net.badgersmc.ek.application

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.time.*

class LeaderboardWindowTest {
    private val zone = ZoneId.of("America/New_York")
    @Test fun `daily range observes DST and exact inclusive exclusive boundaries`() {
        val window = LeaderboardWindow.forPeriod("daily", Instant.parse("2026-11-01T16:00:00Z"), zone, null)!!
        assertEquals(25, Duration.between(window.from, window.until).toHours())
        assertTrue(window.contains(window.from)); assertFalse(window.contains(window.until)); assertFalse(window.contains(window.from.minusNanos(1)))
    }
    @Test fun `weekly starts Monday in configured timezone`() {
        val window = LeaderboardWindow.forPeriod("weekly", Instant.parse("2026-10-08T16:00:00Z"), zone, null)!!
        assertEquals(LocalDate.parse("2026-10-05"), window.from.atZone(zone).toLocalDate())
    }
    @Test fun `season requires valid nonfuture start without resetting lifetime`() {
        val now = Instant.parse("2026-10-08T16:00:00Z")
        assertNull(LeaderboardWindow.forPeriod("season", now, zone, null)); assertNull(LeaderboardWindow.forPeriod("season", now, zone, "2026-10-09"))
        assertEquals(LocalDate.parse("2026-10-01"), LeaderboardWindow.forPeriod("season", now, zone, "2026-10-01")!!.from.atZone(zone).toLocalDate())
    }
}
