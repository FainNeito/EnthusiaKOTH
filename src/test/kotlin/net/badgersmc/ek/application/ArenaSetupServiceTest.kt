package net.badgersmc.ek.application

import net.badgersmc.ek.config.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ArenaSetupServiceTest {
    private val center = PositionConfig(10.5, 80.0, 10.5)
    private val arenas = mutableMapOf<String, ArenaConfig>()
    private var revision = "first"
    private var busy = false
    private var writes = 0
    private val store = object : ArenaSetupStore {
        override fun read() = ArenaSetupSnapshot(revision, arenas.toMap())
        override fun write(id: String, arena: ArenaConfig, expectedRevision: String) { writes++; arenas[id] = arena; revision = "next" }
    }
    private val service = ArenaSetupService(store, { busy }, { it == "world" }, { _, name -> name == "koth" })
    private fun draft() = service.begin("hill", "world", center, "capture").also {
        service.selectBoundary(it, "world", PositionConfig(0.0, 65.0, 0.0), PositionConfig(25.0, 95.0, 25.0))
    }
    @Test fun `new draft is disabled with isolated zero reward family and no persistence`() {
        val draft = draft()
        assertFalse(draft.arena.enabled); assertTrue(draft.arena.schedule.isEmpty())
        assertTrue(draft.arena.rewards.isEmpty()); assertNotNull(draft.arena.rewardFamily)
        assertEquals(0, writes)
    }
    @Test fun `save persists valid draft only explicitly`() { val d = draft(); service.save(d); assertEquals(d.arena, arenas["hill"]) }
    @Test fun `selection includes upper block and clears binding only when requested`() {
        arenas["hill"] = ArenaConfig(worldGuardRegion = "koth", center = center)
        val d = service.begin("hill", "world", center)
        assertEquals("koth", d.arena.worldGuardRegion)
        service.selectBoundary(d, "world", PositionConfig(25.0, 95.0, 25.0), PositionConfig(0.0, 65.0, 0.0))
        assertNull(d.arena.worldGuardRegion); assertEquals(26.0, d.arena.protectedRegion.corner2.x)
    }
    @Test fun `wrong world does not change draft`() {
        val d = draft(); val before = d.arena
        assertEquals(SetupIssue.WORLD, assertThrows(SetupException::class.java) {
            service.selectBoundary(d, "nether", center, center)
        }.issue); assertEquals(before, d.arena)
    }
    @Test fun `missing selection blocks save`() {
        val d = service.begin("hill", "world", center, "capture")
        assertEquals(SetupIssue.BOUNDARY, assertThrows(SetupException::class.java) { service.save(d) }.issue)
        assertEquals(0, writes)
    }
    @Test fun `circle extending beyond selected arena blocks save`() {
        val d = draft(); d.arena = d.arena.copy(radius = 20.0)
        assertTrue(SetupIssue.OUTSIDE in service.issues(d)); assertEquals(0, writes)
    }
    @Test fun `vertical capture bounds must fit too`() {
        val d = draft(); d.arena = d.arena.copy(center = center.copy(y = 65.0))
        assertTrue(SetupIssue.OUTSIDE in service.issues(d))
    }
    @Test fun `nonfinite geometry and capture longer than event rejected`() {
        val d = draft(); d.arena = d.arena.copy(radius = Double.NaN, captureSeconds = 901)
        assertTrue(service.issues(d).containsAll(listOf(SetupIssue.GEOMETRY, SetupIssue.TIMING)))
    }
    @Test fun `active or queued event refuses save without storage`() {
        val d = draft(); busy = true
        assertEquals(SetupIssue.BUSY, assertThrows(SetupException::class.java) { service.save(d) }.issue); assertEquals(0, writes)
    }
    @Test fun `concurrent edit is detected without overwriting`() {
        val d = draft(); revision = "external"
        assertEquals(SetupIssue.STALE, assertThrows(SetupException::class.java) { service.save(d) }.issue); assertEquals(0, writes)
    }
    @Test fun `duplicate create missing edit invalid id and family rejected`() {
        arenas["hill"] = ArenaConfig()
        assertEquals(SetupIssue.EXISTS, assertThrows(SetupException::class.java) { service.begin("hill", "world", center, "capture") }.issue)
        assertEquals(SetupIssue.MISSING, assertThrows(SetupException::class.java) { service.begin("none", "world", center) }.issue)
        assertEquals(SetupIssue.ID, assertThrows(SetupException::class.java) { service.begin("../bad", "world", center, "capture") }.issue)
        assertEquals(SetupIssue.FAMILY, assertThrows(SetupException::class.java) { service.begin("new", "world", center, "unknown") }.issue)
    }
    @Test fun `existing rewards and rules survive draft settings change`() {
        arenas["hill"] = ArenaConfig(worldGuardRegion = "koth", center = center, rewards = listOf("bank 500"), schedule = listOf("12:00"), rewardFamily = "custom", ignoreFactions = true)
        val d = service.begin("hill", "world", center); d.arena = d.arena.copy(keepInventory = true)
        service.save(d); assertEquals(listOf("bank 500"), arenas["hill"]!!.rewards)
        assertEquals("custom", arenas["hill"]!!.rewardFamily); assertTrue(arenas["hill"]!!.ignoreFactions)
    }
    @Test fun `missing named region and unloaded world show readiness errors`() {
        arenas["hill"] = ArenaConfig(world = "missing", worldGuardRegion = "missing")
        assertTrue(service.issues(service.begin("hill", "world", center)).containsAll(listOf(SetupIssue.REGION, SetupIssue.WORLD)))
    }
    @Test fun `capture center outside named region blocks save`() {
        arenas["hill"] = ArenaConfig(worldGuardRegion = "koth", center = center)
        val checked = ArenaSetupService(store, { false }, { true }, { _, _ -> true }, { _, _, _ -> false })
        val d = checked.begin("hill", "world", center)
        assertEquals(SetupIssue.OUTSIDE, assertThrows(SetupException::class.java) { checked.save(d) }.issue)
        assertEquals(0, writes)
    }
    @Test fun `existing mixed case id remains editable without renaming`() {
        arenas["LegacyHill"] = ArenaConfig(worldGuardRegion = "koth", center = center)
        val d = service.begin("LegacyHill", "world", center)
        service.save(d); assertTrue(arenas.containsKey("LegacyHill")); assertFalse(arenas.containsKey("legacyhill"))
    }
}
