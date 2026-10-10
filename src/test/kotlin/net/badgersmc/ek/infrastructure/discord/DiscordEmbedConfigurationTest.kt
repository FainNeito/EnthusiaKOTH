package net.badgersmc.ek.infrastructure.discord

import com.google.gson.JsonParser
import net.badgersmc.ek.config.*
import net.badgersmc.ek.infrastructure.bukkit.DiscordConfigLoader
import org.bukkit.configuration.file.YamlConfiguration
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.File

class DiscordEmbedConfigurationTest {
    @Test fun `old config gets all defaults and shipped config matches defaults`() {
        assertEquals(DiscordEmbedDefaults.templates, DiscordConfigLoader.load(YamlConfiguration()).embeds)
        val cfg = DiscordConfigLoader.load(YamlConfiguration.loadConfiguration(File("src/main/resources/config.yml")))
        assertEquals(DiscordEmbedDefaults.templates, cfg.embeds)
        assertFalse(cfg.enabled)
    }
    @Test fun `custom fields color and toggles load while missing keys retain defaults`() {
        val yaml = YamlConfiguration(); yaml.loadFromString("""
            discord:
              embeds:
                live:
                  title: "Live: {arena}"
                  description: "{capper} — {status} 🏆"
                  color: "#123abc"
                  timestamp: false
                  fields: []
                cancelled:
                  enabled: false
        """.trimIndent())
        val cfg = DiscordConfigLoader.load(yaml)
        val live = cfg.embeds.getValue(DiscordMessageType.LIVE)
        assertEquals(0x123ABC, live.color); assertFalse(live.timestamp); assertTrue(live.fields.isEmpty())
        assertFalse(cfg.embeds.getValue(DiscordMessageType.CANCELLED).enabled)
        val payload = JsonParser.parseString(DiscordEmbedRenderer.render(live, mapOf("arena" to "Hill 🏆", "capper" to "Guild", "status" to "Contested"))).asJsonObject
        val embed = payload.getAsJsonArray("embeds")[0].asJsonObject
        assertEquals("Live: Hill 🏆", embed["title"].asString); assertFalse(embed.has("timestamp"))
        assertTrue(payload.getAsJsonObject("allowed_mentions").getAsJsonArray("parse").isEmpty)
    }
    @Test fun `invalid colors and field structures fail with exact configuration paths`() {
        listOf("discord.embeds.start.color" to "red", "discord.embeds.live.fields" to "bad",
            "discord.embeds.live.enabled" to "yes", "discord.embeds.start.title" to listOf("bad"),
            "discord.embeds.winner.fields" to listOf(mapOf("name" to "x", "value" to "y", "inline" to "yes"))).forEach { (key, value) ->
            val yaml = YamlConfiguration(); yaml.set(key, value)
            val error = assertThrows(IllegalArgumentException::class.java) { DiscordConfigLoader.load(yaml) }
            assertTrue(error.message!!.contains(key), error.message)
        }
    }
    @Test fun `placeholder values are not recursively expanded and JSON escaping is valid`() {
        val template = DiscordEmbedTemplate(title = "{arena}", description = "{winner}", color = 1)
        val embed = JsonParser.parseString(DiscordEmbedRenderer.render(template, mapOf("arena" to "{winner} \"🏆\"", "winner" to "Actual")))
            .asJsonObject.getAsJsonArray("embeds")[0].asJsonObject
        assertEquals("{winner} \"🏆\"", embed["title"].asString)
    }
    @Test fun `expanded output respects individual and aggregate Discord limits without split surrogates`() {
        val template = DiscordEmbedTemplate(title = "{arena}", description = "{arena}", color = 1,
            fields = List(25) { DiscordEmbedField("{arena}", "{arena}") })
        val embed = JsonParser.parseString(DiscordEmbedRenderer.render(template, mapOf("arena" to "🏆".repeat(5000))))
            .asJsonObject.getAsJsonArray("embeds")[0].asJsonObject
        val title = embed["title"].asString; val description = embed["description"].asString
        assertTrue(title.length <= 256); assertTrue(description.length <= 4096)
        var total = title.length + description.length
        embed.getAsJsonArray("fields").forEach { element ->
            val field = element.asJsonObject; val name = field["name"].asString; val value = field["value"].asString
            assertTrue(name.length in 1..256); assertTrue(value.length in 1..1024)
            assertFalse(name.last().isHighSurrogate()); assertFalse(value.last().isHighSurrogate())
            total += name.length + value.length
        }
        assertTrue(total <= 6000)
    }
}
