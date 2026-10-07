package net.badgersmc.ek.infrastructure.protection

import net.badgersmc.ek.domain.KothArena
import org.bukkit.Location

/**
 * Answers whether a location is permanently protected by an enabled KOTH arena.
 */
class RegionProtectionService(
    private val arenas: () -> Map<String, KothArena>,
    private val worldGuardContains: (String, String, Location) -> Boolean = { _, _, _ -> false },
) {
    /**
     * Returns true if the given location falls within any arena's protected region.
     * An arena protects BOTH its capture zone and its (typically larger)
     * configured protected-region boundary — the capture zone alone was letting
     * players build/grief just outside the cap point.
     */
    fun isProtected(location: Location): Boolean {
        return arenas().values.any { arena ->
            val worldGuardRegion = arena.worldGuardRegion
            if (worldGuardRegion != null) {
                worldGuardContains(arena.zone.worldName, worldGuardRegion, location)
            } else {
                arena.zone.contains(location) || arena.protectedRegion?.contains(location) == true
            }
        }
    }
}