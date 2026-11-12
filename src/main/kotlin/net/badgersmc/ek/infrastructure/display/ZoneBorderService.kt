package net.badgersmc.ek.infrastructure.display

import net.badgersmc.ek.domain.CaptureZone
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.Particle
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.scheduler.BukkitTask
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin

/**
 * Marks a static capture point with a faint ring of dust at its edge, redrawn once a second for players
 * within [VIEW_DISTANCE] blocks. Particles don't show through walls and leave nothing in the world.
 * Managed per event and cancelled when the KOTH ends.
 */
class ZoneBorderService(private val plugin: JavaPlugin) {

    companion object {
        /** Older versions drew the border with tagged display entities; any left behind are swept on show. */
        const val BORDER_TAG = "ekoth_zone_border"
        internal const val VIEW_DISTANCE = 24.0
        private const val SPACING = 1.5
        private val DUST = Particle.DustOptions(Color.fromRGB(255, 221, 150), 0.6f)

        /** Evenly spaced points about [SPACING] blocks apart: at least 12 so small points still read as a ring, at most 48. */
        internal fun ringPoints(centerX: Double, y: Double, centerZ: Double, radius: Double): List<Triple<Double, Double, Double>> {
            val r = radius.coerceAtLeast(0.5)
            val count = ceil(2 * PI * r / SPACING).toInt().coerceIn(12, 48)
            return (0 until count).map { index ->
                val angle = 2 * PI * index / count
                Triple(centerX + cos(angle) * r, y, centerZ + sin(angle) * r)
            }
        }
    }

    private val rings = mutableMapOf<String, BukkitTask>()

    /** Show the ring for a zone. Replaces any ring already shown for this event. */
    fun show(zone: CaptureZone, eventKey: String = "legacy") {
        hide(eventKey)
        val world = plugin.server.getWorld(zone.worldName) ?: return
        world.entities.filter { it.scoreboardTags.contains(BORDER_TAG) }.forEach { it.remove() }
        val centerX = (zone.minX + zone.maxX) / 2.0
        val centerZ = (zone.minZ + zone.maxZ) / 2.0
        val y = zone.objectiveY + 0.15
        val center = Location(world, centerX, y, centerZ)
        val points = ringPoints(centerX, y, centerZ, zone.radius).map { (x, py, z) -> Location(world, x, py, z) }
        val range = (VIEW_DISTANCE + zone.radius).let { it * it }
        rings[eventKey] = plugin.server.scheduler.runTaskTimer(plugin, Runnable {
            world.players.filter { it.location.distanceSquared(center) <= range }.forEach { player ->
                points.forEach { player.spawnParticle(Particle.DUST, it, 1, 0.0, 0.0, 0.0, 0.0, DUST) }
            }
        }, 0L, 20L)
    }

    /** Remove the ring. Safe to call multiple times. */
    fun hide(eventKey: String? = null) {
        if (eventKey == null) { rings.values.forEach { it.cancel() }; rings.clear() }
        else rings.remove(eventKey)?.cancel()
    }
}
