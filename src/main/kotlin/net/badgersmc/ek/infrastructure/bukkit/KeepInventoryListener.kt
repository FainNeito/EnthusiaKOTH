package net.badgersmc.ek.infrastructure.bukkit

import net.badgersmc.ek.domain.EventState
import net.badgersmc.ek.domain.KothArena
import net.badgersmc.ek.domain.KothEvent
import org.bukkit.Location
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.PlayerDeathEvent

class KeepInventoryListener(
    private val activeEvent: () -> KothEvent?,
    private val contains: (KothArena, Location) -> Boolean,
) : Listener {
    @EventHandler(priority = EventPriority.HIGHEST)
    fun onDeath(event: PlayerDeathEvent) {
        val active = activeEvent() ?: return
        if (active.state != EventState.ACTIVE || !active.arena.keepInventory ||
            !active.isParticipant(event.entity.uniqueId) || !contains(active.arena, event.entity.location)) return
        event.keepInventory = true
        event.drops.clear()
        if (active.arena.keepExperience) {
            event.keepLevel = true
            event.droppedExp = 0
        }
    }
}
