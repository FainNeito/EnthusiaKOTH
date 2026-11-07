package net.badgersmc.ek

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PluginCompatibilityTest {
    @Test
    fun `LumaGuilds compatibility floor matches getGuildMemberIds release`() {
        assertFalse(EnthusiaKothPlugin.isVersionAtLeast("2.1.0", EnthusiaKothPlugin.MIN_LUMAGUILDS_VERSION))
        assertFalse(EnthusiaKothPlugin.isVersionAtLeast("2.1.7", EnthusiaKothPlugin.MIN_LUMAGUILDS_VERSION))
        assertTrue(EnthusiaKothPlugin.isVersionAtLeast("2.1.8", EnthusiaKothPlugin.MIN_LUMAGUILDS_VERSION))
        assertTrue(EnthusiaKothPlugin.isVersionAtLeast("2.1.8-SNAPSHOT", EnthusiaKothPlugin.MIN_LUMAGUILDS_VERSION))
        assertTrue(EnthusiaKothPlugin.isVersionAtLeast("2.1.12", EnthusiaKothPlugin.MIN_LUMAGUILDS_VERSION))
        assertTrue(EnthusiaKothPlugin.isVersionAtLeast("2.2.0", EnthusiaKothPlugin.MIN_LUMAGUILDS_VERSION))
    }

    @Test
    fun `LumaGuilds must be enabled as well as new enough`() {
        assertNull(EnthusiaKothPlugin.lumaGuildsStartupProblem("3.0.40", enabled = true))

        val disabled = checkNotNull(EnthusiaKothPlugin.lumaGuildsStartupProblem("3.0.40", enabled = false))
        assertTrue(disabled.contains("installed but not enabled"))
        assertTrue(disabled.contains("3.0.40"))

        val missing = checkNotNull(EnthusiaKothPlugin.lumaGuildsStartupProblem(null, enabled = false))
        assertTrue(missing.contains("no loaded LumaGuilds plugin"))
        assertFalse(missing.contains("installed but not enabled"))

        val tooOld = checkNotNull(EnthusiaKothPlugin.lumaGuildsStartupProblem("2.1.7", enabled = true))
        assertTrue(tooOld.contains(">= ${EnthusiaKothPlugin.MIN_LUMAGUILDS_VERSION}"))
        assertTrue(tooOld.contains("found 2.1.7"))
    }

    @Test
    fun `PlaceholderAPI is declared required when its expansion is linked directly`() {
        val descriptor = checkNotNull(javaClass.classLoader.getResource("paper-plugin.yml"))
            .readText()
        val placeholderBlock = descriptor
            .substringAfter("PlaceholderAPI:")
            .substringBefore("Vault:")

        assertTrue(placeholderBlock.contains("required: true"))
    }
}
