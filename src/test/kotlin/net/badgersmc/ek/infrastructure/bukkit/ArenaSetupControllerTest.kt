package net.badgersmc.ek.infrastructure.bukkit

import io.mockk.*
import net.badgersmc.ek.application.*
import net.badgersmc.nexus.i18n.LangService
import net.kyori.adventure.text.Component
import org.bukkit.NamespacedKey
import org.bukkit.entity.Player
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.player.PlayerDropItemEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.ItemMeta
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.java.JavaPlugin
import org.junit.jupiter.api.*
import java.util.UUID

class ArenaSetupControllerTest {
    private val plugin = mockk<JavaPlugin>(relaxed = true)
    private val player = mockk<Player>(relaxed = true)
    private val service = mockk<ArenaSetupService>(relaxed = true)
    private val lang = mockk<LangService>(relaxed = true)
    private val id = UUID.randomUUID()
    private val key = NamespacedKey("enthusiakoth", "arena-setup-wand")
    private lateinit var ui: ArenaSetupController
    @BeforeEach fun setup() {
        every { plugin.name } returns "EnthusiaKOTH"
        every { plugin.namespace() } returns "enthusiakoth"
        every { player.uniqueId } returns id
        every { player.hasPermission("enthusiakoth.admin") } returns false
        every { lang.msg(any(), *anyVararg()) } answers { Component.text(firstArg<String>()) }
        ui = ArenaSetupController(plugin, service, { error("unauthorized storage read") }, {}, lang)
    }
    @AfterEach fun cleanup() = unmockkAll()
    private fun tagged(): ItemStack {
        val item = mockk<ItemStack>(); val meta = mockk<ItemMeta>(relaxed = true)
        every { item.itemMeta } returns meta
        every { meta.persistentDataContainer.get(key, PersistentDataType.STRING) } returns id.toString()
        every { meta.persistentDataContainer.has(key, PersistentDataType.STRING) } returns true
        return item
    }
    @Test fun `nonstaff cannot open create or receive wand`() {
        ui.open(player); ui.create(player, "hill"); ui.giveWand(player)
        verify(exactly = 0) { service.begin(any(), any(), any(), any()) }
        verify(exactly = 0) { player.openInventory(any<Inventory>()) }
    }
    @Test fun `all inventory clicks including bottom shift clicks cancelled`() {
        val event = mockk<InventoryClickEvent>(relaxed = true)
        every { event.view.topInventory.holder } returns ArenaSetupHolder(id, null)
        every { event.whoClicked } returns player
        ui.click(event)
        verify { event.isCancelled = true }; verify(exactly = 0) { plugin.server.scheduler.runTask(plugin, any<Runnable>()) }
    }
    @Test fun `menu ownership checked before scheduling action`() {
        every { player.hasPermission("enthusiakoth.admin") } returns true
        val event = mockk<InventoryClickEvent>(relaxed = true)
        every { event.view.topInventory.holder } returns ArenaSetupHolder(UUID.randomUUID(), null)
        every { event.whoClicked } returns player
        ui.click(event); verify(exactly = 0) { plugin.server.scheduler.runTask(plugin, any<Runnable>()) }
    }
    @Test fun `permission revoked between click and next tick prevents actions`() {
        var permitted = true
        every { player.hasPermission("enthusiakoth.admin") } answers { permitted }
        every { player.isOnline } returns true
        val event = mockk<InventoryClickEvent>(relaxed = true)
        val top = mockk<Inventory>(relaxed = true); val holder = ArenaSetupHolder(id, null, listOf("hill"))
        every { top.holder } returns holder
        every { event.view.topInventory } returns top
        every { player.openInventory.topInventory } returns top
        every { event.clickedInventory } returns top
        every { event.whoClicked } returns player
        every { event.rawSlot } returns 0
        val task = slot<Runnable>()
        every { plugin.server.scheduler.runTask(plugin, capture(task)) } returns mockk(relaxed = true)
        ui.click(event); permitted = false; task.captured.run()
        verify(exactly = 0) { service.begin(any(), any(), any(), any()) }
    }
    @Test fun `dragging into editor cannot move items`() {
        val event = mockk<InventoryDragEvent>(relaxed = true)
        every { event.view.topInventory.holder } returns ArenaSetupHolder(id, null)
        ui.drag(event); verify { event.isCancelled = true }
    }
    @Test fun `tagged tool cannot interact after losing permission`() {
        val event = mockk<PlayerInteractEvent>(relaxed = true)
        every { event.item } returns tagged(); every { event.player } returns player
        ui.interact(event); verify { event.isCancelled = true }
        verify(exactly = 0) { service.selectBoundary(any(), any(), any(), any()) }
    }
    @Test fun `ordinary tool is untouched`() {
        val event = mockk<PlayerInteractEvent>(relaxed = true)
        val item = mockk<ItemStack>(); every { item.itemMeta } returns null; every { event.item } returns item
        ui.interact(event); verify(exactly = 0) { event.isCancelled = any() }
    }
    @Test fun `tagged setup tool cannot break blocks or be dropped`() {
        every { player.inventory.itemInMainHand } returns tagged()
        val breaking = mockk<BlockBreakEvent>(relaxed = true); every { breaking.player } returns player
        ui.breakBlock(breaking); verify { breaking.isCancelled = true }
        val dropping = mockk<PlayerDropItemEvent>(relaxed = true); every { dropping.itemDrop.itemStack } returns tagged()
        ui.drop(dropping); verify { dropping.isCancelled = true }
    }
}
