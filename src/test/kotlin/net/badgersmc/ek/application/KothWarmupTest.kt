package net.badgersmc.ek.application

import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import net.badgersmc.ek.config.DiscordConfig
import net.badgersmc.ek.config.DisplayConfig
import net.badgersmc.ek.config.EnthusiaKothConfig
import net.badgersmc.ek.domain.CaptureZone
import net.badgersmc.ek.domain.EventKind
import net.badgersmc.ek.domain.KothArena
import net.badgersmc.ek.domain.KothEvent
import net.badgersmc.ek.infrastructure.discord.DiscordWebhookService
import net.badgersmc.ek.infrastructure.display.ZoneBorderService
import net.badgersmc.ek.infrastructure.lumaguilds.LumaGuildsAdapter
import net.badgersmc.ek.infrastructure.persistence.InMemoryEventQueueStore
import net.badgersmc.ek.infrastructure.persistence.SqlStatsRepository
import net.badgersmc.nexus.i18n.LangService
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID

class KothWarmupTest {
    private val start = Instant.parse("2026-11-13T12:00:00Z")
    private var now = start
    private val clock = object : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?): Clock = this
        override fun instant(): Instant = now
    }

    @BeforeEach fun mockBukkit() {
        mockkStatic(Bukkit::class)
        every { Bukkit.getOnlinePlayers() } returns mutableListOf()
        every { Bukkit.getConsoleSender() } returns mockk(relaxed = true)
        every { Bukkit.getWorld(any<String>()) } returns null
        every { Bukkit.getPlayer(any<UUID>()) } returns null
    }

    @AfterEach fun cleanup() = unmockkStatic(Bukkit::class)

    private fun service(warmup: Int, opened: MutableList<KothEvent>): KothService {
        val lang = mockk<LangService>(relaxed = true)
        every { lang.msg(any(), *anyVararg()) } returns Component.empty()
        return KothService(
            cfgLoader = { EnthusiaKothConfig(display = DisplayConfig(zoneBorder = false), discord = DiscordConfig(enabled = false)) },
            stats = mockk<SqlStatsRepository>(relaxed = true),
            economy = mockk<PlayerEconomy>(relaxed = true),
            guilds = mockk<LumaGuildsAdapter>(relaxed = true),
            displayService = mockk<DisplayService>(relaxed = true),
            fireworkService = mockk<FireworkCelebrationService>(relaxed = true),
            discordWebhook = mockk<DiscordWebhookService>(relaxed = true),
            zoneBorderService = mockk<ZoneBorderService>(relaxed = true),
            lang = lang,
            arenaResolver = { null },
            queueStore = InMemoryEventQueueStore(),
            clock = clock,
            logger = { _, _ -> },
            arenaWarmup = { warmup },
            liveEffects = { event, _ -> opened += event },
        )
    }

    private val arena = KothArena(id = "capture", family = "capture", zone = mockk<CaptureZone>(relaxed = true), durationSeconds = 600, captureSeconds = 120)

    @Test fun `a KOTH waits for its arena to build, then opens once`() {
        val opened = mutableListOf<KothEvent>()
        val koth = service(warmup = 5, opened)
        assertTrue(koth.startEvent(arena, kind = EventKind.ADMIN))
        val event = koth.activeEvent!!
        assertEquals(start.plusSeconds(5), event.liveAt)
        assertEquals(start.plusSeconds(5 + 600), event.endsAt)
        assertFalse(event.liveAnnounced)
        assertTrue(opened.isEmpty())

        now = start.plusSeconds(3)
        koth.tick()
        assertFalse(event.liveAnnounced, "still building at 3 s")

        now = start.plusSeconds(5)
        koth.tick()
        assertTrue(event.liveAnnounced)
        now = start.plusSeconds(6)
        koth.tick()
        assertEquals(listOf(event), opened, "the opening plays exactly once")
    }

    @Test fun `an arena without a build opens at once`() {
        val opened = mutableListOf<KothEvent>()
        val koth = service(warmup = 0, opened)
        assertTrue(koth.startEvent(arena, kind = EventKind.ADMIN))
        val event = koth.activeEvent!!
        assertEquals(event.startsAt, event.liveAt)
        assertEquals(start.plusSeconds(600), event.endsAt)
        assertTrue(event.liveAnnounced)
        assertEquals(listOf(event), opened)
    }
}
