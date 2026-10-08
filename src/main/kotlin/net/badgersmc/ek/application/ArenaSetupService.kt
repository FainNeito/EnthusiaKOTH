package net.badgersmc.ek.application

import net.badgersmc.ek.config.ArenaConfig
import net.badgersmc.ek.config.PositionConfig
import net.badgersmc.ek.config.ProtectedRegionConfig

data class ArenaSetupSnapshot(val revision: String, val arenas: Map<String, ArenaConfig>)
interface ArenaSetupStore {
    fun read(): ArenaSetupSnapshot
    fun write(id: String, arena: ArenaConfig, expectedRevision: String)
}
data class ArenaSetupDraft(
    val id: String,
    val revision: String,
    val creating: Boolean,
    var arena: ArenaConfig,
    var boundaryReady: Boolean,
)
enum class SetupIssue { ID, EXISTS, MISSING, FAMILY, WORLD, BOUNDARY, GEOMETRY, TIMING, OUTSIDE, REGION, BUSY, STALE, IO }
class SetupException(val issue: SetupIssue) : RuntimeException(issue.name)

/** Pure draft policy; Bukkit geometry, storage and rendering are adapters. */
class ArenaSetupService(
    private val store: ArenaSetupStore,
    private val busy: () -> Boolean,
    private val worldExists: (String) -> Boolean,
    private val regionExists: (String, String) -> Boolean,
    private val regionContainsCenter: (String, String, PositionConfig) -> Boolean = { _, _, _ -> true },
) {
    fun begin(id: String, world: String, center: PositionConfig, family: String? = null): ArenaSetupDraft {
        if (family != null && !Regex("[a-z0-9][a-z0-9_-]{0,47}").matches(id)) throw SetupException(SetupIssue.ID)
        val snapshot = store.read()
        val existing = snapshot.arenas[id]
        if (family != null && existing != null) throw SetupException(SetupIssue.EXISTS)
        if (family == null && existing == null) throw SetupException(SetupIssue.MISSING)
        if (family != null && family !in setOf("capture", "moving", "conquest", "score")) throw SetupException(SetupIssue.FAMILY)
        return ArenaSetupDraft(id, snapshot.revision, family != null,
            existing ?: ArenaConfig(enabled = false, family = family!!, world = world, center = center,
                rewardFamily = "setup_${java.util.UUID.randomUUID()}"), existing != null)
    }

    fun selectBoundary(draft: ArenaSetupDraft, world: String, first: PositionConfig, second: PositionConfig) {
        if (world != draft.arena.world) throw SetupException(SetupIssue.WORLD)
        val min = PositionConfig(minOf(first.x, second.x), minOf(first.y, second.y), minOf(first.z, second.z))
        // Selected block corners include the complete upper block, not just its lower vertex.
        val max = PositionConfig(maxOf(first.x, second.x) + 1, maxOf(first.y, second.y) + 1, maxOf(first.z, second.z) + 1)
        draft.arena = draft.arena.copy(worldGuardRegion = null, protectedRegion = ProtectedRegionConfig(min, max))
        draft.boundaryReady = true
    }

    fun issues(draft: ArenaSetupDraft): List<SetupIssue> = buildList {
        val a = draft.arena
        if (!worldExists(a.world)) add(SetupIssue.WORLD)
        if (!draft.boundaryReady) add(SetupIssue.BOUNDARY)
        val points = listOf(a.center, a.protectedRegion.corner1, a.protectedRegion.corner2)
        if (points.any { !it.x.isFinite() || !it.y.isFinite() || !it.z.isFinite() } ||
            !a.radius.isFinite() || a.radius <= 0 || a.radius > 256 ||
            !a.movingSquareSize.isFinite() || a.movingSquareSize <= 0) add(SetupIssue.GEOMETRY)
        if (a.durationSeconds <= 0 || a.durationSeconds > 86400 || a.captureSeconds <= 0 ||
            (a.family != "moving" && a.captureSeconds > a.durationSeconds)) add(SetupIssue.TIMING)
        if (a.worldGuardRegion != null) {
            if (!regionExists(a.world, a.worldGuardRegion)) add(SetupIssue.REGION)
            else if (SetupIssue.GEOMETRY !in this && !regionContainsCenter(a.world, a.worldGuardRegion, a.center)) add(SetupIssue.OUTSIDE)
        } else if (draft.boundaryReady && SetupIssue.GEOMETRY !in this) {
            val p = a.protectedRegion.corner1; val q = a.protectedRegion.corner2
            val half = if (a.family == "moving") a.movingSquareSize / 2 + a.radius else a.radius
            if (a.center.x - half < minOf(p.x, q.x) || a.center.x + half > maxOf(p.x, q.x) ||
                a.center.y - a.radius < minOf(p.y, q.y) || a.center.y + a.radius > maxOf(p.y, q.y) ||
                a.center.z - half < minOf(p.z, q.z) || a.center.z + half > maxOf(p.z, q.z)) add(SetupIssue.OUTSIDE)
        }
    }

    fun save(draft: ArenaSetupDraft) {
        if (busy()) throw SetupException(SetupIssue.BUSY)
        issues(draft).firstOrNull()?.let { throw SetupException(it) }
        val current = store.read()
        if (current.revision != draft.revision) throw SetupException(SetupIssue.STALE)
        if (draft.creating && current.arenas.containsKey(draft.id)) throw SetupException(SetupIssue.EXISTS)
        store.write(draft.id, draft.arena, draft.revision)
    }
}
