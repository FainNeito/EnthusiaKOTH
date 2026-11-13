package net.badgersmc.ek.application

import net.badgersmc.ek.config.BlitzConfig
import net.badgersmc.ek.domain.KothArena
import java.time.LocalDate
import java.time.LocalTime

/** One round of a weekend blitz, recovered from its scheduled occurrence ID. */
data class BlitzRound(
    val date: LocalDate,
    val startIndex: Int,
    val startTime: LocalTime,
    val round: Int,
    val rounds: Int,
    val arenaId: String,
) {
    enum class Status { UPCOMING, LIVE, DONE }

    val isFirst: Boolean get() = round == 1
    val isLast: Boolean get() = round == rounds

    companion object {
        private val PATTERN = Regex("^blitz:(\\d{4}-\\d{2}-\\d{2}):(\\d+):(\\d{2})(\\d{2}):r(\\d+)of(\\d+):(.+):(-?\\d+)$")

        fun id(round: BlitzRound, epochMillis: Long): String =
            "blitz:${round.date}:${round.startIndex}:%02d%02d:r${round.round}of${round.rounds}:${round.arenaId}:$epochMillis"
                .format(round.startTime.hour, round.startTime.minute)

        fun parse(occurrenceId: String?): BlitzRound? {
            val match = PATTERN.matchEntire(occurrenceId ?: return null) ?: return null
            val (date, index, hour, minute, round, rounds, arena) = match.destructured
            return runCatching {
                BlitzRound(LocalDate.parse(date), index.toInt(), LocalTime.of(hour.toInt(), minute.toInt()), round.toInt(), rounds.toInt(), arena)
            }.getOrNull()?.takeIf { it.round in 1..it.rounds }
        }

        /** The rotation for one blitz: configured arenas that are loaded, else every loaded arena, shifted so each blitz opens somewhere new. */
        fun rotation(cfg: BlitzConfig, available: Collection<KothArena>, date: LocalDate, startIndex: Int): List<KothArena> {
            val byId = available.associateBy { it.id }
            val order = if (cfg.arenas.isEmpty()) available.sortedBy { it.id } else cfg.arenas.mapNotNull(byId::get)
            if (order.isEmpty()) return emptyList()
            val shift = Math.floorMod(date.toEpochDay() * 7 + startIndex, order.size.toLong()).toInt()
            return order.drop(shift) + order.take(shift)
        }

        /** How a round reads: live only while its own event runs, finished once its slot ends or it was stopped. */
        fun status(now: java.time.Instant, start: java.time.Instant, roundSeconds: Int, running: Boolean): Status = when {
            now.isBefore(start) -> Status.UPCOMING
            now.isBefore(start.plusSeconds(roundSeconds.toLong())) && running -> Status.LIVE
            else -> Status.DONE
        }

        /** Blitz rounds are shorter than the arena's own match; the capture can never outlast the round. */
        fun apply(arena: KothArena, cfg: BlitzConfig): KothArena = arena.copy(
            durationSeconds = cfg.roundSeconds,
            captureSeconds = cfg.captureSeconds.coerceAtMost(cfg.roundSeconds),
        )
    }
}
