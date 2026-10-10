package net.badgersmc.ek.application

import net.badgersmc.ek.domain.ArenaName
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ArenaNameFormattingPolicyTest {
    @Test fun `legacy RGB and joined emoji names are accepted without rewriting saved text`() {
        for (name in listOf("&c&lCrimson &rSummit 🏆", "§aSummit", "&#12AbEFHill 🧑🏽‍🚀", "&x&1&2&a&b&e&fHill", "§x§1§2§a§b§e§fHill", "🏳️‍🌈 🇺🇸")) {
            assertEquals(name, ArenaName.parse("  $name  "))
        }
    }
    @Test fun `formatting does not consume the visible length budget`() {
        assertNotNull(ArenaName.parse("&#abcdef" + "🏆".repeat(64)))
        assertNotNull(ArenaName.parse("&a" + "🧑🏽‍🚀".repeat(64)))
        assertThrows(IllegalArgumentException::class.java) { ArenaName.parse("🏆".repeat(65)) }
    }
    @Test fun `format only malformed RGB bidi and isolated invisible joins fail`() {
        for (name in listOf("&c&l", "&#12345Hill", "&#zzzzzzHill", "&x&1&2Hill", "§zHill", "Hill\u202Etext", "Hill\u200Btext", "Hill\u200D", "<click:run_command:/op>Hill", "&a".repeat(513) + "Hill")) {
            assertThrows(IllegalArgumentException::class.java, { ArenaName.parse(name) }, name)
        }
    }
}
