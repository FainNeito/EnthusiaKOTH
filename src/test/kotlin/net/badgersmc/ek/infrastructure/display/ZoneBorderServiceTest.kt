package net.badgersmc.ek.infrastructure.display

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.hypot

class ZoneBorderServiceTest {
    @Test fun `ring points sit on the capture radius at the given height`() {
        val points = ZoneBorderService.ringPoints(10.0, 80.15, -4.0, 5.0)
        assertEquals(21, points.size)
        points.forEach { (x, y, z) ->
            assertEquals(5.0, hypot(x - 10.0, z + 4.0), 1e-9)
            assertEquals(80.15, y)
        }
    }

    @Test fun `ring density stays between twelve and forty-eight points`() {
        assertEquals(12, ZoneBorderService.ringPoints(0.0, 0.0, 0.0, 0.1).size)
        assertEquals(48, ZoneBorderService.ringPoints(0.0, 0.0, 0.0, 256.0).size)
        assertTrue(ZoneBorderService.ringPoints(0.0, 0.0, 0.0, 0.1).all { (x, _, z) -> hypot(x, z) >= 0.5 - 1e-9 })
    }
}
