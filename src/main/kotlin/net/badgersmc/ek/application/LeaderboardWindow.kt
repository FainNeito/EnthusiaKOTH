package net.badgersmc.ek.application

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

data class LeaderboardWindow(val from: Instant, val until: Instant) {
    fun contains(instant: Instant) = !instant.isBefore(from) && instant.isBefore(until)
    companion object {
        fun forPeriod(period: String, now: Instant, zone: ZoneId, seasonStart: String?): LeaderboardWindow? {
            val date = now.atZone(zone).toLocalDate()
            val start = when (period.lowercase()) {
                "daily" -> date
                "weekly" -> date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                "season" -> runCatching { LocalDate.parse(seasonStart) }.getOrNull()?.takeIf { !it.isAfter(date) } ?: return null
                else -> return null
            }
            val end = when (period.lowercase()) { "daily" -> start.plusDays(1); "weekly" -> start.plusWeeks(1); else -> date.plusDays(1) }
            return LeaderboardWindow(start.atStartOfDay(zone).toInstant(), end.atStartOfDay(zone).toInstant())
        }
    }
}
