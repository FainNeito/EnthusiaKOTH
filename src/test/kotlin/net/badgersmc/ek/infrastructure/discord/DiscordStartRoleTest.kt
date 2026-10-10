package net.badgersmc.ek.infrastructure.discord

import com.google.gson.JsonParser
import io.mockk.mockk
import net.badgersmc.ek.infrastructure.bukkit.DiscordConfigLoader
import net.badgersmc.ek.infrastructure.lumaguilds.LumaGuildsAdapter
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.plugin.java.JavaPlugin
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class DiscordStartRoleTest {
    private val role = "1428541718856204309"
    @Test fun `role configuration is optional and rejects malformed IDs`() {
        val yaml = YamlConfiguration()
        assertEquals("", DiscordConfigLoader.load(yaml).startRoleId)
        yaml.set("discord.start-role-id", role)
        assertEquals(role, DiscordConfigLoader.load(yaml).startRoleId)
        listOf<Any>("@everyone", "<@&$role>", "123", listOf(role), 123L).forEach {
            yaml.set("discord.start-role-id", it)
            val error = assertThrows(IllegalArgumentException::class.java) { DiscordConfigLoader.load(yaml) }
            assertTrue(error.message!!.contains("discord.start-role-id"))
        }
    }
    @Test fun `only start announcements allow the configured role including after config changes`() {
        val payloads = LinkedBlockingQueue<String>()
        val transport = WebhookTransport { _, payload -> payloads.add(payload); WebhookResponse(204) }
        var configuredRole = role
        val service = DiscordWebhookService(mockk<JavaPlugin>(relaxed = true), { "https://example.invalid" }, { true },
            mockk<LumaGuildsAdapter>(relaxed = true), transport, startRoleId = { configuredRole })
        fun next(ping: Boolean) {
            val payload = JsonParser.parseString(payloads.poll(3, TimeUnit.SECONDS) ?: fail("No payload")).asJsonObject
            val mentions = payload.getAsJsonObject("allowed_mentions")
            assertTrue(mentions.getAsJsonArray("parse").isEmpty)
            if (ping) {
                assertEquals("<@&$role>", payload["content"].asString)
                assertEquals(listOf(role), mentions.getAsJsonArray("roles").map { it.asString })
            } else {
                assertFalse(payload.has("content")); assertFalse(mentions.has("roles"))
            }
        }
        try {
            service.sendStart(UUID.randomUUID(), "@everyone <@&999999999999999999>", "Hill"); next(true)
            service.sendPreStart("Hill", 10); next(false)
            service.sendLiveUpdate(UUID.randomUUID(), "Hill", null, false, "15m"); next(false)
            service.sendCancelled(UUID.randomUUID(), "Hill", "Staff"); next(false)
            service.sendNoWinner(UUID.randomUUID(), "Hill"); next(false)
            service.sendCapture(UUID.randomUUID(), "Hill", net.badgersmc.ek.domain.TeamId(net.badgersmc.ek.domain.TeamMode.GUILD, UUID.randomUUID()), false); next(false)
            configuredRole = ""
            service.sendStart("Hill", "Here"); next(false)
        } finally { service.shutdown() }
    }
}
