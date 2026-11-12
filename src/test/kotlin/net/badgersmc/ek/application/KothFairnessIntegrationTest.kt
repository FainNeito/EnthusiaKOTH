package net.badgersmc.ek.application

import io.mockk.*
import net.badgersmc.ek.config.*
import net.badgersmc.ek.domain.*
import net.badgersmc.ek.infrastructure.lumaguilds.LumaGuildsAdapter
import net.badgersmc.ek.infrastructure.persistence.*
import net.badgersmc.nexus.i18n.LangService
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.entity.Player
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class KothFairnessIntegrationTest {
    private val now = Instant.parse("2026-10-08T16:00:00Z")
    private val guild = UUID.randomUUID()
    private val capper = mockk<Player>(relaxed = true)
    private val idle = mockk<Player>(relaxed = true)
    private val localPlayers = mutableSetOf<Player>()
    private val delayedSounds = mutableListOf<Pair<Long, () -> Unit>>()
    private val guilds = mockk<LumaGuildsAdapter>(relaxed = true)
    private val stats = mockk<SqlStatsRepository>(relaxed = true)
    private val zone = mockk<CaptureZone>(relaxed = true)
    @BeforeEach fun setup() {
        localPlayers.clear(); localPlayers.add(capper); delayedSounds.clear()
        mockkStatic(Bukkit::class)
        every { capper.uniqueId } returns UUID.randomUUID()
        every { idle.uniqueId } returns UUID.randomUUID()
        every { capper.name } returns "Capper"
        every { idle.name } returns "Idle"
        every { capper.isValid } returns true
        every { capper.isDead } returns false
        every { capper.gameMode } returns GameMode.SURVIVAL
        every { Bukkit.getOnlinePlayers() } returns mutableListOf(capper, idle)
        every { Bukkit.getWorld(any<String>()) } returns null
        every { Bukkit.getPlayer(capper.uniqueId) } returns capper
        every { Bukkit.getPlayer(idle.uniqueId) } returns idle
        every { Bukkit.getConsoleSender() } returns mockk(relaxed = true)
        every { Bukkit.dispatchCommand(any(), any()) } returns true
        every { zone.contains(capper.location) } returns true
        every { guilds.playerGuildId(capper) } returns guild
        every { guilds.playerGuildId(idle) } returns guild
        every { guilds.guildName(guild) } returns "Guild"
    }
    @AfterEach fun cleanup() = unmockkAll()
    private fun service(minimumTeams: Int = 0, mute: Boolean = false, localAudience: Boolean = false): KothService {
        val lang = mockk<LangService>(relaxed = true)
        every { lang.msg(any(), *anyVararg()) } answers { Component.text(firstArg<String>()) }
        return KothService(
            cfgLoader = { EnthusiaKothConfig(display = DisplayConfig(false), fairness = FairnessConfig(minimumParticipatingTeams = minimumTeams)) },
            stats = stats, economy = mockk(relaxed = true), guilds = guilds,
            displayService = mockk(relaxed = true), fireworkService = mockk(relaxed = true),
            discordWebhook = mockk(relaxed = true), zoneBorderService = mockk(relaxed = true), lang = lang,
            arenaResolver = { null }, queueStore = InMemoryEventQueueStore(), clock = Clock.fixed(now, ZoneOffset.UTC),
            logger = { _, _ -> }, notificationsEnabled = { !mute || it !== idle },
            captureAudience = { _, player -> !localAudience || player in localPlayers },
            scheduleSound = { delay, action -> delayedSounds.add(delay to action); Unit },
        )
    }
    private fun arena() = KothArena("capture", "capture", zone, durationSeconds = 60,
        captureSeconds = 1, rewards = listOf("give {CONTRIBUTORS} diamond 1"))
    @Test fun `winning tick qualifies capper without rewarding idle guild member`() {
        val service = service()
        assertTrue(service.startEvent(arena(), teamMode = TeamMode.GUILD))
        service.tick() // enter
        service.tick() // scoring and completion
        assertNull(service.activeEvent)
        verify(exactly = 1) { Bukkit.dispatchCommand(any(), "give Capper diamond 1") }
        verify(exactly = 0) { Bukkit.dispatchCommand(any(), "give Idle diamond 1") }
        verify(exactly = 1) { stats.incrementWin("guild:$guild", "capture") }
    }
    @Test fun `minimum participating teams withholds reward and win credit`() {
        val service = service(minimumTeams = 2)
        assertTrue(service.startEvent(arena(), teamMode = TeamMode.GUILD))
        service.tick(); service.tick()
        assertNull(service.activeEvent)
        verify(exactly = 0) { Bukkit.dispatchCommand(any(), any()) }
        verify(exactly = 0) { stats.incrementWin(any(), any()) }
    }
    @Test fun `public event messages respect opt out`() {
        assertTrue(service(mute = true).startEvent(arena(), teamMode = TeamMode.GUILD))
        verify(atLeast = 1) { capper.sendMessage(any<Component>()) }
        verify(exactly = 0) { idle.sendMessage(any<Component>()) }
    }
    @Test fun `start and winner remain global while capture entry stays local`() {
        val service = service(localAudience = true)
        assertTrue(service.startEvent(arena(), teamMode = TeamMode.GUILD))
        verify(exactly = 1) { idle.sendMessage(Component.text("koth.begin")) }
        service.tick()
        verify(exactly = 1) { capper.sendMessage(Component.text("koth.enter")) }
        verify(exactly = 0) { idle.sendMessage(Component.text("koth.enter")) }
        service.tick()
        verify(exactly = 1) { idle.sendMessage(Component.text("koth.capture")) }
    }
    @Test fun `capture chime and winning fanfare are timed and only reach the capture audience`() {
        val service = service(localAudience = true)
        assertTrue(service.startEvent(arena().copy(captureSeconds = 3), teamMode = TeamMode.GUILD))
        service.tick()
        assertEquals(listOf(4L, 5L), delayedSounds.map { it.first })
        delayedSounds.sortedBy { it.first }.forEach { it.second() }
        delayedSounds.clear()
        service.tick(); service.tick(); service.tick()
        assertEquals(listOf(3L, 8L, 13L), delayedSounds.map { it.first })
        delayedSounds.sortedBy { it.first }.forEach { it.second() }
        verify(exactly = 1) { capper.playSound(any<org.bukkit.Location>(), "minecraft:block.note_block.pling", org.bukkit.SoundCategory.PLAYERS, 0.19f, 0.95f) }
        verify(exactly = 1) { capper.playSound(any<org.bukkit.Location>(), "minecraft:block.note_block.pling", org.bukkit.SoundCategory.PLAYERS, 0.20f, 1.12f) }
        verify(exactly = 1) { capper.playSound(any<org.bukkit.Location>(), "minecraft:block.amethyst_block.chime", org.bukkit.SoundCategory.PLAYERS, 0.07f, 1f) }
        verify(exactly = 1) { capper.playSound(any<org.bukkit.Location>(), "minecraft:block.note_block.bell", org.bukkit.SoundCategory.PLAYERS, 0.45f, 0.75f) }
        verify(exactly = 1) { capper.playSound(any<org.bukkit.Location>(), "minecraft:entity.player.levelup", org.bukkit.SoundCategory.PLAYERS, 0.55f, 1f) }
        verify(exactly = 1) { capper.playSound(any<org.bukkit.Location>(), "minecraft:block.note_block.bell", org.bukkit.SoundCategory.PLAYERS, 0.38f, 1f) }
        verify(exactly = 1) { capper.playSound(any<org.bukkit.Location>(), "minecraft:block.note_block.bell", org.bukkit.SoundCategory.PLAYERS, 0.42f, 1.25f) }
        verify(exactly = 0) { idle.playSound(any<org.bukkit.Location>(), any<String>(), any<org.bukkit.SoundCategory>(), any<Float>(), any<Float>()) }
    }

    @Test fun `the player on the point hears the capture chime even outside the message regions`() {
        val service = service(localAudience = true)
        assertTrue(service.startEvent(arena().copy(captureSeconds = 31), teamMode = TeamMode.GUILD))
        localPlayers.clear()
        service.tick()
        delayedSounds.forEach { it.second() }
        verify(exactly = 1) { capper.playSound(any<org.bukkit.Location>(), "minecraft:block.note_block.pling", org.bukkit.SoundCategory.PLAYERS, 0.19f, 0.95f) }
        verify(exactly = 1) { capper.playSound(any<org.bukkit.Location>(), "minecraft:block.note_block.pling", org.bukkit.SoundCategory.PLAYERS, 0.20f, 1.12f) }
        verify(exactly = 0) { idle.playSound(any<org.bukkit.Location>(), any<String>(), any<org.bukkit.SoundCategory>(), any<Float>(), any<Float>()) }
    }

    @Test fun `delayed capture notes are suppressed after loss of control or audience`() {
        val service = service(localAudience = true)
        assertTrue(service.startEvent(arena().copy(captureSeconds = 31), teamMode = TeamMode.GUILD))
        service.tick()
        // Leaving the audience now means leaving both the message regions and the point itself.
        localPlayers.clear()
        every { zone.contains(capper.location) } returns false
        delayedSounds.forEach { it.second() }
        verify(exactly = 0) { capper.playSound(any<org.bukkit.Location>(), "minecraft:block.note_block.pling", org.bukkit.SoundCategory.PLAYERS, 0.20f, 1.12f) }
        localPlayers.add(capper)
        service.tick()
        delayedSounds.forEach { it.second() }
        verify(exactly = 0) { capper.playSound(any<org.bukkit.Location>(), "minecraft:block.note_block.pling", org.bukkit.SoundCategory.PLAYERS, 0.20f, 1.12f) }
    }

    @Test fun `no winner does not play the victory fanfare`() {
        every { zone.contains(capper.location) } returns false
        val service = service()
        assertTrue(service.startEvent(arena().copy(durationSeconds = 0), durationOverride = 0, teamMode = TeamMode.GUILD))
        service.tick()
        delayedSounds.forEach { it.second() }
        verify(exactly = 0) { capper.playSound(any<org.bukkit.Location>(), "minecraft:block.note_block.bell", org.bukkit.SoundCategory.PLAYERS, any<Float>(), any<Float>()) }
        verify(exactly = 0) { capper.playSound(any<org.bukkit.Location>(), "minecraft:entity.player.levelup", org.bukkit.SoundCategory.PLAYERS, any<Float>(), any<Float>()) }
    }

    @Test fun `winning fanfare rechecks regional audience for delayed notes`() {
        val service = service(localAudience = true)
        assertTrue(service.startEvent(arena(), teamMode = TeamMode.GUILD))
        service.tick(); delayedSounds.clear(); service.tick()
        assertEquals(listOf(3L, 8L, 13L), delayedSounds.map { it.first })
        localPlayers.clear()
        every { zone.contains(capper.location) } returns false
        delayedSounds.forEach { it.second() }
        verify(exactly = 0) { capper.playSound(any<org.bukkit.Location>(), "minecraft:entity.player.levelup", org.bukkit.SoundCategory.PLAYERS, any<Float>(), any<Float>()) }
    }

    @Test fun `sound cues respect notification opt out`() {
        val service = service(mute = true)
        assertTrue(service.startEvent(arena(), teamMode = TeamMode.GUILD))
        service.tick(); service.tick()
        delayedSounds.forEach { it.second() }
        verify(exactly = 0) { idle.playSound(any<org.bukkit.Location>(), any<String>(), any<org.bukkit.SoundCategory>(), any<Float>(), any<Float>()) }
    }

    @Test fun `capture countdown and leave stay local`() {
        val service = service(localAudience = true)
        assertTrue(service.startEvent(arena().copy(captureSeconds = 31), teamMode = TeamMode.GUILD))
        service.tick(); service.tick()
        verify(exactly = 1) { capper.sendMessage(Component.text("koth.capping")) }
        verify(exactly = 0) { idle.sendMessage(Component.text("koth.capping")) }
        every { zone.contains(capper.location) } returns false
        service.tick()
        verify(exactly = 1) { capper.sendMessage(Component.text("koth.leave")) }
        verify(exactly = 0) { idle.sendMessage(Component.text("koth.leave")) }
    }
    @Test fun `the holder hears the lost note the moment they step off, even outside the message regions`() {
        val service = service(localAudience = true)
        assertTrue(service.startEvent(arena().copy(captureSeconds = 31), teamMode = TeamMode.GUILD))
        localPlayers.clear()
        service.tick(); service.tick()
        every { zone.contains(capper.location) } returns false
        service.tick(); service.tick()
        verify(exactly = 1) { capper.playSound(any<org.bukkit.Location>(), "minecraft:block.note_block.bass", org.bukkit.SoundCategory.PLAYERS, 0.35f, 0.8f) }
    }
    @Test fun `the player on the point is told above the hotbar that they are capturing, even outside the message regions`() {
        val service = service(localAudience = true)
        assertTrue(service.startEvent(arena().copy(captureSeconds = 31), teamMode = TeamMode.GUILD))
        localPlayers.clear()
        service.tick(); service.tick()
        verify(atLeast = 1) { capper.sendActionBar(Component.text("koth.actionbar.capturing")) }
        verify(exactly = 0) { capper.sendMessage(Component.text("koth.capping")) }
        verify(exactly = 0) { idle.sendActionBar(any<Component>()) }
    }
    @Test fun `moving out of notification regions suppresses subsequent capture messages`() {
        val service = service(localAudience = true)
        assertTrue(service.startEvent(arena().copy(captureSeconds = 31), teamMode = TeamMode.GUILD))
        service.tick()
        localPlayers.clear()
        service.tick()
        verify(exactly = 0) { capper.sendMessage(Component.text("koth.capping")) }
        verify(exactly = 0) { idle.sendMessage(Component.text("koth.capping")) }
    }
    @Test fun `one contributor command exception does not deny other eligible rewards`() {
        val service = service()
        assertTrue(service.startEvent(arena().copy(durationSeconds = 0), durationOverride = 0, teamMode = TeamMode.GUILD))
        val event = service.activeEvent!!
        val winning = TeamId(TeamMode.GUILD, guild)
        event.scores[winning] = 1.0
        event.scoringParticipation.record(winning, linkedSetOf(capper.uniqueId, idle.uniqueId))
        every { Bukkit.dispatchCommand(any(), "give Capper diamond 1") } throws IllegalStateException("recipient command failed")
        service.tick()
        assertNull(service.activeEvent)
        verify(exactly = 1) { Bukkit.dispatchCommand(any(), "give Capper diamond 1") }
        verify(exactly = 1) { Bukkit.dispatchCommand(any(), "give Idle diamond 1") }
    }
    @Test fun `guild switch cannot redeem contributions earned for old guild`() {
        val service = service()
        assertTrue(service.startEvent(arena().copy(durationSeconds = 0), durationOverride = 0, teamMode = TeamMode.GUILD))
        val event = service.activeEvent!!
        val winning = TeamId(TeamMode.GUILD, guild)
        event.scores[winning] = 1.0
        event.scoringParticipation.record(winning, setOf(capper.uniqueId))
        every { guilds.playerGuildId(capper) } returns UUID.randomUUID()
        service.tick()
        verify(exactly = 0) { Bukkit.dispatchCommand(any(), any()) }
    }
}
