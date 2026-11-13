package net.badgersmc.ek.infrastructure.display

import net.badgersmc.ek.infrastructure.bukkit.MenuStyle
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import org.bukkit.Bukkit
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Display
import org.bukkit.entity.TextDisplay
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.scheduler.BukkitTask
import java.io.File
import java.util.Locale

/** What a hologram shows. Arena holograms need an arena ID; custom ones read their lines from holograms.yml. */
enum class HologramType(val needsArena: Boolean = false) {
    ARENA(needsArena = true), SCHEDULE, BLITZ, TOP_PLAYERS, TOP_GUILDS, PLACEHOLDERS, CUSTOM;

    val id: String get() = name.lowercase(Locale.ROOT).replace('_', '-')

    companion object {
        fun parse(value: String?): HologramType? = entries.firstOrNull { it.id.equals(value?.trim(), true) || it.name.equals(value?.trim(), true) }
    }
}

/** What a create, move or remove did. Nothing changes unless holograms.yml was written. */
enum class HologramChange { DONE, EXISTS, UNKNOWN, SAVE_FAILED, FILE_UNREADABLE }

data class HologramSpec(
    val name: String,
    val type: HologramType,
    val world: String,
    val x: Double,
    val y: Double,
    val z: Double,
    val arena: String? = null,
    val lines: List<String> = emptyList(),
)

/**
 * Placed KOTH holograms: text displays that refresh every second and are re-created whenever their chunk loads.
 * They are never saved into the world, so removing one from holograms.yml or with /ekoth hologram remove leaves nothing behind.
 */
class KothHologramService(
    private val plugin: JavaPlugin,
    private val render: (HologramSpec) -> List<Component>,
    private val file: File = File(plugin.dataFolder, "holograms.yml"),
) {
    companion object {
        private val NAME = Regex("^[A-Za-z0-9_-]{1,32}$")
        fun validName(name: String): Boolean = NAME.matches(name)
    }

    private val specs = linkedMapOf<String, HologramSpec>()
    private val displays = mutableMapOf<String, TextDisplay>()
    private var task: BukkitTask? = null
    /** Set while holograms.yml cannot be parsed, so a save never overwrites definitions staff still need to repair. */
    private var fileUnreadable = false

    fun start() {
        load()
        task?.cancel()
        task = plugin.server.scheduler.runTaskTimer(plugin, Runnable { refresh() }, 20L, 20L)
    }

    fun names(): List<String> = specs.keys.sorted()
    fun all(): List<HologramSpec> = specs.values.toList()
    fun get(name: String): HologramSpec? = specs[name.lowercase(Locale.ROOT)]

    fun create(spec: HologramSpec): HologramChange {
        val key = spec.name.lowercase(Locale.ROOT)
        if (key in specs) return HologramChange.EXISTS
        return change { it[key] = spec.copy(name = key) }
    }

    fun move(name: String, location: Location): HologramChange {
        val key = name.lowercase(Locale.ROOT)
        val spec = specs[key] ?: return HologramChange.UNKNOWN
        val world = location.world?.name ?: return HologramChange.UNKNOWN
        return change { it[key] = spec.copy(world = world, x = location.x, y = location.y, z = location.z) }
            .also { if (it == HologramChange.DONE) { displays.remove(key)?.remove(); refresh() } }
    }

    fun remove(name: String): HologramChange {
        val key = name.lowercase(Locale.ROOT)
        if (key !in specs) return HologramChange.UNKNOWN
        return change { it.remove(key) }
    }

    /** Applies a change to a copy, writes it, and only then makes it live. */
    private fun change(edit: (MutableMap<String, HologramSpec>) -> Unit): HologramChange {
        if (fileUnreadable) return HologramChange.FILE_UNREADABLE
        val next = LinkedHashMap(specs).also(edit)
        if (!save(next.values)) return HologramChange.SAVE_FAILED
        specs.clear(); specs.putAll(next)
        refresh()
        return HologramChange.DONE
    }

    fun reload() {
        clearDisplays()
        load()
        refresh()
    }

    fun shutdown() {
        task?.cancel(); task = null
        clearDisplays()
    }

    private fun clearDisplays() {
        displays.values.forEach { it.remove() }
        displays.clear()
    }

    internal fun refresh() {
        displays.keys.filter { it !in specs }.forEach { displays.remove(it)?.remove() }
        specs.values.forEach { spec ->
            val world = Bukkit.getWorld(spec.world) ?: return@forEach
            if (!world.isChunkLoaded(Math.floorDiv(Math.floor(spec.x).toInt(), 16), Math.floorDiv(Math.floor(spec.z).toInt(), 16))) return@forEach
            val text = runCatching { render(spec) }.getOrElse { error ->
                plugin.logger.warning("KOTH hologram '${spec.name}' could not render: ${error.message}")
                listOf(MenuStyle.text("Unavailable", MenuStyle.CORAL))
            }.let { lines -> Component.join(net.kyori.adventure.text.JoinConfiguration.newlines(), lines) }
            val existing = displays[spec.name]?.takeIf { it.isValid }
            val display = existing ?: world.spawn(Location(world, spec.x, spec.y, spec.z), TextDisplay::class.java) {
                it.isPersistent = false
                it.billboard = Display.Billboard.CENTER
                it.alignment = TextDisplay.TextAlignment.CENTER
                it.isShadowed = true
                it.lineWidth = 240
                it.backgroundColor = Color.fromARGB(110, 20, 14, 10)
                it.viewRange = 0.6f
            }.also { displays[spec.name] = it }
            display.text(text)
        }
    }

    private fun load() {
        if (!file.exists()) { specs.clear(); fileUnreadable = false; return }
        val yaml = YamlConfiguration()
        try {
            yaml.load(file)
        } catch (error: Exception) {
            // Keep what is already placed; refuse to save until the file parses again.
            fileUnreadable = true
            plugin.logger.severe("holograms.yml could not be read (${error.message}); keeping the current holograms and refusing changes until it is fixed")
            return
        }
        fileUnreadable = false
        specs.clear()
        val section = yaml.getConfigurationSection("holograms") ?: return
        section.getKeys(false).forEach { name ->
            val entry = section.getConfigurationSection(name) ?: return@forEach
            val type = HologramType.parse(entry.getString("type"))
            val world = entry.getString("world")
            if (type == null || world == null || !validName(name)) {
                plugin.logger.warning("Ignoring KOTH hologram '$name' in holograms.yml: it needs a known type and a world")
                return@forEach
            }
            val key = name.lowercase(Locale.ROOT)
            specs[key] = HologramSpec(key, type, world, entry.getDouble("x"), entry.getDouble("y"), entry.getDouble("z"),
                entry.getString("arena")?.takeIf(String::isNotBlank), entry.getStringList("lines"))
        }
    }

    private fun save(all: Collection<HologramSpec>): Boolean {
        val yaml = YamlConfiguration()
        yaml.options().setHeader(listOf(
            "KOTH holograms. Manage them with /ekoth hologram, or edit this file and run /ekoth reload.",
            "Types: " + HologramType.entries.joinToString { it.id } + ". Custom lines are MiniMessage and may use PlaceholderAPI placeholders.",
        ))
        all.forEach { spec ->
            val path = "holograms.${spec.name}"
            yaml.set("$path.type", spec.type.id)
            spec.arena?.let { yaml.set("$path.arena", it) }
            yaml.set("$path.world", spec.world)
            yaml.set("$path.x", spec.x); yaml.set("$path.y", spec.y); yaml.set("$path.z", spec.z)
            if (spec.type == HologramType.CUSTOM) yaml.set("$path.lines", spec.lines)
        }
        return runCatching { file.parentFile?.mkdirs(); yaml.save(file) }
            .onFailure { plugin.logger.severe("Could not save holograms.yml: ${it.message}") }
            .isSuccess
    }
}

/** Placeholder values come back with legacy section-sign colours; holograms show them as components. */
internal fun legacyValue(value: String): Component = LegacyComponentSerializer.legacySection().deserialize(value)
