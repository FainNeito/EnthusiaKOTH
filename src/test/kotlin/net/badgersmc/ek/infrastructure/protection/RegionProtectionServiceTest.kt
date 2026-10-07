package net.badgersmc.ek.infrastructure.protection

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.badgersmc.ek.domain.CaptureZone
import net.badgersmc.ek.domain.KothArena
import org.bukkit.Location
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RegionProtectionServiceTest {
    @Test
    fun `bound WorldGuard region is authoritative`() {
        val location = mockk<Location>()
        val zone = mockk<CaptureZone>()
        val legacy = mockk<CaptureZone>()
        every { zone.worldName } returns "world"
        val arena = arena(zone, legacy, "koth")

        val service = RegionProtectionService(
            arenas = { mapOf("capture" to arena) },
            worldGuardContains = { world, region, queried ->
                world == "world" && region == "koth" && queried === location
            },
        )

        assertTrue(service.isProtected(location))
        verify(exactly = 0) { zone.contains(any()) }
        verify(exactly = 0) { legacy.contains(any()) }
    }

    @Test
    fun `legacy protected cuboid remains fallback when no WorldGuard region is bound`() {
        val location = mockk<Location>()
        val zone = mockk<CaptureZone>()
        val legacy = mockk<CaptureZone>()
        every { zone.contains(location) } returns false
        every { legacy.contains(location) } returns true
        val arena = arena(zone, legacy, null)

        val service = RegionProtectionService(arenas = { mapOf("capture" to arena) })

        assertTrue(service.isProtected(location))
    }

    private fun arena(zone: CaptureZone, legacy: CaptureZone, worldGuardRegion: String?) = KothArena(
        id = "capture",
        family = "capture",
        zone = zone,
        protectedRegion = legacy,
        worldGuardRegion = worldGuardRegion,
        durationSeconds = 60,
        captureSeconds = 15,
    )
}
