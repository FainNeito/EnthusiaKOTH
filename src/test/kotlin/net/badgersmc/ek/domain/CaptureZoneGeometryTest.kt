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
    fun `square capture includes the corners its outline draws`() {
        assertTrue(zone.contains(Location(world, 4.9, 80.0, 4.9)))
        assertTrue(zone.contains(Location(world, -5.0, 80.0, 5.0)))
    }

    @Test
    fun `square capture excludes anything past an edge`() {
        assertFalse(zone.contains(Location(world, 5.1, 80.0, 0.0)))
        assertFalse(zone.contains(Location(world, 0.0, 80.0, -5.1)))
    }

    @Test
    fun `square capture respects vertical bounds`() {
        assertFalse(zone.contains(Location(world, 0.0, 90.0, 0.0)))
    }
}
