package net.badgersmc.ek.infrastructure.bukkit

import io.mockk.*
import net.badgersmc.ek.application.ProgressionClaim
import net.badgersmc.ek.infrastructure.persistence.SqlProgressionStore
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemStack
import org.bukkit.Material
import org.bukkit.plugin.java.JavaPlugin
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.util.UUID

class ProgressionPackagePagingTest {
 @Test fun packageOptionsRemainReachableBeyondFirstPageWithoutGrantingDuringNavigation() {
  mockkStatic(Bukkit::class)
  try {
   val player=mockk<Player>(relaxed=true);val id=UUID.randomUUID();every { player.uniqueId } returns id
   val plugin=mockk<JavaPlugin>(relaxed=true)
   val store=mockk<SqlProgressionStore>(relaxed=true)
   every { store.options("claim",id) } returns (0..22).map { "package$it" }
   val plugins=mockk<org.bukkit.plugin.PluginManager>(relaxed=true)
   every { Bukkit.getPluginManager() } returns plugins
   every { plugins.getPlugin("EnthusiaTags") } returns null
   val captured=slot<InventoryHolder>()
   every { Bukkit.createInventory(capture(captured),54,any<Component>()) } returns mockk<Inventory>(relaxed=true)
   val ui=spyk(ProgressionController(store,mockk(),mockk(),mockk(),{error("unused")},{error("unused")},mockk(),mockk(),plugin),recordPrivateCalls=true)
   every { ui["item"](any<Material>(),any<String>(),any<List<String>>()) } returns mockk<ItemStack>(relaxed=true)
   val claim=ProgressionClaim("claim",id,"CHOICE","",0,"PENDING","")
   val choose=ProgressionController::class.java.getDeclaredMethod("choose",Player::class.java,ProgressionClaim::class.java,Int::class.javaPrimitiveType)
   choose.isAccessible=true;choose.invoke(ui,player,claim,0)
   val first=captured.captured as ProgressionMenu
   assertNotNull(first.actions[51]);first.actions.getValue(51).invoke()
   val second=captured.captured as ProgressionMenu
   assertNotSame(first,second);assertNotNull(second.actions[47]);assertNotNull(second.actions[19]);assertNotNull(second.actions[20]);assertNull(second.actions[21])
   verify(exactly=0) { store.choose(any(),any(),any()) }
  } finally { unmockkAll() }
 }
}
