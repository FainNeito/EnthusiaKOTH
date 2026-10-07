package net.badgersmc.ek.domain

import org.bukkit.Location
import org.bukkit.World

/**
 * A cuboid capture zone for KOTH.
 */
data class CaptureZone(
    val id: String,
    val worldName: String,
    val corner1: Location,
    val corner2: Location,
    val radius: Double = 5.0, // used by MOVING family — radius around moving point
) {
    val minX: Double get() = minOf(corner1.x, corner2.x)
    val maxX: Double get() = maxOf(corner1.x, corner2.x)
    val minY: Double get() = minOf(corner1.y, corner2.y)
    val maxY: Double get() = maxOf(corner1.y, corner2.y)
    val minZ: Double get() = minOf(corner1.z, corner2.z)
    val maxZ: Double get() = maxOf(corner1.z, corner2.z)
    val objectiveY: Double get() = (minY + maxY) / 2.0
    val radiusSq: Double get() = radius * radius

    fun contains(loc: Location): Boolean {
        if (loc.world?.name != worldName) return false
        return loc.x in minX..maxX
                && loc.y in minOf(corner1.y, corner2.y)..maxOf(corner1.y, corner2.y)
                && loc.z in minZ..maxZ
    }

    /**
     * Circular horizontal objective check with the zone's configured vertical bounds.
     * Static capture KOTHs use this instead of the cuboid bounding box so radius is
     * a true radius rather than half the side length of a square.
     */
    fun containsCircular(loc: Location): Boolean {
        if (loc.world?.name != worldName || loc.y !in minY..maxY) return false
        val centerX = (minX + maxX) / 2.0
        val centerZ = (minZ + maxZ) / 2.0
        val dx = loc.x - centerX
        val dz = loc.z - centerZ
        return dx * dx + dz * dz <= radiusSq
    }

    fun verticalBounds(): Pair<Double, Double> =
        minOf(corner1.y, corner2.y) to maxOf(corner1.y, corner2.y)

    fun center(world: World): Location {
        val cx = (minX + maxX) / 2.0
        val cy = (minY + maxY) / 2.0
        val cz = (minZ + maxZ) / 2.0
        return Location(world, cx, cy, cz)
    }
}
