package net.badgersmc.ek.infrastructure.bukkit

import io.mockk.*
import net.badgersmc.ek.application.KothService
import net.badgersmc.ek.domain.*
import net.badgersmc.ek.infrastructure.restriction.*
import org.bukkit.*
import org.bukkit.entity.Player
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.player.PlayerTeleportEvent
import org.junit.jupiter.api.*
import java.time.Instant
import java.util.UUID

class ConcurrentListenerTest {
    private val world = mockk<World>().also { every { it.name } returns "world" }
    private val player = mockk<Player>(relaxed = true)
    private fun event(id: String, x: Double, keep: Boolean) = KothEvent(UUID.randomUUID(), KothArena(id, "capture",
        CaptureZone(id, "world", Location(world, x, 0.0, 0.0), Location(world, x + 10, 100.0, 10.0)),
        durationSeconds = 60, captureSeconds = 10, keepInventory = keep), Instant.EPOCH, Instant.EPOCH.plusSeconds(60), state = EventState.ACTIVE)
    @AfterEach fun cleanup() = unmockkAll()
    @Test fun `death in second active arena uses its inventory policy`() {
        val first = event("a", 0.0, false); val second = event("b", 50.0, true)
        every { player.location } returns Location(world, 55.0, 80.0, 5.0)
        val death = mockk<PlayerDeathEvent>(relaxed = true); every { death.entity } returns player
        KeepInventoryListener({ first }, { listOf(first, second) }) { arena, location -> arena.zone.contains(location) }.onDeath(death)
        verify { death.keepInventory = true; death.keepLevel = true; death.droppedExp = 0 }
    }
    @Test fun `pearl entering second arena evaluates destination rules`() {
        val first = event("a", 0.0, false); val second = event("b", 50.0, false)
        every { player.location } returns Location(world, 30.0, 80.0, 5.0)
        val koth = mockk<KothService>(); every { koth.allEvents() } returns listOf(first, second)
        val restrictions = RestrictionService({ if (it == "b") RuleSet(enderPearlAllowed = false) else RuleSet.PERMISSIVE })
        val teleport = mockk<PlayerTeleportEvent>(relaxed = true)
        every { teleport.player } returns player; every { teleport.to } returns Location(world, 55.0, 80.0, 5.0)
        every { teleport.cause } returns PlayerTeleportEvent.TeleportCause.ENDER_PEARL
        RestrictionListener(koth, restrictions).onPearlTeleport(teleport)
        verify { teleport.isCancelled = true }
    }
}
