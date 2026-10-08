package net.badgersmc.ek.application

import io.mockk.*
import net.badgersmc.nexus.i18n.LangService
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.player.PlayerJoinEvent
import org.junit.jupiter.api.*
import java.util.UUID

class DisplayAudienceTest {
    private val player = mockk<Player>(relaxed = true)
    private val lang = mockk<LangService>(relaxed = true)
    @BeforeEach fun setup() {
        mockkStatic(Bukkit::class)
        every { player.uniqueId } returns UUID.randomUUID()
        every { Bukkit.getOnlinePlayers() } returns mutableListOf(player)
        every { lang.msg(any(), *anyVararg()) } returns Component.empty()
    }
    @AfterEach fun cleanup() = unmockkAll()
    @Test fun `public rejoin reevaluates region eligibility instead of stale viewer membership`() {
        val display = DisplayService(mockk(relaxed = true), lang) { false }
        display.showKoth("capture", null, "60s", false, 0f, listOf(player), true)
        clearMocks(player, answers = false)
        display.onJoin(PlayerJoinEvent(player, Component.empty()))
        verify(exactly = 0) { player.showBossBar(any()) }
    }
    @Test fun `private participant rejoin preserves private audience despite public opt out`() {
        val display = DisplayService(mockk(relaxed = true), lang) { false }
        display.showKoth("capture", null, "60s", false, 0f, listOf(player), false)
        clearMocks(player, answers = false)
        display.onJoin(PlayerJoinEvent(player, Component.empty()))
        verify(exactly = 1) { player.showBossBar(any()) }
    }
}
