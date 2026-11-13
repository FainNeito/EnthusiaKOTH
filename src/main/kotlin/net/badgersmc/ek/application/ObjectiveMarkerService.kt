package net.badgersmc.ek.application

import net.badgersmc.ek.domain.CaptureZone
import net.badgersmc.ek.domain.KothEvent
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Particle
import org.bukkit.entity.Player

internal data class ObjectiveMarkerPoint(val x: Double, val y: Double, val z: Double)

class ObjectiveMarkerService {
    fun show(event: KothEvent, audience: Collection<Player>, showStaticObjective: Boolean) {
        val moving = event.arena.family.equals("moving", ignoreCase = true)
        if (!moving && !showStaticObjective) return
        val world = Bukkit.getWorld(event.arena.zone.worldName) ?: return
        if (moving) {
            val point = event.movingPoint ?: return
            showMoving(world, point, event.arena.zone.radius, audience)
            return
        }
        val points = staticZonePoints(event.arena.zone)
        audience.asSequence()
            .filter { it.isOnline && it.world.uid == world.uid }
            .forEach { player ->
                points.forEach { point ->
                    player.spawnParticle(
                        Particle.END_ROD,
                        Location(world, point.x, point.y, point.z),
                        2,
                        0.08,
                        0.18,
                        0.08,
                        0.0,
                    )
                }
            }
    }

    /**
     * The moving point as its capture area really is: the same gold square outline static arenas use, following the
     * point, plus a short beam over its centre so it can be found from across the arena. Drawn at the point's own height
     * (the arena's floor), not the highest block, so roofs and floating arenas don't lift it out of reach.
     */
    private fun showMoving(world: org.bukkit.World, point: Triple<Double, Double, Double>, radius: Double, audience: Collection<Player>) {
        val (x, y, z) = point
        val half = radius.coerceAtLeast(0.5)
        val outline = movingSquarePoints(x, y + 0.1, z, half)
        val beam = (1..8).map { ObjectiveMarkerPoint(x, y + 0.5 + it * 0.75, z) }
        audience.asSequence()
            .filter { it.isOnline && it.world.uid == world.uid }
            .forEach { player ->
                outline.forEach { player.spawnParticle(Particle.DUST, Location(world, it.x, it.y, it.z), 1, 0.0, 0.0, 0.0, 0.0, GOLD_DUST) }
                beam.forEach { player.spawnParticle(Particle.END_ROD, Location(world, it.x, it.y, it.z), 1, 0.02, 0.05, 0.02, 0.0) }
            }
    }

    companion object {
        private val GOLD_DUST = Particle.DustOptions(org.bukkit.Color.fromRGB(255, 190, 40), 1.2f)

        /** The moving capture square's outline, every half block along its edges. */
        internal fun movingSquarePoints(centerX: Double, y: Double, centerZ: Double, half: Double): List<ObjectiveMarkerPoint> =
            net.badgersmc.ek.infrastructure.display.ZoneBorderService
                .squarePoints(centerX - half, centerZ - half, centerX + half, centerZ + half, y)
                .map { (px, py, pz) -> ObjectiveMarkerPoint(px, py, pz) }

        internal fun staticZonePoints(zone: CaptureZone): List<ObjectiveMarkerPoint> {
            val y = zone.objectiveY + 0.35
            val centerX = (zone.minX + zone.maxX) / 2.0
            val centerZ = (zone.minZ + zone.maxZ) / 2.0
            return listOf(
                ObjectiveMarkerPoint(zone.minX, y, zone.minZ),
                ObjectiveMarkerPoint(zone.minX, y, zone.maxZ),
                ObjectiveMarkerPoint(zone.maxX, y, zone.minZ),
                ObjectiveMarkerPoint(zone.maxX, y, zone.maxZ),
                ObjectiveMarkerPoint(centerX, y, centerZ),
            )
        }
    }
}
