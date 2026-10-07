package net.badgersmc.ek.domain

import io.mockk.every
import io.mockk.mockk
import org.bukkit.Location
import org.bukkit.World
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CaptureZoneGeometryTest {
    private val world = mockk<World>().also { every { it.name } returns "world" }

    private val zone = CaptureZone(
        id = "capture",
        worldName = "world",
        corner1 = Location(world, -5.0, 75.0, -5.0),
        corner2 = Location(world, 5.0, 85.0, 5.0),
        radius = 5.0,
    )

    @Test
    fun `circular capture includes points on radius`() {
        assertTrue(zone.containsCircular(Location(world, 3.0, 80.0, 4.0)))
    }

    @Test
    fun `circular capture excludes square corner inside old cuboid check`() {
        val diagonal = Location(world, 4.0, 80.0, 4.0)
        assertTrue(zone.contains(diagonal))
        assertFalse(zone.containsCircular(diagonal))
    }

    @Test
    fun `circular capture respects vertical bounds`() {
        assertFalse(zone.containsCircular(Location(world, 0.0, 90.0, 0.0)))
    }
}
