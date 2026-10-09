package net.badgersmc.ek.infrastructure.bukkit

import net.badgersmc.ek.config.PositionConfig
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SelectionOutlineTest {
    @Test fun `one clicked block includes all eight exterior vertices`() {
        val point = PositionConfig(-2.0, 64.0, 3.0)
        val outline = SelectionOutline.selectedBlocks(point, point)
        assertEquals(8, outline.size)
        assertTrue(PositionConfig(-1.0, 65.0, 4.0) in outline)
        assertTrue(point in outline)
    }

    @Test fun `reversed selection includes upper blocks and lies only on edges`() {
        val a = PositionConfig(10.0, 70.0, 20.0); val b = PositionConfig(-3.0, 60.0, -4.0)
        val outline = SelectionOutline.selectedBlocks(a, b)
        assertEquals(outline.toSet(), SelectionOutline.selectedBlocks(b, a).toSet())
        assertTrue(PositionConfig(11.0, 71.0, 21.0) in outline)
        outline.forEach { p ->
            assertTrue(p.x in -3.0..11.0 && p.y in 60.0..71.0 && p.z in -4.0..21.0)
            assertTrue(listOf(p.x == -3.0 || p.x == 11.0, p.y == 60.0 || p.y == 71.0,
                p.z == -4.0 || p.z == 21.0).count { it } >= 2)
        }
    }

    @Test fun `large and full height selections remain bounded and unique`() {
        val outline = SelectionOutline.points(PositionConfig(-30_000_000.0, -64.0, -30_000_000.0),
            PositionConfig(30_000_000.0, 320.0, 30_000_000.0))
        assertTrue(outline.size <= 200)
        assertEquals(outline.size, outline.toSet().size)
        assertTrue(outline.all { it.x.isFinite() && it.y.isFinite() && it.z.isFinite() })
    }

    @Test fun `stored upper bound is not expanded again and invalid geometry is omitted`() {
        val outline = SelectionOutline.points(PositionConfig(0.0, 0.0, 0.0), PositionConfig(2.0, 2.0, 2.0))
        assertEquals(2.0, outline.maxOf { it.x })
        assertTrue(SelectionOutline.points(PositionConfig(Double.NaN, 0.0, 0.0), PositionConfig()).isEmpty())
    }
}
