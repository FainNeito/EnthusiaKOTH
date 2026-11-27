package net.badgersmc.ek.domain

import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

data class CrownSchedulePlan(
    val zone: ZoneId,
    val anchorWeek: LocalDate,
    val weekday: DayOfWeek,
    val rotatingTimes: List<LocalTime>,
) {
    init {
        require(anchorWeek.dayOfWeek == weekday) { "Anchor must be on the configured weekday" }
        require(rotatingTimes.isNotEmpty()) { "At least one event time is required" }
    }
}

data class CrownScheduledSlot(val date: LocalDate, val instant: Instant, val rotationIndex: Int)

/** Preview only. Persistence, queueing, notifications and rewards are deliberately separate. */
object CrownSchedule {
    /** Gap times are omitted; overlap times use the earlier offset exactly once. */
    fun slots(plan: CrownSchedulePlan, from: Instant, until: Instant): List<CrownScheduledSlot> {
        require(!until.isBefore(from))
        require(Duration.between(from, until) <= Duration.ofDays(370)) { "Preview is limited to 370 days" }
        var date = from.atZone(plan.zone).toLocalDate().minusDays(1)
        val last = until.atZone(plan.zone).toLocalDate().plusDays(1)
        val result = mutableListOf<CrownScheduledSlot>()
        while (!date.isAfter(last)) {
            if (date.dayOfWeek == plan.weekday) {
                val weeks = ChronoUnit.WEEKS.between(plan.anchorWeek, date)
                val index = Math.floorMod(weeks, plan.rotatingTimes.size.toLong()).toInt()
                val local = LocalDateTime.of(date, plan.rotatingTimes[index])
                val offset = plan.zone.rules.getValidOffsets(local).firstOrNull()
                if (offset != null) {
                    val instant = local.toInstant(offset)
                    if (!instant.isBefore(from) && instant.isBefore(until)) {
                        result += CrownScheduledSlot(date, instant, index)
                    }
                }
            }
            date = date.plusDays(1)
        }
        return result
    }
}
