package net.badgersmc.ek.infrastructure.display

import net.badgersmc.ek.domain.CaptureZone
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.Particle
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.scheduler.BukkitTask
import kotlin.math.ceil
import kotlin.math.hypot

/**
 * Outlines a static capture point with a square of gold dust along its edges, redrawn twice a second for
 * players within [VIEW_DISTANCE] blocks. Particles don't show through walls and leave nothing in the world.
 * Managed per event and cancelled when the KOTH ends.
 */
class ZoneBorderService(private val plugin: JavaPlugin) {

    companion object {
        /** Older versions drew the border with tagged display entities; any left behind are swept on show. */
        const val BORDER_TAG = "ekoth_zone_border"
        internal const val VIEW_DISTANCE = 32.0
        internal const val MAX_POINTS = 160
        private const val SPACING = 0.5
        private val DUST = Particle.DustOptions(Color.fromRGB(255, 190, 40), 1.2f)

        /** Points along the square's four edges, every [SPACING] blocks, spread wider on large zones to stay under [MAX_POINTS]. */
        internal fun squarePoints(minX: Double, minZ: Double, maxX: Double, maxZ: Double, y: Double): List<Triple<Double, Double, Double>> {
            val corners = listOf(minX to minZ, maxX to minZ, maxX to maxZ, minX to maxZ)
            val perimeter = 2 * ((maxX - minX) + (maxZ - minZ))
            val step = maxOf(SPACING, perimeter / MAX_POINTS)
            return corners.indices.flatMap { edge ->
                val (x1, z1) = corners[edge]
                val (x2, z2) = corners[(edge + 1) % corners.size]
                val segments = ceil(hypot(x2 - x1, z2 - z1) / step).toInt().coerceAtLeast(1)
                // Each edge stops one step short of its end corner, which starts the next edge.
                (0 until segments).map { i -> Triple(x1 + (x2 - x1) * i / segments, y, z1 + (z2 - z1) * i / segments) }
            }
        }
    }

    private val outlines = mutableMapOf<String, BukkitTask>()

    /** Show the outline for a zone. Replaces any outline already shown for this event. */
    fun show(zone: CaptureZone, eventKey: String = "legacy") {
        hide(eventKey)
        val world = plugin.server.getWorld(zone.worldName) ?: return
        world.entities.filter { it.scoreboardTags.contains(BORDER_TAG) }.forEach { it.remove() }
        val y = zone.objectiveY + 0.1
        val center = Location(world, (zone.minX + zone.maxX) / 2.0, y, (zone.minZ + zone.maxZ) / 2.0)
        val points = squarePoints(zone.minX, zone.minZ, zone.maxX, zone.maxZ, y).map { (x, py, z) -> Location(world, x, py, z) }
        val range = (VIEW_DISTANCE + hypot(zone.maxX - zone.minX, zone.maxZ - zone.minZ) / 2).let { it * it }
        outlines[eventKey] = plugin.server.scheduler.runTaskTimer(plugin, Runnable {
            world.players.filter { it.location.distanceSquared(center) <= range }.forEach { player ->
                points.forEach { player.spawnParticle(Particle.DUST, it, 1, 0.0, 0.0, 0.0, 0.0, DUST) }
            }
        }, 0L, 10L)
    }

    /** Remove the outline. Safe to call multiple times. */
    fun hide(eventKey: String? = null) {
        if (eventKey == null) { outlines.values.forEach { it.cancel() }; outlines.clear() }
        else outlines.remove(eventKey)?.cancel()
    }
}
