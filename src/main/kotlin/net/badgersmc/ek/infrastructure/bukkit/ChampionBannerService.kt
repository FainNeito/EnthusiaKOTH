package net.badgersmc.ek.infrastructure.bukkit

import net.badgersmc.ek.api.KothLifecycle
import net.badgersmc.ek.api.KothLifecycleEvent
import net.badgersmc.ek.application.ArenaChampion
import net.badgersmc.ek.application.BannerDesign
import net.badgersmc.ek.application.ChampionStore
import net.badgersmc.ek.domain.CaptureZone
import net.badgersmc.ek.domain.KothArena
import org.bukkit.Bukkit
import org.bukkit.DyeColor
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Registry
import org.bukkit.block.Banner
import org.bukkit.block.Block
import org.bukkit.block.data.Directional
import org.bukkit.block.data.Rotatable
import org.bukkit.block.banner.Pattern
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.plugin.java.JavaPlugin
import java.util.UUID
import kotlin.math.ceil
import kotlin.math.floor

/**
 * The guild that last won an arena flies its banner there. Every banner in the arena takes on the champion guild's
 * design: when the guild wins, and each time the arena builds itself for a KOTH, until another guild wins it.
 *
 * Only confirmed guild wins count: a solo win, a private test, or a win whose reward was withheld leaves the
 * reigning champion in place. A guild without a banner of its own leaves the arena's banners as they were built.
 */
class ChampionBannerService(
    private val plugin: JavaPlugin,
    private val store: ChampionStore,
    private val arenas: () -> Map<String, KothArena>,
    private val enabled: () -> Boolean,
    /** The arena's protection region, from its config or WorldGuard, when it has one. */
    private val regionBounds: (KothArena) -> CaptureZone?,
    private val guildName: (UUID) -> String?,
    private val guildBanner: (UUID) -> BannerDesign?,
) : Listener {

    @EventHandler(priority = EventPriority.MONITOR)
    fun onLifecycle(event: KothLifecycleEvent) {
        if (event.lifecycle != KothLifecycle.COMPLETED || event.snapshot.privateTest) return
        val winner = event.snapshot.winner?.takeIf { it.mode == "GUILD" } ?: return
        crown(event.snapshot.arenaId, winner.id)
    }

    /** Makes [guildId] the arena's champion and raises its banners there now. Returns how many banners changed. */
    fun crown(arenaId: String, guildId: UUID): Int {
        val name = runCatching { guildName(guildId) }.getOrNull() ?: guildId.toString().take(8)
        runCatching { store.set(arenaId, ArenaChampion(guildId, name, System.currentTimeMillis())) }
            .onFailure { plugin.logger.warning("Could not save the champion of arena '$arenaId': ${it.message}") }
        return restyleArena(arenaId)
    }

    fun champion(arenaId: String): ArenaChampion? = runCatching { store.get(arenaId) }.getOrNull()

    /** Forgets the arena's champion; its banners go back to their own design the next time it builds. */
    fun clear(arenaId: String): Boolean = runCatching { store.remove(arenaId) }.getOrDefault(false)

    /** Called for each banner the arena build places, so the arena rises already flying its champion's colours. */
    fun decorate(arenaId: String, block: Block) {
        val design = design(arenaId) ?: return
        restyle(block, design)
    }

    /** Restyles every banner standing in the arena right now. Returns how many changed. */
    fun restyleArena(arenaId: String): Int {
        val design = design(arenaId) ?: return 0
        val arena = arenas()[arenaId] ?: return 0
        val world = Bukkit.getWorld(arena.zone.worldName) ?: return 0
        val region = regionBounds(arena)
        val area = region ?: arena.zone
        // A protection region is the arena's edge; without one, look a little beyond the capture zone.
        val margin = if (region == null) ZONE_MARGIN else 0
        val minX = floor(area.minX).toInt() - margin
        val maxX = ceil(area.maxX).toInt() - 1 + margin
        val minZ = floor(area.minZ).toInt() - margin
        val maxZ = ceil(area.maxZ).toInt() - 1 + margin
        var changed = 0
        for (cx in (minX shr 4)..(maxX shr 4)) for (cz in (minZ shr 4)..(maxZ shr 4)) {
            if (!world.isChunkLoaded(cx, cz)) continue
            world.getChunkAt(cx, cz).getTileEntities(false).forEach { state ->
                val loc = state.location
                if (state is Banner && loc.blockX in minX..maxX && loc.blockZ in minZ..maxZ && restyle(state.block, design)) changed++
            }
        }
        return changed
    }

    private fun design(arenaId: String): BannerDesign? {
        if (!enabled()) return null
        val champion = champion(arenaId) ?: return null
        return runCatching { guildBanner(champion.guildId) }.getOrNull()
    }

    /** Swaps one banner block for the design, keeping which way it faces. Returns false when it isn't a banner. */
    private fun restyle(block: Block, design: BannerDesign): Boolean {
        val current = block.type
        if (!BannerDesign.isBanner(current.name)) return false
        val material = design.material(BannerDesign.isWallBanner(current.name))?.let(Material::matchMaterial) ?: return false
        val old = block.blockData
        val data = material.createBlockData().also { data ->
            if (old is Directional && data is Directional) data.facing = old.facing
            if (old is Rotatable && data is Rotatable) data.rotation = old.rotation
        }
        if (current != material) block.setBlockData(data, false)
        val banner = block.state as? Banner ?: return false
        banner.patterns = design.usableLayers().mapNotNull { layer ->
            val type = Registry.BANNER_PATTERN.get(NamespacedKey.minecraft(layer.type)) ?: return@mapNotNull null
            Pattern(DyeColor.valueOf(layer.color), type)
        }
        banner.update(true, false)
        return true
    }

    private companion object {
        /** Arenas without a protection region still catch banners just outside the capture zone. */
        const val ZONE_MARGIN = 16
    }
}
