package net.badgersmc.ek.application

import net.badgersmc.ek.domain.CaptureZone
import net.badgersmc.ek.domain.KothArena
import net.badgersmc.ek.domain.KothEvent

object EventConcurrency {
    fun conflicts(arena: KothArena, events: Collection<KothEvent>, capacity: Int,
                  overlap: (KothArena, KothArena) -> Boolean = ::overlaps): Boolean =
        events.size >= capacity.coerceIn(1, 16) || events.any {
            it.isPrivateTest || it.arena.id == arena.id || overlap(arena, it.arena)
        }

    fun overlaps(a: KothArena, b: KothArena): Boolean {
        if (a.zone.worldName != b.zone.worldName) return false
        // Named regions can be polygons: fail closed until exact intersection is available.
        if (a.worldGuardRegion != null || b.worldGuardRegion != null) return true
        val az = listOfNotNull(a.zone, a.protectedRegion)
        val bz = listOfNotNull(b.zone, b.protectedRegion)
        return az.any { x -> bz.any { y -> intersects(x, y) } }
    }

    private fun intersects(a: CaptureZone, b: CaptureZone): Boolean =
        a.minX <= b.maxX && a.maxX >= b.minX && a.minY <= b.maxY &&
            a.maxY >= b.minY && a.minZ <= b.maxZ && a.maxZ >= b.minZ
}
