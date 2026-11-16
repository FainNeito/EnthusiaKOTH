package net.badgersmc.ek.application

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.LocalDate
import kotlin.random.Random

class ArenaLayoutsTest {
    @TempDir lateinit var folder: File

    private fun build(arena: String, vararg built: String) =
        ArenaBuild(arena, "world", built.mapIndexed { i, state -> BuildBlock(i, 80, 0, "minecraft:air", state) })

    @Test fun `names are lower case and short`() {
        assertTrue(ArenaLayouts.validName("frozen-keep_2"))
        assertFalse(ArenaLayouts.validName("Frozen")); assertFalse(ArenaLayouts.validName("../x")); assertFalse(ArenaLayouts.validName(""))
        assertFalse(ArenaLayouts.validName("a".repeat(33)))
    }

    @Test fun `weekly rotation changes on Mondays and walks the names in order`() {
        val names = listOf("c", "a", "b")
        val sunday = LocalDate.parse("2026-11-15"); val monday = sunday.plusDays(1)
        val thisWeek = ArenaLayouts.weekly(names, sunday)
        assertEquals(thisWeek, ArenaLayouts.weekly(names, sunday.minusDays(6)))
        val next = ArenaLayouts.weekly(names, monday)
        assertNotEquals(thisWeek, next)
        assertEquals(listOf("a", "b", "c").let { it[(it.indexOf(thisWeek) + 1) % 3] }, next)
        assertNull(ArenaLayouts.weekly(emptyList(), monday))
    }

    @Test fun `random rotation never repeats the last layout when there is a choice`() {
        repeat(50) { seed -> assertNotEquals("a", ArenaLayouts.random(listOf("a", "b", "c"), "a", Random(seed))) }
        assertEquals("only", ArenaLayouts.random(listOf("only"), "only"))
        assertNull(ArenaLayouts.random(emptyList(), null))
    }

    @Test fun `a schematic overlays the base with its copy point on the anchor, keeping only changes`() {
        // A 3 x 1 x 1 base of stone at x 10..12, y 80, z 5.
        val base = BaseSnapshot("world", 10, 80, 5, 12, 80, 5, listOf("minecraft:stone"), intArrayOf(0, 0, 0))
        val placed = listOf(PlacedBlock(-1, 0, 0, "minecraft:stone"), PlacedBlock(0, 0, 0, "minecraft:gold_block"), PlacedBlock(1, 0, 0, "minecraft:air"))
        val (layout, outside) = ArenaLayouts.overlay(base, "hill", Triple(11, 80, 5), placed)
        assertEquals(0, outside)
        assertEquals(listOf(BuildBlock(11, 80, 5, "minecraft:stone", "minecraft:gold_block"), BuildBlock(12, 80, 5, "minecraft:stone", "minecraft:air")), layout!!.blocks)
        val (none, out) = ArenaLayouts.overlay(base, "hill", Triple(12, 80, 5), placed)
        assertNull(none); assertEquals(1, out)
    }

    @Test fun `an old single build becomes the default layout`() {
        ArenaBuildStore(folder).saveLayout(build("summit", "minecraft:stone"), "tmp")
        // Recreate the pre-layout file for another arena by hand.
        val legacy = File(folder, "layouts/summit/tmp.dat").copyTo(File(folder, "hill.build.dat"))
        assertTrue(legacy.isFile)
        val store = ArenaBuildStore(folder)
        assertFalse(legacy.exists())
        assertEquals(listOf("default"), store.layouts("hill"))
        assertEquals("default", store.activeLayout("hill"))
        assertEquals(1, store.loadBuild("hill")!!.blocks.size)
    }

    @Test fun `layouts are saved, chosen, renamed, deleted and cleared per arena`() {
        var today = LocalDate.parse("2026-11-16")
        val store = ArenaBuildStore(folder) { today }
        assertFalse(store.hasBuild("summit"))
        store.saveLayout(build("summit", "minecraft:stone"), "keep")
        assertEquals("keep", store.activeLayout("summit"))
        store.saveLayout(build("summit", "minecraft:gold_block", "minecraft:gold_block"), "temple")
        assertEquals("keep", store.activeLayout("summit"), "a new layout doesn't take over the active one")
        store.setActive("summit", "temple")
        assertEquals(2, store.loadBuild("summit")!!.blocks.size)
        // Saving without a name records over the active layout.
        store.saveBuild(build("summit", "minecraft:diamond_block"))
        assertEquals(1, store.loadLayout("summit", "temple")!!.blocks.size)

        store.setRotation("summit", LayoutRotation.WEEKLY)
        val first = store.activeLayout("summit"); today = today.plusWeeks(1)
        assertNotEquals(first, store.activeLayout("summit"))
        store.setRotation("summit", LayoutRotation.RANDOM)
        store.setActive("summit", "keep"); store.advanceForKoth("summit", Random(1))
        assertEquals("temple", store.activeLayout("summit"))

        store.renameLayout("summit", "temple", "sunken-temple")
        assertEquals(listOf("keep", "sunken-temple"), store.layouts("summit"))
        assertEquals("sunken-temple", store.activeLayout("summit"))
        store.deleteLayout("summit", "keep")
        assertEquals(listOf("sunken-temple"), store.layouts("summit"))
        assertTrue(store.layouts("hill").isEmpty())
        store.clear("summit")
        assertFalse(store.hasBuild("summit")); assertTrue(store.layouts("summit").isEmpty())
        assertTrue(folder.walkTopDown().none { it.name.endsWith(".tmp") })
    }
}
