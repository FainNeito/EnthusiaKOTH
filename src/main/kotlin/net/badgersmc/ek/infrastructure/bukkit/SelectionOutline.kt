package net.badgersmc.ek.infrastructure.bukkit

import net.badgersmc.ek.config.PositionConfig

/** Twelve cuboid edges, bounded independently of selection size. Inputs are exclusive upper bounds. */
object SelectionOutline {
    fun points(first: PositionConfig, second: PositionConfig): List<PositionConfig> {
        if (listOf(first.x, first.y, first.z, second.x, second.y, second.z).any { !it.isFinite() }) return emptyList()
        val low = PositionConfig(minOf(first.x, second.x), minOf(first.y, second.y), minOf(first.z, second.z))
        val high = PositionConfig(maxOf(first.x, second.x), maxOf(first.y, second.y), maxOf(first.z, second.z))
        val corners = (0..7).map { n -> PositionConfig(
            if (n and 1 == 0) low.x else high.x,
            if (n and 2 == 0) low.y else high.y,
            if (n and 4 == 0) low.z else high.z) }
        return buildSet {
            for (axis in listOf(1, 2, 4)) for (n in 0..7) if (n and axis == 0) {
                val from = corners[n]; val to = corners[n or axis]
                val length = maxOf(to.x - from.x, to.y - from.y, to.z - from.z)
                val segments = kotlin.math.ceil(length).coerceIn(1.0, 16.0).toInt()
                for (step in 0..segments) {
                    if (step == 0) { add(from); continue }
                    if (step == segments) { add(to); continue }
                    val t = step.toDouble() / segments
                    add(PositionConfig(from.x + (to.x - from.x) * t,
                        from.y + (to.y - from.y) * t, from.z + (to.z - from.z) * t))
                }
            }
        }.toList()
    }

    fun selectedBlocks(first: PositionConfig, second: PositionConfig) = points(
        PositionConfig(minOf(first.x, second.x), minOf(first.y, second.y), minOf(first.z, second.z)),
        PositionConfig(maxOf(first.x, second.x) + 1, maxOf(first.y, second.y) + 1, maxOf(first.z, second.z) + 1))
}
