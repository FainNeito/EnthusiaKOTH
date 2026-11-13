package net.badgersmc.ek.infrastructure.display

import io.mockk.every
import io.mockk.mockk
import net.badgersmc.ek.application.BlitzRound
import net.badgersmc.ek.application.ScheduledOccurrence
import net.badgersmc.ek.infrastructure.bukkit.KothCommand
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.command.Command
import org.bukkit.entity.Player
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

class HologramRendererTest {
    private val now = Instant.parse("2026-11-14T18:07:00Z")
    private val plain = PlainTextComponentSerializer.plainText()
    private fun round(n: Int, arena: String, at: String) = ScheduledOccurrence(
        BlitzRound.id(BlitzRound(LocalDate.parse("2026-11-14"), 0, LocalTime.of(18, 0), n, 3, arena), 0), arena, Instant.parse(at))
    private val rounds = listOf(round(1, "capture", "2026-11-14T18:00:00Z"), round(2, "moving", "2026-11-14T18:06:00Z"), round(3, "capture", "2026-11-14T18:12:00Z"))

    private fun renderer(live: LiveArena? = null, top: List<Pair<String, Int>> = emptyList(), liveArena: String = "capture",
        liveOccurrence: String? = null) = HologramRenderer(
        clock = Clock.fixed(now, ZoneOffset.UTC), zone = { ZoneId.of("UTC") }, arenaIds = { listOf("capture", "moving") },
        arenaName = { Component.text(it.replaceFirstChar(Char::uppercase)) }, arenaFamily = { if (it == "moving") "moving" else "capture" },
        live = { if (it == liveArena) live else null }, nextStart = { if (it == "capture") Instant.parse("2026-11-14T18:12:00Z") else null },
        upcoming = { rounds.drop(2) }, nextBlitz = { rounds }, blitzRoundSeconds = { 300 }, occurrenceLive = { it == liveOccurrence }, top = { _, _ -> top },
        placeholder = { if (it == "current_koth") "capture" else "" }, custom = { Component.text(it) })

    private fun text(type: HologramType, renderer: HologramRenderer = renderer(), arena: String? = null) =
        renderer.render(HologramSpec("h", type, "world", 0.0, 0.0, 0.0, arena)).map(plain::serialize)

    @Test fun `arena hologram shows the next start when idle and the holder and progress when live`() {
        val idle = text(HologramType.ARENA, arena = "capture")
        assertEquals(listOf("Capture", "Capture KOTH", "Next start: 18:12 (in 5m 0s)"), idle)
        val live = text(HologramType.ARENA, renderer(LiveArena("FainNoir", Instant.parse("2026-11-14T18:10:30Z"), 45.0)), "capture")
        assertTrue("Holding: FainNoir" in live, live.toString())
        assertTrue("■■■■□□□□□□ 45%" in live, live.toString())
        assertTrue("Ends in: 3m 30s" in live, live.toString())
        assertEquals("Arena 'gone' is not loaded", text(HologramType.ARENA, arena = "gone")[1])
    }

    @Test fun `blitz hologram marks each round done, live or upcoming`() {
        val running = renderer(liveOccurrence = rounds[1].id)
        assertEquals(listOf("Weekend Blitz", "Running now", "1. Capture  done", "2. Moving  live", "3. Capture  18:12"), text(HologramType.BLITZ, running))
        // A round staff stopped early is finished, not live, even inside its slot.
        assertEquals("2. Moving  done", text(HologramType.BLITZ)[3])
        // Another event running in that arena is not the round, so the round still reads as finished.
        val other = renderer(LiveArena("FainNoir", Instant.parse("2026-11-14T18:10:00Z"), null), liveArena = "moving")
        assertEquals("2. Moving  done", text(HologramType.BLITZ, other)[3])
        assertEquals("Capture  18:12 (in 5m 0s)  Blitz 3/3", text(HologramType.SCHEDULE)[1])
    }

    @Test fun `leaderboards and placeholders read plainly`() {
        assertEquals(listOf("Top KOTH Players", "No wins yet"), text(HologramType.TOP_PLAYERS))
        assertEquals("1. FainNoir  1 win", text(HologramType.TOP_GUILDS, renderer(top = listOf("FainNoir" to 1)))[1])
        val placeholders = text(HologramType.PLACEHOLDERS)
        assertTrue("%enthusiakoth_current_koth%  capture" in placeholders, placeholders.toString())
        assertTrue("%enthusiakoth_moving_capper%  (empty)" in placeholders, placeholders.toString())
    }

    @Test fun `custom lines keep their MiniMessage colours around coloured placeholder values`() {
        val values = mapOf("%enthusiakoth_next_name%" to "§cSummit§r", "%enthusiakoth_next_koth_time%" to "10m 28s")
        val line = customLine("<color:#fff6e0>Next: <color:#ffcf1f>%enthusiakoth_next_name% <color:#fff6e0>in %enthusiakoth_next_koth_time% %other_x%") { values[it] }
        assertEquals("Next: Summit in 10m 28s %other_x%", plain.serialize(line))
    }

    @Test fun `hologram commands tab complete their actions, types, arenas and names`() {
        val service = mockk<KothHologramService> { every { names() } returns listOf("spawn-board") }
        val command = KothCommand(mockk(relaxed = true), { mockk(relaxed = true) }, mockk(relaxed = true), mockk(relaxed = true),
            mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true),
            { mapOf("capture" to mockk(), "moving" to mockk()) }, {}, {}, { _, _, _ -> "" }, { _, _ -> "" }, { _, _ -> "" }, { emptyList() },
            holograms = { service })
        val admin = mockk<Player>(relaxed = true).also { every { it.hasPermission(any<String>()) } returns true }
        fun complete(vararg args: String) = command.onTabComplete(admin, mockk<Command>(), "ekoth", arrayOf(*args))
        assertTrue("hologram" in complete("holo"))
        assertEquals(listOf("create", "move", "remove", "list"), complete("hologram", ""))
        assertEquals(listOf("top-players", "top-guilds"), complete("hologram", "create", "board", "top"))
        assertEquals(listOf("capture", "moving"), complete("hologram", "create", "board", "arena", ""))
        assertEquals(listOf("spawn-board"), complete("hologram", "remove", ""))
    }
}
