package net.badgersmc.ek.infrastructure.bukkit

import org.bukkit.configuration.file.YamlConfiguration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ChancedRewardsConfigTest {
    private fun chances(yaml: String) = ArenaConfigLoader.load(YamlConfiguration().apply { loadFromString(yaml) })
        .getValue("hill").chancedRewards

    @Test fun `a chance with a decimal point keeps its command`() {
        val loaded = chances("""
            arenas:
              hill:
                chanced-rewards:
                  "20.0": "bank 50"
                  "12.5": "bank 10"
                  "5": "bank 5"
                  "nonsense": "bank 1"
        """.trimIndent())
        assertEquals(mapOf("bank 50" to 20.0, "bank 10" to 12.5, "bank 5" to 5.0), loaded)
    }

    @Test fun `the list form still loads`() {
        val loaded = chances("""
            arenas:
              hill:
                chanced-rewards:
                  - { command: "bank 50", chance: 20.0 }
        """.trimIndent())
        assertEquals(mapOf("bank 50" to 20.0), loaded)
    }
}
