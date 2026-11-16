package net.badgersmc.ek.application

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/** A guild's banner: its base dye colour and its layers, both by their vanilla names (RED, STRIPE_TOP, ...). */
data class BannerDesign(val baseColor: String, val patterns: List<Layer>) {
    data class Layer(val type: String, val color: String)

    /** The banner block for this design: a wall banner keeps hanging on the wall, a standing one keeps standing. */
    fun material(wall: Boolean): String? {
        val base = baseColor.trim().uppercase()
        if (base !in DYES) return null
        return if (wall) "${base}_WALL_BANNER" else "${base}_BANNER"
    }

    /** Layers whose dye is known; a layer type the server doesn't know is dropped where the block is placed. */
    fun usableLayers(): List<Layer> = patterns
        .map { Layer(it.type.trim().lowercase(), it.color.trim().uppercase()) }
        .filter { it.type.isNotEmpty() && it.color in DYES }
        .take(MAX_LAYERS)

    companion object {
        val DYES = setOf(
            "WHITE", "ORANGE", "MAGENTA", "LIGHT_BLUE", "YELLOW", "LIME", "PINK", "GRAY",
            "LIGHT_GRAY", "CYAN", "PURPLE", "BLUE", "BROWN", "GREEN", "RED", "BLACK",
        )

        /** Vanilla banners show at most this many layers. */
        const val MAX_LAYERS = 16

        fun isBanner(material: String) = material.endsWith("_BANNER")
        fun isWallBanner(material: String) = material.endsWith("_WALL_BANNER")
    }
}

/** The guild that last won an arena, whose banners fly over it until another guild wins there. */
data class ArenaChampion(val guildId: UUID, val name: String, val wonAt: Long)

/**
 * Each arena's champion, one line per arena (`arena<TAB>guild<TAB>won-at<TAB>name`), written atomically so a crash
 * mid-write keeps the previous champions.
 */
class ChampionStore(private val file: File) {
    private var cache: MutableMap<String, ArenaChampion>? = null

    @Synchronized fun all(): Map<String, ArenaChampion> = load().toMap()

    @Synchronized fun get(arenaId: String): ArenaChampion? = load()[arenaId]

    @Synchronized fun set(arenaId: String, champion: ArenaChampion) {
        val map = load()
        map[arenaId] = champion
        save(map)
    }

    @Synchronized fun remove(arenaId: String): Boolean {
        val map = load()
        if (map.remove(arenaId) == null) return false
        save(map)
        return true
    }

    private fun load(): MutableMap<String, ArenaChampion> {
        cache?.let { return it }
        val map = mutableMapOf<String, ArenaChampion>()
        if (file.isFile) {
            file.readLines(Charsets.UTF_8).forEach { line ->
                val parts = line.split('\t', limit = 4)
                if (parts.size < 4 || parts[0].isBlank()) return@forEach
                val guild = runCatching { UUID.fromString(parts[1]) }.getOrNull() ?: return@forEach
                val at = parts[2].toLongOrNull() ?: return@forEach
                map[parts[0]] = ArenaChampion(guild, parts[3], at)
            }
        }
        cache = map
        return map
    }

    private fun save(map: Map<String, ArenaChampion>) {
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, file.name + ".tmp")
        temp.writeText(map.entries.sortedBy { it.key }.joinToString("") { (arena, c) ->
            "$arena\t${c.guildId}\t${c.wonAt}\t${c.name.replace('\t', ' ').replace('\n', ' ')}\n"
        }, Charsets.UTF_8)
        Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }
}
