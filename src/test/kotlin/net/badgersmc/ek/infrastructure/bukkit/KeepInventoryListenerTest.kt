package net.badgersmc.ek.infrastructure.bukkit

import io.mockk.*
import net.badgersmc.ek.domain.*
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.inventory.ItemStack
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class KeepInventoryListenerTest {
    private fun event(state: EventState = EventState.ACTIVE, keep: Boolean = true, xp: Boolean = true) = KothEvent(
        UUID.randomUUID(), KothArena("safe", "capture", mockk(relaxed = true), durationSeconds = 60,
            captureSeconds = 10, keepInventory = keep, keepExperience = xp),
        Instant.now(), Instant.now().plusSeconds(60), state = state,
    )
    @Test fun `active arena preserves inventory and XP without drops`() {
        val death = mockk<PlayerDeathEvent>(relaxed = true)
        val drops = mutableListOf(mockk<ItemStack>())
        every { death.drops } returns drops
        KeepInventoryListener({ event() }) { _, _ -> true }.onDeath(death)
        verify { death.keepInventory = true; death.keepLevel = true; death.droppedExp = 0 }
        assertTrue(drops.isEmpty())
    }
    @Test fun `outside inactive disabled and unjoined private deaths are unchanged`() {
        val private = event().copy(owner = UUID.randomUUID(), isPrivateTest = true)
        listOf(event(state = EventState.STARTING), event(state = EventState.COMPLETED), event(keep = false), private, null).forEach { active ->
            val death = mockk<PlayerDeathEvent>(relaxed = true)
            KeepInventoryListener({ active }) { _, _ -> true }.onDeath(death)
            verify(exactly = 0) { death.keepInventory = any(); death.keepLevel = any(); death.droppedExp = any() }
        }
        val death = mockk<PlayerDeathEvent>(relaxed = true)
        KeepInventoryListener({ event() }) { _, _ -> false }.onDeath(death)
        verify(exactly = 0) { death.keepInventory = any() }
    }
    @Test fun `optional XP loss leaves XP policy unchanged`() {
        val death = mockk<PlayerDeathEvent>(relaxed = true)
        KeepInventoryListener({ event(xp = false) }) { _, _ -> true }.onDeath(death)
        verify { death.keepInventory = true }
        verify(exactly = 0) { death.keepLevel = any(); death.droppedExp = any() }
    }
}
