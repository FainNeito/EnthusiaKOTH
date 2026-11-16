package net.badgersmc.ek.application

import java.time.LocalDate
import kotlin.random.Random

/** How an arena picks which of its layouts to build. */
enum class LayoutRotation {
    /** The layout staff chose with /ekoth layout use. */
    FIXED,
    /** A different layout each week, starting Monday, going through them in name order. */
    WEEKLY,
    /** A layout picked at random each time a KOTH starts, never the same one twice running when there is a choice. */
    RANDOM;

    val id: String get() = name.lowercase()

    companion object {
        fun parse(value: String?): LayoutRotation? = entries.firstOrNull { it.id.equals(value?.trim(), true) }
    }
}

/** A block a schematic places, relative to the spot the builder stood on when they copied it. */
data class PlacedBlock(val dx: Int, val dy: Int, val dz: Int, val state: String)

object ArenaLayouts {
    const val DEFAULT = "default"
    private val NAME = Regex("^[a-z0-9_-]{1,32}$")

    /** Layout names are lower case letters, digits, dashes and underscores, up to 32 characters. */
    fun validName(name: String): Boolean = NAME.matches(name)

    /** Weeks counted from a Monday, so every arena on weekly rotation changes layout at the same moment. */
    fun weekIndex(date: LocalDate): Long = Math.floorDiv(date.toEpochDay() + 3, 7)

    fun weekly(names: List<String>, date: LocalDate): String? {
        val sorted = names.sorted()
        if (sorted.isEmpty()) return null
        return sorted[Math.floorMod(weekIndex(date), sorted.size.toLong()).toInt()]
    }

    fun random(names: List<String>, previous: String?, random: Random = Random.Default): String? {
        val choices = names.filter { it != previous }.ifEmpty { names }
        return if (choices.isEmpty()) null else choices[random.nextInt(choices.size)]
    }

    /**
     * Lays a schematic over the arena's base form, with the builder's standing spot on [anchor], and keeps only the
     * blocks that differ from the base. Air in the schematic clears what the base has there, as a WorldEdit paste does.
     * Returns the build, or null with how many blocks would land outside the recorded base form.
     */
    fun overlay(base: BaseSnapshot, arenaId: String, anchor: Triple<Int, Int, Int>, placed: List<PlacedBlock>): Pair<ArenaBuild?, Int> {
        val (ax, ay, az) = anchor
        val outside = placed.count { !base.contains(ax + it.dx, ay + it.dy, az + it.dz) }
        if (outside > 0) return null to outside
        val changed = placed.mapNotNull { block ->
            val x = ax + block.dx; val y = ay + block.dy; val z = az + block.dz
            val was = base.at(x, y, z)
            if (was == block.state) null else BuildBlock(x, y, z, was, block.state)
        }.sortedWith(compareBy<BuildBlock> { it.y }.thenBy { it.z }.thenBy { it.x })
        return ArenaBuild(arenaId, base.world, changed) to 0
    }
}
