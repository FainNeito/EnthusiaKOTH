package net.badgersmc.ek.infrastructure.protection

import com.sk89q.worldedit.bukkit.BukkitAdapter
import com.sk89q.worldedit.math.BlockVector3
import com.sk89q.worldguard.WorldGuard
import org.bukkit.Bukkit
import org.bukkit.Location

/**
 * Thin adapter around WorldGuard region lookup so arena configuration can bind
 * to a named WorldGuard region without duplicating its geometry.
 */
class WorldGuardRegionService {
    fun exists(worldName: String, regionId: String): Boolean =
        region(worldName, regionId) != null

    fun contains(worldName: String, regionId: String, location: Location): Boolean {
        if (location.world?.name != worldName) return false
        val region = region(worldName, regionId) ?: return false
        return region.contains(BlockVector3.at(location.blockX, location.blockY, location.blockZ))
    }

    fun regionIds(worldName: String): List<String> =
        manager(worldName)?.regions?.keys?.sorted() ?: emptyList()

    private fun region(worldName: String, regionId: String) =
        manager(worldName)?.getRegion(regionId)

    private fun manager(worldName: String) =
        Bukkit.getWorld(worldName)?.let { world ->
            WorldGuard.getInstance().platform.regionContainer.get(BukkitAdapter.adapt(world))
        }
}
