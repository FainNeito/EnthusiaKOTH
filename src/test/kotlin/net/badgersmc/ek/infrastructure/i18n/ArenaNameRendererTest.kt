package net.badgersmc.ek.infrastructure.i18n

import net.badgersmc.nexus.i18n.LangHost
import net.badgersmc.nexus.i18n.LangService
import net.badgersmc.nexus.i18n.Locale
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.TextComponent
import net.kyori.adventure.text.format.Style
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class ArenaNameRendererTest {
    @TempDir lateinit var directory: Path
    private val plain = PlainTextComponentSerializer.plainText()
    private fun lang(): LangService {
        val host = object : LangHost {
            override val dataFolder = directory.toFile()
            override val resourceClassLoader = KothLang::class.java.classLoader
        }
        return LangService(host, Locale("en_US"), KothLang::class.java).also { it.reload() }
    }
    private fun leaves(component: Component, parent: Style = Style.empty()): List<Pair<String, Style>> {
        val inherited = component.style().merge(parent, Style.Merge.Strategy.IF_ABSENT_ON_TARGET)
        return (if (component is TextComponent && component.content().isNotEmpty()) listOf(component.content() to inherited) else emptyList()) +
            component.children().flatMap { leaves(it, inherited) }
    }
    @Test fun `both RGB syntaxes and legacy decorations retain emojis`() {
        for (raw in listOf("&#12AbEF&lSummit 🧑🏽‍🚀", "&x&1&2&a&b&e&f&lSummit 🧑🏽‍🚀", "§x§1§2§a§b§e§f§lSummit 🧑🏽‍🚀")) {
            val value = raw.arenaComponent()
            assertEquals("Summit 🧑🏽‍🚀", plain.serialize(value))
            val style = leaves(value).single().second
            assertEquals(TextColor.color(0x12ABEF), style.color())
            assertEquals(TextDecoration.State.TRUE, style.decoration(TextDecoration.BOLD))
        }
        assertEquals("Crête & Summit 🏆", plain.serialize("Crête & Summit 🏆".arenaComponent()))
    }
    @Test fun `actual Nexus templates preserve prefix and scope arena styling`() {
        val service = lang()
        service.registerGlobalResolver("entered", Placeholder.parsed("entered", "<aqua>FainNoir"))
        val result = service.arenaMsg("koth.enter", "koth_name" to "&#12ABEF&lSummit 🏆".arenaComponent(), "captime" to "3m")
        assertTrue(plain.serialize(result).contains("FainNoir entered the Summit 🏆 KOTH! Cap in 3m!"))
        assertTrue(plain.serialize(result).contains("Koth"))
        val rendered = leaves(result)
        val name = rendered.first { it.first.contains("Summit") }.second
        val after = rendered.first { it.first.contains("KOTH!") }.second
        assertEquals(TextColor.color(0x12ABEF), name.color())
        assertNotEquals(name.color(), after.color())
        assertNotEquals(TextDecoration.State.TRUE, after.decoration(TextDecoration.BOLD))
    }
    @Test fun `default bossbar inserts components without debug text and retains time style`() {
        val service = lang()
        val result = service.arenaMsg("bossbar.format_with_capper", "koth_name" to "&cSummit 🏆".arenaComponent(),
            "capper" to "FainNoir", "contested" to service.msg("bossbar.contested"), "time" to "1m 45s")
        assertTrue(plain.serialize(result).contains("Summit 🏆"))
        assertFalse(plain.serialize(result).contains("TextComponent"))
        val rendered = leaves(result)
        assertEquals(TextColor.color(0xFF5555), rendered.first { it.first.contains("Summit") }.second.color())
        assertEquals(TextColor.color(0xAAAAAA), rendered.first { it.first.contains("1m 45s") }.second.color())
    }
    @Test fun `scheduled warning renders a formatted name using the real language service`() {
        val result = lang().arenaMsg("koth.warning_minutes", "koth_name" to "&#FFAA00Summit 🏆".arenaComponent(), "minutes" to "5")
        assertTrue(plain.serialize(result).contains("Summit 🏆"))
        assertFalse(plain.serialize(result).contains("TextComponent"))
        assertTrue(plain.serialize(result).contains("5 minutes"))
    }
    @Test fun `flare interpolation and scoreboard serialization preserve boundaries`() {
        val result = "&aFlare: {KOTH} / Ready".withArenaName("&#12ABEF&lSummit 🏆")
        assertEquals("Flare: Summit 🏆 / Ready", plain.serialize(result))
        val rendered = leaves(result)
        assertEquals(TextColor.color(0x12ABEF), rendered.first { it.first.contains("Summit") }.second.color())
        assertEquals(TextColor.color(0x55FF55), rendered.first { it.first.contains("Ready") }.second.color())
        assertTrue("&#12ABEFHill".arenaLegacyText().startsWith("§x§1§2§a§b§e§f"))
    }
}
