package net.badgersmc.ek.infrastructure.bukkit

import io.mockk.*
import org.bukkit.entity.Player
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.inventory.Inventory
import org.bukkit.plugin.java.JavaPlugin
import org.junit.jupiter.api.*
import java.util.UUID

class ProgressionMenuInteractionTest {
    private val plugin=mockk<JavaPlugin>(relaxed=true)
    private val player=mockk<Player>(relaxed=true)
    private val id=UUID.randomUUID()
    private val action=mockk<()->Unit>(relaxed=true)
    private val holder=ProgressionMenu(id,true,mapOf(0 to action))
    private val top=mockk<Inventory>(relaxed=true)
    private val event=mockk<InventoryClickEvent>(relaxed=true)
    private val ui=ProgressionController(mockk(),mockk(),mockk(),mockk(),{error("not used")},{error("not used")},mockk(),mockk(),plugin)
    @BeforeEach fun setup() {
        every { plugin.isEnabled } returns true; every { player.isOnline } returns true
        every { player.uniqueId } returns id; every { player.hasPermission(any<String>()) } returns true
        every { top.holder } returns holder; every { event.view.topInventory } returns top
        every { event.clickedInventory } returns top; every { player.openInventory.topInventory } returns top
        every { event.whoClicked } returns player; every { event.rawSlot } returns 0; every { event.isLeftClick } returns true
    }
    @AfterEach fun cleanup()=unmockkAll()
    @Test fun `owner action executes next tick only while same menu remains open`() {
        val scheduled=slot<Runnable>(); every { plugin.server.scheduler.runTask(plugin,capture(scheduled)) } returns mockk(relaxed=true)
        ui.click(event); verify(exactly=0) { action() }; scheduled.captured.run(); verify(exactly=1) { action() }
    }
    @Test fun `revoked staff permission or changed menu prevents queued action`() {
        val scheduled=slot<Runnable>(); every { plugin.server.scheduler.runTask(plugin,capture(scheduled)) } returns mockk(relaxed=true)
        ui.click(event); every { player.hasPermission("enthusiakoth.admin") } returns false
        scheduled.captured.run(); verify(exactly=0) { action() }
        every { player.hasPermission("enthusiakoth.admin") } returns true
        ui.click(event); every { player.openInventory.topInventory } returns mockk(relaxed=true)
        scheduled.captured.run(); verify(exactly=0) { action() }
    }
    @Test fun `bottom inventory and another owner cannot schedule payout actions`() {
        every { event.clickedInventory } returns mockk(relaxed=true); ui.click(event)
        every { event.clickedInventory } returns top; every { player.uniqueId } returns UUID.randomUUID(); ui.click(event)
        verify(exactly=0) { plugin.server.scheduler.runTask(plugin,any<Runnable>()) }
        verify { event.isCancelled=true }
    }
}
