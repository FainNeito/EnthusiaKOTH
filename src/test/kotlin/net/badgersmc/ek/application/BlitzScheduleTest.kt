package net.badgersmc.ek.application

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.badgersmc.ek.config.BlitzConfig
import net.badgersmc.ek.config.EnthusiaKothConfig
import net.badgersmc.ek.config.ScheduleConfig
import net.badgersmc.ek.domain.CaptureZone
import net.badgersmc.ek.domain.EventKind
import net.badgersmc.ek.domain.KothArena
import net.badgersmc.ek.infrastructure.persistence.InMemoryScheduleStateStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

class BlitzScheduleTest {
    private val zone = ZoneId.of("UTC")
    private val saturday = LocalDate.parse("2026-11-14")
    private val arenas = listOf("capture", "moving", "summit").map(::arena)
    private val blitz = BlitzConfig(enabled = true, startTimes = listOf("18:00"), rounds = 4, roundSeconds = 300, captureSeconds = 60, breakSeconds = 60)

    @Test fun `a weekend blitz chains rounds at fixed slots, each in the next arena`() {
        val rounds = service(Instant.parse("2026-11-14T12:00:00Z")).occurrencesForDate(saturday)
        assertEquals(listOf("18:00", "18:06", "18:12", "18:18"), rounds.map { it.instant.atZone(zone).toLocalTime().toString() })
        rounds.zipWithNext().forEach { (a, b) -> assertTrue(a.arenaId != b.arenaId, "back-to-back rounds share ${a.arenaId}") }
        assertEquals(setOf("capture", "moving", "summit"), rounds.take(3).map { it.arenaId }.toSet())
        assertEquals(rounds[0].arenaId, rounds[3].arenaId)
        assertEquals(listOf(1, 2, 3, 4), rounds.map { BlitzRound.parse(it.id)!!.round })
    }

    @Test fun `no blitz on weekdays or while disabled`() {
        assertTrue(service(Instant.parse("2026-11-16T12:00:00Z")).occurrencesForDate(LocalDate.parse("2026-11-16")).isEmpty())
        assertTrue(service(Instant.parse("2026-11-14T12:00:00Z"), blitz.copy(enabled = false)).occurrencesForDate(saturday).isEmpty())
    }

    @Test fun `configured rotation keeps its order and skips arenas that are not loaded`() {
        val rotation = BlitzRound.rotation(blitz.copy(arenas = listOf("summit", "missing", "capture")), arenas, saturday, 0)
        assertEquals(2, rotation.size)
        assertTrue(rotation.map { it.id } in listOf(listOf("summit", "capture"), listOf("capture", "summit")))
    }

    @Test fun `only the opening round gets a warning`() {
        val warnings = mutableListOf<String>()
        val store = InMemoryScheduleStateStore().apply { setLastEvaluation(Instant.parse("2026-11-14T17:54:59Z")) }
        val koth = mockk<KothService>(relaxed = true) { every { queueStart(any(), any(), any(), any(), any()) } returns true }
        val schedule = service(Instant.parse("2026-11-14T17:55:00Z"), store = store, koth = koth, warnings = warnings)
        listOf("17:55:00", "18:00:00", "18:01:00", "18:06:00", "18:07:00").forEach { schedule.tickAt(Instant.parse("2026-11-14T${it}Z")) }
        assertEquals(1, warnings.size)
        verify(exactly = 1) { koth.queueStart(any(), EventKind.SCHEDULED, Instant.parse("2026-11-14T18:00:00Z"), any(), match { it.startsWith("blitz:") }) }
        verify(exactly = 1) { koth.queueStart(any(), EventKind.SCHEDULED, Instant.parse("2026-11-14T18:06:00Z"), any(), match { it.startsWith("blitz:") }) }
    }

    @Test fun `round IDs round-trip and shorten the arena's match`() {
        val round = BlitzRound(saturday, 1, LocalTime.of(9, 5), 2, 4, "my-arena_2")
        val parsed = BlitzRound.parse(BlitzRound.id(round, 1794506700000))
        assertEquals(round, parsed)
        assertNull(BlitzRound.parse("arena:capture:0:2026-11-14:18:00:1"))
        val shortened = BlitzRound.apply(arena("capture"), blitz.copy(roundSeconds = 120, captureSeconds = 600))
        assertEquals(120, shortened.durationSeconds)
        assertEquals(120, shortened.captureSeconds)
    }

    @Test fun `next blitz lists every round of the coming blitz`() {
        val rounds = service(Instant.parse("2026-11-13T09:00:00Z")).nextBlitz()
        assertEquals(4, rounds.size)
        assertEquals(Instant.parse("2026-11-14T18:00:00Z"), rounds.first().instant)
        val running = service(Instant.parse("2026-11-14T18:13:00Z")).nextBlitz()
        assertEquals(Instant.parse("2026-11-14T18:00:00Z"), running.first().instant)
    }

    @Test fun `a long blitz still queues rounds that fall days after it opened`() {
        val long = blitz.copy(rounds = 48, roundSeconds = 7200, breakSeconds = 3600)
        val round12 = Instant.parse("2026-11-16T03:00:00Z")
        val koth = mockk<KothService>(relaxed = true) { every { queueStart(any(), any(), any(), any(), any()) } returns true }
        val store = InMemoryScheduleStateStore().apply { setLastEvaluation(round12.minusSeconds(1)) }
        service(round12, long, store, koth).tickAt(round12)
        verify(exactly = 1) { koth.queueStart(any(), EventKind.SCHEDULED, round12, any(), match { BlitzRound.parse(it)?.round == 12 }) }
    }

    @Test fun `next event and per-arena starts find a weekend blitz from midweek`() {
        val monday = service(Instant.parse("2026-11-16T12:00:00Z"))
        assertEquals(Instant.parse("2026-11-21T18:00:00Z"), monday.nextScheduledStart())
        val rounds = monday.nextBlitz()
        val opener = rounds.first().arenaId
        assertEquals(rounds.first().instant, monday.nextStartFor(opener)!!.instant)
        val second = rounds[1]
        assertEquals(second.instant, monday.nextStartFor(second.arenaId)!!.instant)
    }

    private fun service(
        now: Instant,
        config: BlitzConfig = blitz,
        store: InMemoryScheduleStateStore = InMemoryScheduleStateStore(),
        koth: KothService = mockk(relaxed = true),
        warnings: MutableList<String> = mutableListOf(),
    ) = ScheduleService(
        cfgLoader = { EnthusiaKothConfig(schedule = ScheduleConfig(enabled = true, zone = zone, preStartWarningSeconds = 300, times = emptyList(), blitz = config)) },
        kothService = koth,
        arenas = { arenas.associateBy { it.id } },
        stateStore = store,
        clock = Clock.fixed(now, ZoneOffset.UTC),
        warningSink = { id, _ -> warnings += id },
        logger = {},
    )

    private fun arena(id: String) = KothArena(id = id, family = "capture", zone = mockk<CaptureZone>(relaxed = true), durationSeconds = 900, captureSeconds = 120)
}
