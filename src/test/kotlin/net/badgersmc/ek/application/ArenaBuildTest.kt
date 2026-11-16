package net.badgersmc.ek.application

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class ArenaBuildTest {
    @TempDir lateinit var folder: File

    private fun block(x: Int, y: Int, z: Int, built: String = "minecraft:gold_block") = BuildBlock(x, y, z, "minecraft:air", built)

    @Test fun `arenas rise bottom layer first, from the capture point outward`() {
        val blocks = listOf(block(10, 81, 0), block(0, 80, 0), block(5, 80, 0), block(0, 81, 0), block(-3, 80, 0))
        val order = ArenaBuild.buildOrder(blocks, centerX = 0.5, centerZ = 0.5)
        assertEquals(listOf(0 to 80, -3 to 80, 5 to 80, 0 to 81, 10 to 81), order.map { it.x to it.y })
    }

    @Test fun `batches spread every block over the animation and finish on its last tick`() {
        val batches = ArenaBuild.batches(count = 10, ticks = 4)
        assertEquals(4, batches.size)
        assertEquals(10, batches.sum())
        assertTrue(batches.all { it in 2..3 })
        assertEquals(listOf(1, 1, 1), ArenaBuild.batches(count = 3, ticks = 80))
        assertTrue(ArenaBuild.batches(count = 0, ticks = 80).isEmpty())
    }

    @Test fun `the build is exactly what differs from the base form`() {
        val base = BaseSnapshot("world", 0, 0, 0, 1, 1, 1, listOf("minecraft:air", "minecraft:stone"), IntArray(8) { if (it < 4) 1 else 0 })
        assertEquals("minecraft:stone", base.at(1, 0, 1))
        assertEquals("minecraft:air", base.at(0, 1, 0))
        val now = mapOf(Triple(0, 1, 0) to "minecraft:gold_block", Triple(1, 0, 0) to "minecraft:air")
        val build = base.diff("summit") { x, y, z -> now[Triple(x, y, z)] ?: base.at(x, y, z) }
        assertEquals(setOf(BuildBlock(0, 1, 0, "minecraft:air", "minecraft:gold_block"), BuildBlock(1, 0, 0, "minecraft:stone", "minecraft:air")), build.blocks.toSet())
        assertTrue(base.diff("summit") { x, y, z -> base.at(x, y, z) }.blocks.isEmpty())
    }

    @Test fun `base, build and journal survive a round trip, and journals list interrupted arenas`() {
        val store = ArenaBuildStore(folder)
        val base = BaseSnapshot("world", -2, 79, 3, 0, 80, 4, listOf("minecraft:air", "minecraft:red_nether_bricks"), IntArray(12) { it % 2 })
        store.saveBase("moving", base)
        assertEquals(base, store.loadBase("moving"))
        val build = ArenaBuild("moving", "world", listOf(block(1, 80, 2), BuildBlock(2, 80, 2, "minecraft:stone", "minecraft:lantern[hanging=false]")))
        store.saveBuild(build)
        assertEquals(build, store.loadBuild("moving"))
        assertTrue(store.journals().isEmpty())
        store.startJournal(build)
        assertEquals(listOf(build), store.journals())
        store.endJournal("moving")
        assertTrue(store.journals().isEmpty())
        store.clear("moving")
        assertFalse(store.hasBase("moving") || store.hasBuild("moving"))
        assertNull(store.loadBuild("moving"))
        assertTrue(folder.walkTopDown().none { it.name.endsWith(".tmp") })
    }
}
