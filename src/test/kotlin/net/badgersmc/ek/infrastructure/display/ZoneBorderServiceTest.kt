package net.badgersmc.ek.infrastructure.display

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ZoneBorderServiceTest {
    @Test fun `outline points trace the square's edges every half block`() {
        val points = ZoneBorderService.squarePoints(-4.5, -4.5, 5.5, 5.5, 80.1)
        assertEquals(80, points.size)
        assertTrue(points.all { (x, y, z) ->
            y == 80.1 && x in -4.5..5.5 && z in -4.5..5.5 && (x == -4.5 || x == 5.5 || z == -4.5 || z == 5.5)
        })
        assertTrue(listOf(-4.5 to -4.5, 5.5 to -4.5, 5.5 to 5.5, -4.5 to 5.5).all { (cx, cz) -> points.any { it.first == cx && it.third == cz } })
    }

    @Test fun `large zones spread their points to stay under the cap`() {
        val points = ZoneBorderService.squarePoints(0.0, 0.0, 256.0, 256.0, 0.0)
        assertTrue(points.size <= ZoneBorderService.MAX_POINTS)
        assertTrue(points.size >= ZoneBorderService.MAX_POINTS - 4)
    }
}
