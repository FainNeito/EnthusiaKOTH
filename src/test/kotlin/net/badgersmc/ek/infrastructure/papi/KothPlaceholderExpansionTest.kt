package net.badgersmc.ek.infrastructure.papi

import io.mockk.every
import io.mockk.mockk
import net.badgersmc.ek.application.KothService
import net.badgersmc.ek.application.ScheduleService
import net.badgersmc.ek.domain.*
import net.badgersmc.ek.infrastructure.lumaguilds.LumaGuildsAdapter
import net.badgersmc.ek.infrastructure.persistence.SqlStatsRepository
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.bukkit.entity.Player
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class KothPlaceholderExpansionTest {
    private val now = Instant.parse("2026-10-10T12:00:00Z")
    private val owner = UUID.randomUUID()
    private fun event(family: String = "capture", private: Boolean = false): KothEvent {
        val arena = KothArena("hill", family, CaptureZone("hill", "world", mockk(), mockk()),
            durationSeconds = 900, captureSeconds = 120, displayName = "&#12ABEF&lSummit 🏆")
        return KothEvent(UUID.randomUUID(), arena, now, now.plusSeconds(900), owner = owner, isPrivateTest = private)
            .also { it.currentController = TeamId(TeamMode.GUILD, owner); it.setScore(it.currentController!!, 30.0) }
    }
    private fun expansion(event: KothEvent): KothPlaceholderExpansion {
        val service = mockk<KothService>(relaxed = true)
        every { service.activeEvent } returns event
        every { service.eventForArena("hill") } returns event
        every { service.capperName(event) } returns "Enthusiast"
        val schedule = mockk<ScheduleService>(relaxed = true)
        every { schedule.nextEventInfo() } returns ("hill" to "15m 0s")
        return KothPlaceholderExpansion(service, schedule, mockk<SqlStatsRepository>(relaxed = true),
            mockk<LumaGuildsAdapter>(relaxed = true), { mapOf("hill" to event.arena) }, Clock.fixed(now, ZoneOffset.UTC))
    }
    @Test fun `actual expansion formats RGB emojis resets and reads capture score`() {
        val e = event(); val papi = expansion(e)
        val name = papi.onPlaceholderRequest(null, "hill_name")!!
        assertEquals("§x§1§2§a§b§e§f§lSummit 🏆§r", name)
        assertEquals(name, papi.onPlaceholderRequest(null, "current_name"))
        assertEquals(name, papi.onPlaceholderRequest(null, "next_name"))
        assertEquals("hill", papi.onPlaceholderRequest(null, "current_koth"))
        assertEquals("25.0", papi.onPlaceholderRequest(null, "hill_capture_progress"))
        assertEquals("1m 30s", papi.onPlaceholderRequest(null, "current_capture_timeleft"))
        e.currentController = null
        assertEquals("0.0", papi.onPlaceholderRequest(null, "current_capture_progress"))
        assertEquals("120", papi.onPlaceholderRequest(null, "current_capture_secondsleft"))
    }
    @Test fun `timed score modes have no capture countdown`() {
        for (family in listOf("score", "moving", "conquest")) {
            val papi = expansion(event(family))
            assertEquals("N/A", papi.onPlaceholderRequest(null, "hill_capture_progress"))
            assertEquals("N/A", papi.onPlaceholderRequest(null, "current_capture_secondsleft"))
            assertEquals("15m 0s", papi.onPlaceholderRequest(null, "hill_timeleft"))
        }
    }
    @Test fun `private event is hidden from playerless holograms`() {
        val papi = expansion(event(private = true))
        assertEquals("None", papi.onPlaceholderRequest(null, "current_name"))
        assertEquals("0", papi.onPlaceholderRequest(null, "hill_capture_progress"))
        assertEquals("Not Active", papi.onPlaceholderRequest(null, "hill_capture_timeleft"))
        val participant = mockk<Player>(); every { participant.uniqueId } returns owner
        assertEquals("25.0", papi.onPlaceholderRequest(participant, "hill_capture_progress"))
        val outsider = mockk<Player>(); every { outsider.uniqueId } returns UUID.randomUUID()
        assertEquals("0", papi.onPlaceholderRequest(outsider, "current_capture_progress"))
    }
}
