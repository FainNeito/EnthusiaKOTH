package net.badgersmc.ek.infrastructure.bukkit

import io.mockk.*
import net.badgersmc.ek.application.*
import net.badgersmc.ek.config.EnthusiaKothConfig
import net.badgersmc.nexus.i18n.LangService
import net.kyori.adventure.text.Component
import org.bukkit.entity.Player
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.plugin.java.JavaPlugin
import org.junit.jupiter.api.*
import java.util.UUID

class StaffSettingsControllerTest {
    private val plugin = mockk<JavaPlugin>(relaxed = true)
    private val player = mockk<Player>(relaxed = true)
    private val service = mockk<StaffSettingsService>(relaxed = true)
    private val lang = mockk<LangService>(relaxed = true)
    private val id = UUID.randomUUID()
    private lateinit var ui: StaffSettingsController
    @BeforeEach fun setup() {
        every { player.uniqueId } returns id
        every { player.hasPermission("enthusiakoth.admin") } returns false
        every { lang.msg(any(), *anyVararg()) } answers { Component.text(firstArg<String>()) }
        ui = StaffSettingsController(plugin, service, { EnthusiaKothConfig() }, { emptyMap() }, mockk(relaxed = true), {}, lang)
    }
    @AfterEach fun cleanup() = unmockkAll()
    @Test fun `nonstaff cannot read a settings draft`() { ui.open(player); verify(exactly = 0) { service.begin(any()) } }
    @Test fun `bottom shift clicks and drags cannot extract menu items`() {
        val holder = StaffSettingsHolder(id, StaffSettingsDraft("v", null, mutableMapOf()), StaffSettingsPage.HOME)
        val click = mockk<InventoryClickEvent>(relaxed = true); every { click.view.topInventory.holder } returns holder; every { click.whoClicked } returns player
        ui.click(click); verify { click.isCancelled = true }; verify(exactly = 0) { plugin.server.scheduler.runTask(plugin, any<Runnable>()) }
        val drag = mockk<InventoryDragEvent>(relaxed = true); every { drag.view.topInventory.holder } returns holder
        ui.drag(drag); verify { drag.isCancelled = true }
    }
    @Test fun `foreign owner cannot schedule Save even with admin permission`() {
        every { player.hasPermission("enthusiakoth.admin") } returns true
        val click = mockk<InventoryClickEvent>(relaxed = true)
        every { click.view.topInventory.holder } returns StaffSettingsHolder(UUID.randomUUID(), StaffSettingsDraft("v", null, mutableMapOf()), StaffSettingsPage.HOME)
        every { click.whoClicked } returns player
        ui.click(click); verify(exactly = 0) { plugin.server.scheduler.runTask(plugin, any<Runnable>()) }
    }
}
