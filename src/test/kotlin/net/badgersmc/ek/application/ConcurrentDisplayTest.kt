package net.badgersmc.ek.application

import io.mockk.*
import net.badgersmc.ek.config.DisplayConfig
import net.badgersmc.nexus.i18n.LangService
import net.kyori.adventure.bossbar.BossBar
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.scoreboard.DisplaySlot
import org.bukkit.scoreboard.Scoreboard
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import java.util.UUID

class ConcurrentDisplayTest {
    private val player = mockk<Player>(relaxed = true)
    private val lang = mockk<LangService>(relaxed = true)
    private lateinit var display: DisplayService
    @BeforeEach fun setup() {
        mockkStatic(Bukkit::class)
        every { player.uniqueId } returns UUID.randomUUID(); every { Bukkit.getOnlinePlayers() } returns listOf(player)
        every { lang.msg(any(), *anyVararg()) } returns Component.empty()
        display = DisplayService(mockk<JavaPlugin>(relaxed = true), lang)
    }
    @AfterEach fun cleanup() = unmockkAll()
    @Test fun `matching display names remain independently owned and renaming reuses the same bar`() {
        val shown = mutableListOf<BossBar>(); every { player.showBossBar(capture(shown)) } just Runs
        val format = DisplayConfig(bossbarTitle = "<koth_name> <time>")
        display.showKoth("a", null, "10s", false, .5f, listOf(player), true, format, displayName = "Summit")
        display.showKoth("b", null, "10s", false, .5f, listOf(player), true, format, displayName = "Summit")
        assertEquals(2, shown.size); assertNotSame(shown[0], shown[1])
        display.showKoth("a", null, "9s", false, .6f, listOf(player), true, format, displayName = "Crimson Summit")
        assertEquals("Crimson Summit 9s", net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(shown[0].name()))
        display.clear("a")
        verify(exactly = 1) { player.hideBossBar(shown[0]) }
        verify(exactly = 0) { player.hideBossBar(shown[1]) }
    }
    @Test fun `clearing one arena removes only its own bar`() {
        val shown = mutableListOf<BossBar>(); every { player.showBossBar(capture(shown)) } just Runs
        display.showKoth("a", null, "10s", false, .5f, listOf(player), true)
        display.showKoth("b", null, "10s", false, .5f, listOf(player), true)
        assertEquals(2, shown.size); assertNotSame(shown[0], shown[1])
        display.clear("b"); verify(exactly = 1) { player.hideBossBar(shown[1]) }; verify(exactly = 0) { player.hideBossBar(shown[0]) }
    }
    @Test fun `optional sidebar yields to an existing sidebar owner`() {
        val board = mockk<Scoreboard>(relaxed = true); every { player.scoreboard } returns board
        every { board.getObjective(DisplaySlot.SIDEBAR) } returns mockk(relaxed = true)
        display.showKoth("a", null, "10s", false, .5f, listOf(player), true, DisplayConfig(scoreboard = true))
        verify(exactly = 0) { player.scoreboard = any() }
    }
    @Test fun `bossbar disable removes existing bar without replacing another arena`() {
        val shown = mutableListOf<BossBar>(); every { player.showBossBar(capture(shown)) } just Runs
        display.showKoth("a", null, "10s", false, .5f, listOf(player), true)
        display.showKoth("a", null, "10s", false, .5f, listOf(player), true, DisplayConfig(bossbar = false))
        assertEquals(1, shown.size); verify { player.hideBossBar(shown.single()) }
    }
    @Test fun `sidebar cleanup restores prior board only while still owned`() {
        val original = mockk<Scoreboard>(relaxed = true); val owned = mockk<Scoreboard>(relaxed = true)
        val other = mockk<Scoreboard>(relaxed = true)
        var current = original
        every { original.getObjective(DisplaySlot.SIDEBAR) } returns null
        every { player.scoreboard } answers { current }; every { player.scoreboard = any() } answers { current = firstArg() }
        every { Bukkit.getScoreboardManager() } returns mockk(relaxed = true) { every { newScoreboard } returns owned }
        every { Bukkit.getPlayer(player.uniqueId) } returns player
        display.showKoth("a", null, "10s", false, .5f, listOf(player), true, DisplayConfig(scoreboard = true))
        assertSame(owned, current); display.clear("a"); assertSame(original, current)
        display.showKoth("a", null, "10s", false, .5f, listOf(player), true, DisplayConfig(scoreboard = true))
        current = other; display.clear("a"); assertSame(other, current)
    }

}
