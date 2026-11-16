package net.badgersmc.ek.infrastructure.bukkit

import net.badgersmc.ek.api.KothLifecycle
import net.badgersmc.ek.api.KothLifecycleEvent
import net.badgersmc.ek.application.ArenaBuild
import net.badgersmc.ek.application.ArenaBuildStore
import net.badgersmc.ek.application.BaseSnapshot
import net.badgersmc.ek.application.BuildBlock
import net.badgersmc.ek.config.ArenaBuildConfig
import net.badgersmc.ek.domain.CaptureZone
import net.badgersmc.ek.domain.KothArena
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Particle
import org.bukkit.World
import org.bukkit.block.data.BlockData
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.scheduler.BukkitTask
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Arenas that build themselves when a KOTH starts. Staff record a base form and a built form; the difference rises
 * out of the ground over a few seconds when an event starts there, and comes down again in reverse when it ends.
 *
 * The blocks are real, so players can stand on and hide behind them. Before an arena builds, the blocks it changes are
 * journalled to disk; if the server stops mid-event, the next start puts the base form back from that journal.
 */
class ArenaBuildService(
    private val plugin: JavaPlugin,
    private val store: ArenaBuildStore,
    private val arenas: () -> Map<String, KothArena>,
    private val settings: () -> ArenaBuildConfig,
    /** The arena's protection region, from its config or WorldGuard, when it has one. */
    private val regionBounds: (KothArena) -> CaptureZone?,
    /** Whether a KOTH is running or queued in the arena, which blocks recording. */
    private val arenaBusy: (String) -> Boolean,
    /** Called for each banner the build places, so it can take on the arena's champion colours. */
    private val decorate: (String, org.bukkit.block.Block) -> Unit = { _, _ -> },
) : Listener {
    /** Arenas currently built or building, with how far the build got. */
    private class Active(val build: ArenaBuild, val order: List<BuildBlock>) {
        var placed = 0
        var task: BukkitTask? = null
    }

    private val active = mutableMapOf<String, Active>()
    private val tearingDown = mutableMapOf<String, Pair<ArenaBuild, BukkitTask>>()
    /** Arenas standing a little longer after a win, so the victors see their banners go up. */
    private val holding = mutableMapOf<String, BukkitTask>()
    private val parsed = mutableMapOf<String, BlockData>()

    /** Puts back any arena the server stopped in the middle of. Call once on enable. */
    fun recover() {
        store.journals().forEach { build ->
            runCatching { restoreNow(build) }
                .onSuccess { plugin.logger.warning("Restored the base form of arena '${build.arenaId}' after an interrupted KOTH (${build.blocks.size} blocks)") }
                .onFailure { plugin.logger.severe("Could not restore arena '${build.arenaId}' after an interrupted KOTH: ${it.message}; its journal is kept for the next start") }
                .onSuccess { store.endJournal(build.arenaId) }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onLifecycle(event: KothLifecycleEvent) {
        val arena = event.snapshot.arenaId
        when (event.lifecycle) {
            KothLifecycle.STARTED -> runCatching { build(arena) }.onFailure { plugin.logger.severe("Arena '$arena' could not build itself: ${it.message}") }
            KothLifecycle.COMPLETED, KothLifecycle.CANCELLED -> {
                // A reload or shutdown can't wait for an animation, so the base form goes back at once.
                val instant = !plugin.isEnabled || event.snapshot.reason in setOf("RELOAD", "PLUGIN_DISABLE")
                val hold = settings().victoryHoldSeconds
                if (!instant && hold > 0 && event.lifecycle == KothLifecycle.COMPLETED && event.snapshot.winner != null && arena in active) {
                    // The winners get a moment in the finished arena under their own banners before it comes down.
                    holding.remove(arena)?.cancel()
                    holding[arena] = plugin.server.scheduler.runTaskLater(plugin, Runnable {
                        holding.remove(arena)
                        runCatching { teardown(arena) }.onFailure { plugin.logger.severe("Arena '$arena' could not take itself down: ${it.message}") }
                    }, hold * 20L)
                    return
                }
                runCatching { teardown(arena, instant) }.onFailure { plugin.logger.severe("Arena '$arena' could not take itself down: ${it.message}") }
            }
            else -> Unit
        }
    }

    /** Starts the build animation for [arenaId], if it has a recorded build. Returns how many blocks it will place. */
    fun build(arenaId: String): Int {
        val cfg = settings()
        // A KOTH starting while the last one's victory hold stands keeps the arena up as it is.
        holding.remove(arenaId)?.cancel()
        if (!cfg.enabled || arenaId in active) return 0
        finishTeardown(arenaId)
        val build = store.loadBuild(arenaId)?.takeIf { it.blocks.isNotEmpty() } ?: return 0
        val world = Bukkit.getWorld(build.world) ?: return 0
        val arena = arenas()[arenaId]
        val centerX = arena?.zone?.let { (it.minX + it.maxX) / 2 } ?: build.blocks.map { it.x }.average()
        val centerZ = arena?.zone?.let { (it.minZ + it.maxZ) / 2 } ?: build.blocks.map { it.z }.average()
        store.startJournal(build)
        val state = Active(build, ArenaBuild.buildOrder(build.blocks, centerX, centerZ))
        active[arenaId] = state
        val batches = ArenaBuild.batches(state.order.size, cfg.buildSeconds * 20)
        var tick = 0
        state.task = plugin.server.scheduler.runTaskTimer(plugin, Runnable {
            if (tick >= batches.size) { state.task?.cancel(); state.task = null; return@Runnable }
            val batch = state.order.subList(state.placed, state.placed + batches[tick])
            batch.forEach { block -> set(world, block, block.built); decorateIfBanner(arenaId, world, block) }
            effects(world, batch, place = true)
            state.placed += batch.size
            liftPlayers(world, batch)
            tick++
        }, 1L, 1L)
        return state.order.size
    }

    /** Takes [arenaId] back to its base form: in reverse over a few seconds, or at once when [instant]. */
    fun teardown(arenaId: String, instant: Boolean = false) {
        holding.remove(arenaId)?.cancel()
        val state = active.remove(arenaId) ?: return
        state.task?.cancel()
        val world = Bukkit.getWorld(state.build.world)
        if (instant || world == null || !plugin.isEnabled) {
            restoreNow(state.build)
            store.endJournal(arenaId)
            return
        }
        val order = state.order.take(state.placed).reversed()
        val batches = ArenaBuild.batches(order.size, settings().teardownSeconds * 20)
        var tick = 0
        var done = 0
        lateinit var task: BukkitTask
        task = plugin.server.scheduler.runTaskTimer(plugin, Runnable {
            if (tick >= batches.size) {
                // Everything the build could have touched goes back, including blocks it never reached.
                restoreNow(state.build)
                store.endJournal(arenaId)
                tearingDown.remove(arenaId)
                task.cancel()
                return@Runnable
            }
            val batch = order.subList(done, done + batches[tick])
            batch.forEach { block -> set(world, block, block.base) }
            effects(world, batch, place = false)
            done += batch.size
            tick++
        }, 1L, 1L)
        tearingDown[arenaId] = state.build to task
    }

    private fun finishTeardown(arenaId: String) {
        val (build, task) = tearingDown.remove(arenaId) ?: return
        task.cancel()
        restoreNow(build)
        store.endJournal(arenaId)
    }

    /** Puts every built arena back at once. Call on disable and before a reload. */
    fun shutdown() {
        holding.values.forEach { it.cancel() }
        holding.clear()
        tearingDown.keys.toList().forEach(::finishTeardown)
        active.keys.toList().forEach { teardown(it, instant = true) }
    }

    fun isBuilt(arenaId: String) = arenaId in active

    /** Seconds a KOTH in [arenaId] waits while its arena builds itself; 0 when it has nothing to build. */
    fun warmupSeconds(arenaId: String): Int {
        val cfg = settings()
        return if (cfg.enabled && store.hasBuild(arenaId)) cfg.buildSeconds + 1 else 0
    }

    // Recording ---------------------------------------------------------------------------------------------------

    enum class Result { DONE, NO_ARENA, NO_WORLD, BUSY, NO_BASE, NO_BUILD, NOTHING_CHANGED, TOO_LARGE, TOO_MANY }

    /** Saves the arena's region as it stands now as its base form. */
    fun recordBase(arenaId: String): Pair<Result, Int> {
        val arena = arenas()[arenaId] ?: return Result.NO_ARENA to 0
        if (arenaBusy(arenaId) || arenaId in active) return Result.BUSY to 0
        val world = Bukkit.getWorld(arena.zone.worldName) ?: return Result.NO_WORLD to 0
        val box = scanBox(arena, world)
        val volume = box.volume()
        if (volume > settings().maxScanBlocks) return Result.TOO_LARGE to volume.toInt()
        val palette = mutableListOf<String>()
        val lookup = mutableMapOf<String, Int>()
        val blocks = IntArray(volume.toInt())
        var index = 0
        for (y in box.minY..box.maxY) for (z in box.minZ..box.maxZ) for (x in box.minX..box.maxX) {
            val data = world.getBlockAt(x, y, z).blockData.asString
            blocks[index++] = lookup.getOrPut(data) { palette += data; palette.size - 1 }
        }
        store.saveBase(arenaId, BaseSnapshot(world.name, box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ, palette, blocks))
        return Result.DONE to volume.toInt()
    }

    /** Records whatever differs from the base form as the arena's build, then puts the base form back. */
    fun recordBuild(arenaId: String): Pair<Result, Int> {
        if (arenaId !in arenas()) return Result.NO_ARENA to 0
        if (arenaBusy(arenaId)) return Result.BUSY to 0
        val base = store.loadBase(arenaId) ?: return Result.NO_BASE to 0
        val world = Bukkit.getWorld(base.world) ?: return Result.NO_WORLD to 0
        val build = base.diff(arenaId) { x, y, z -> world.getBlockAt(x, y, z).blockData.asString }
        if (build.blocks.isEmpty()) return Result.NOTHING_CHANGED to 0
        if (build.blocks.size > settings().maxBlocks) return Result.TOO_MANY to build.blocks.size
        active.remove(arenaId)?.task?.cancel()
        store.saveBuild(build)
        restoreNow(build)
        store.endJournal(arenaId)
        return Result.DONE to build.blocks.size
    }

    /** Puts the recorded build in place at once, so staff can change it and record it again. */
    fun edit(arenaId: String): Pair<Result, Int> {
        if (arenaBusy(arenaId) || arenaId in active) return Result.BUSY to 0
        val build = store.loadBuild(arenaId) ?: return Result.NO_BUILD to 0
        val world = Bukkit.getWorld(build.world) ?: return Result.NO_WORLD to 0
        store.startJournal(build)
        build.blocks.forEach { set(world, it, it.built); decorateIfBanner(arenaId, world, it) }
        return Result.DONE to build.blocks.size
    }

    /** Builds the arena, then takes it down again after the preview time. */
    fun preview(arenaId: String): Pair<Result, Int> {
        if (arenaBusy(arenaId) || arenaId in active) return Result.BUSY to 0
        if (!store.hasBuild(arenaId)) return Result.NO_BUILD to 0
        val placed = build(arenaId)
        if (placed == 0) return Result.NO_BUILD to 0
        plugin.server.scheduler.runTaskLater(plugin, Runnable { if (!arenaBusy(arenaId)) teardown(arenaId) },
            (settings().buildSeconds + settings().previewSeconds) * 20L)
        return Result.DONE to placed
    }

    fun clear(arenaId: String): Result {
        if (arenaBusy(arenaId) || arenaId in active) return Result.BUSY
        store.clear(arenaId)
        return Result.DONE
    }

    fun status(arenaId: String): Triple<Boolean, Int?, Boolean> =
        Triple(store.hasBase(arenaId), store.loadBuild(arenaId)?.blocks?.size, arenaId in active)

    // Helpers -----------------------------------------------------------------------------------------------------

    internal data class Box(val minX: Int, val minY: Int, val minZ: Int, val maxX: Int, val maxY: Int, val maxZ: Int) {
        fun volume(): Long = (maxX - minX + 1L) * (maxY - minY + 1L) * (maxZ - minZ + 1L)
    }

    /** The arena's protection region (or its zone with a margin), from a little below the capture point to well above it. */
    private fun scanBox(arena: KothArena, world: World): Box {
        val cfg = settings()
        val region = regionBounds(arena)
        val zone = arena.zone
        val margin = if (region == null) cfg.margin.toDouble() else 0.0
        val area = region ?: zone
        val centerY = zone.objectiveY
        return Box(
            floor(area.minX - margin).toInt(), maxOf(world.minHeight, floor(centerY - cfg.below).toInt()), floor(area.minZ - margin).toInt(),
            ceil(area.maxX + margin).toInt() - 1, minOf(world.maxHeight - 1, floor(centerY + cfg.above).toInt()), ceil(area.maxZ + margin).toInt() - 1,
        )
    }

    private fun data(text: String): BlockData = parsed.getOrPut(text) { Bukkit.createBlockData(text) }

    private fun set(world: World, block: BuildBlock, state: String) {
        world.getBlockAt(block.x, block.y, block.z).setBlockData(data(state), false)
    }

    private fun decorateIfBanner(arenaId: String, world: World, block: BuildBlock) {
        if (!data(block.built).material.name.endsWith("_BANNER")) return
        runCatching { decorate(arenaId, world.getBlockAt(block.x, block.y, block.z)) }
            .onFailure { plugin.logger.warning("Arena '$arenaId' could not dress a banner: ${it.message}") }
    }

    private fun restoreNow(build: ArenaBuild) {
        val world = Bukkit.getWorld(build.world) ?: error("world '${build.world}' is not loaded")
        build.blocks.forEach { set(world, it, it.base) }
    }

    /** A couple of block sounds and a puff of the block's own dust each tick, so the arena audibly assembles. */
    private fun effects(world: World, batch: List<BuildBlock>, place: Boolean) {
        if (batch.isEmpty()) return
        val step = maxOf(1, batch.size / 2)
        batch.filterIndexed { index, _ -> index % step == 0 }.take(2).forEach { block ->
            val shown = data(if (place) block.built else block.base)
            val dust = data(block.built)
            val at = Location(world, block.x + 0.5, block.y + 0.5, block.z + 0.5)
            val sound = if (place) shown.soundGroup.placeSound else dust.soundGroup.breakSound
            world.playSound(at, sound, 0.55f, 0.9f + (block.y % 3) * 0.08f)
            if (!dust.material.isAir) world.spawnParticle(Particle.BLOCK, at, 6, 0.3, 0.3, 0.3, 0.0, dust)
        }
    }

    /** A block that rises into a player lifts them on top of it rather than trapping them. */
    private fun liftPlayers(world: World, batch: List<BuildBlock>) {
        if (batch.isEmpty()) return
        val cells = batch.filter { data(it.built).material.isSolid }.map { Triple(it.x, it.y, it.z) }.toSet()
        if (cells.isEmpty()) return
        world.players.forEach { player ->
            val at = player.location
            val feet = Triple(at.blockX, at.blockY, at.blockZ)
            val head = Triple(at.blockX, at.blockY + 1, at.blockZ)
            if (feet !in cells && head !in cells) return@forEach
            var y = at.blockY
            while (y < at.blockY + 12 && (world.getBlockAt(at.blockX, y, at.blockZ).type.isSolid || world.getBlockAt(at.blockX, y + 1, at.blockZ).type.isSolid)) y++
            player.teleport(at.clone().apply { this.y = y.toDouble() })
        }
    }
}
