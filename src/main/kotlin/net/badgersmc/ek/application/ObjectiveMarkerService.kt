package net.badgersmc.ek.application

import net.badgersmc.ek.domain.CaptureZone
import net.badgersmc.ek.domain.KothEvent
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Particle
import org.bukkit.entity.Player

internal data class ObjectiveMarkerPoint(val x: Double, val y: Double, val z: Double)

/** A floor block under the moving point, by world coordinates. */
internal data class FloorCell(val x: Int, val y: Int, val z: Int)

class ObjectiveMarkerService {
    /** Per event and player, the floor blocks that player is currently being shown as the moving outline. */
    private val shownFloor = mutableMapOf<java.util.UUID, MutableMap<java.util.UUID, Set<FloorCell>>>()

    fun show(event: KothEvent, audience: Collection<Player>, showStaticObjective: Boolean, movingFloor: Boolean = true) {
        val moving = event.arena.family.equals("moving", ignoreCase = true)
        if (!moving && !showStaticObjective) return
        val world = Bukkit.getWorld(event.arena.zone.worldName) ?: return
        if (moving) {
            val point = event.movingPoint ?: return
            showMoving(event.id, world, point, event.arena.zone.radius, audience, movingFloor)
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
    private fun showMoving(
        eventId: java.util.UUID,
        world: org.bukkit.World,
        point: Triple<Double, Double, Double>,
        radius: Double,
        audience: Collection<Player>,
        movingFloor: Boolean,
    ) {
        val (x, y, z) = point
        val half = radius.coerceAtLeast(0.5)
        val viewers = audience.filter { it.isOnline && it.world.uid == world.uid }
        // The floor outline: the edge of the capture square, lit up in the floor under it. Only solid blocks are used,
        // so it never draws over gaps; with no floor under the point it falls back to the gold particle outline.
        val cells = if (movingFloor) movingFloorCells(x, y, z, half).filterTo(mutableSetOf()) { cell ->
            world.isChunkLoaded(cell.x shr 4, cell.z shr 4) && world.getBlockAt(cell.x, cell.y, cell.z).type.let { it.isSolid && it.isOccluding }
        } else emptySet()
        updateFloor(eventId, world, cells, viewers)
        val outline = if (cells.isEmpty()) movingSquarePoints(x, y + 0.1, z, half) else emptyList()
        val beam = (1..8).map { ObjectiveMarkerPoint(x, y + 0.5 + it * 0.75, z) }
        viewers.forEach { player ->
            outline.forEach { player.spawnParticle(Particle.DUST, Location(world, it.x, it.y, it.z), 1, 0.0, 0.0, 0.0, 0.0, GOLD_DUST) }
            beam.forEach { player.spawnParticle(Particle.END_ROD, Location(world, it.x, it.y, it.z), 1, 0.02, 0.05, 0.02, 0.0) }
        }
    }

    /**
     * Shows each viewer the floor outline as client-side block changes: nothing in the world is modified. Only blocks
     * that entered or left the outline since the last update are sent, and players who left the audience get the real
     * blocks back.
     */
    private fun updateFloor(eventId: java.util.UUID, world: org.bukkit.World, cells: Set<FloorCell>, viewers: List<Player>) {
        val shown = shownFloor.getOrPut(eventId) { mutableMapOf() }
        val current = viewers.associateBy { it.uniqueId }
        shown.keys.filter { it !in current }.forEach { id ->
            val previous = shown.remove(id).orEmpty()
            Bukkit.getPlayer(id)?.takeIf { it.isOnline && it.world.uid == world.uid }?.let { restore(it, world, previous) }
        }
        current.values.forEach { player ->
            val previous = shown[player.uniqueId].orEmpty()
            val added = cells - previous
            val removed = previous - cells
            if (removed.isNotEmpty()) restore(player, world, removed)
            if (added.isNotEmpty()) player.sendBlockChanges(added.map { cell ->
                world.getBlockAt(cell.x, cell.y, cell.z).state.also { it.type = FLOOR_MARKER }
            })
            shown[player.uniqueId] = cells
        }
    }

    private fun restore(player: Player, world: org.bukkit.World, cells: Collection<FloorCell>) {
        if (cells.isNotEmpty()) player.sendBlockChanges(cells.map { world.getBlockAt(it.x, it.y, it.z).state })
    }

    /** Puts the real floor back for everyone shown the moving outline of [eventId]. */
    fun clear(eventId: java.util.UUID, worldName: String) {
        val shown = shownFloor.remove(eventId) ?: return
        val world = Bukkit.getWorld(worldName) ?: return
        shown.forEach { (id, cells) -> Bukkit.getPlayer(id)?.takeIf { it.isOnline && it.world.uid == world.uid }?.let { restore(it, world, cells) } }
    }

    companion object {
        private val GOLD_DUST = Particle.DustOptions(org.bukkit.Color.fromRGB(255, 190, 40), 1.2f)
        /** Golden, like the arena's gold accents, and distinct from every block the arena is built from. */
        private val FLOOR_MARKER = org.bukkit.Material.OCHRE_FROGLIGHT

        /** The floor blocks along the edge of the moving capture square: the blocks whose centres lie inside it. */
        internal fun movingFloorCells(centerX: Double, y: Double, centerZ: Double, half: Double): Set<FloorCell> {
            val minX = kotlin.math.ceil(centerX - half - 0.5).toInt()
            val maxX = kotlin.math.floor(centerX + half - 0.5).toInt()
            val minZ = kotlin.math.ceil(centerZ - half - 0.5).toInt()
            val maxZ = kotlin.math.floor(centerZ + half - 0.5).toInt()
            val floorY = kotlin.math.floor(y).toInt() - 1
            if (minX > maxX || minZ > maxZ) return emptySet()
            return buildSet {
                for (x in minX..maxX) { add(FloorCell(x, floorY, minZ)); add(FloorCell(x, floorY, maxZ)) }
                for (z in minZ..maxZ) { add(FloorCell(minX, floorY, z)); add(FloorCell(maxX, floorY, z)) }
            }
        }

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
