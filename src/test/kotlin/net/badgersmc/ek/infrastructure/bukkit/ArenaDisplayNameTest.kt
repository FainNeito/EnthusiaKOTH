package net.badgersmc.ek.infrastructure.bukkit

import org.bukkit.configuration.file.YamlConfiguration
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ArenaDisplayNameTest {
    @Test fun `loaded runtime arenas use names while keeping capture zones keyed by ID`() {
        io.mockk.mockkStatic(org.bukkit.Bukkit::class)
        try {
            val yaml = YamlConfiguration().apply {
                set("arenas.capture.display-name", "Crimson Summit")
                set("arenas.score.display-name", "Crimson Summit")
                set("arenas.moving.world", "world")
            }
            val plugin = io.mockk.mockk<org.bukkit.plugin.java.JavaPlugin>(relaxed = true)
            io.mockk.every { plugin.config } returns yaml
            io.mockk.every { org.bukkit.Bukkit.getWorld("world") } returns io.mockk.mockk(relaxed = true)
            val arenas = ConfigLoader(plugin).loadArenas()
            assertEquals("Crimson Summit", arenas.getValue("capture").name)
            assertEquals("Crimson Summit", arenas.getValue("score").name)
            assertEquals("moving", arenas.getValue("moving").name)
            assertEquals("capture", arenas.getValue("capture").id)
            assertEquals("capture", arenas.getValue("capture").zone.id)
            assertEquals("score", arenas.getValue("score").id)
        } finally { io.mockk.unmockkAll() }
    }
    @Test fun `configured visible name is loaded without changing arena keys`() {
        val yaml = YamlConfiguration().apply {
            set("arenas.capture.display-name", "  Crimson Summit  ")
            set("arenas.capture.schedule", listOf("18:00"))
            set("arenas.capture.rewards", listOf("announce {KOTH}"))
        }
        val arenas = ArenaConfigLoader.load(yaml)
        assertEquals(setOf("capture"), arenas.keys)
        assertEquals("Crimson Summit", arenas.getValue("capture").displayName)
        assertEquals(listOf("18:00"), arenas.getValue("capture").schedule)
        assertEquals(listOf("announce {KOTH}"), arenas.getValue("capture").rewards)
    }
}
