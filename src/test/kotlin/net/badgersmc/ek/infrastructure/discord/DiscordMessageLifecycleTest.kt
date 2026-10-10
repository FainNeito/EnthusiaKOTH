package net.badgersmc.ek.infrastructure.discord

import io.mockk.every
import io.mockk.mockk
import net.badgersmc.ek.config.*
import net.badgersmc.ek.domain.TeamId
import net.badgersmc.ek.domain.TeamMode
import net.badgersmc.ek.infrastructure.lumaguilds.LumaGuildsAdapter
import org.bukkit.plugin.java.JavaPlugin
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class DiscordMessageLifecycleTest {
    private data class Call(val method: String, val url: String, val id: String?, val payload: String)
    private class Transport : WebhookTransport {
        val calls = CopyOnWriteArrayList<Call>()
        var createResult: () -> WebhookResponse = { WebhookResponse(200, messageId = (100 + calls.size).toString()) }
        var editResult: () -> WebhookResponse = { WebhookResponse(200) }
        override fun send(url: String, payload: String): WebhookResponse { calls.add(Call("POST", url, null, payload)); return WebhookResponse(204) }
        override fun create(url: String, payload: String): WebhookResponse { calls.add(Call("CREATE", url, null, payload)); return createResult() }
        override fun edit(url: String, messageId: String, payload: String): WebhookResponse { calls.add(Call("PATCH", url, messageId, payload)); return editResult() }
    }
    private fun service(t: Transport, url: () -> String = { "https://example.invalid/hook" },
        templates: () -> Map<DiscordMessageType, DiscordEmbedTemplate> = { DiscordEmbedDefaults.templates }): DiscordWebhookService {
        val guilds = mockk<LumaGuildsAdapter>(relaxed = true)
        every { guilds.guildName(any()) } returns "Enthusiast"
        return DiscordWebhookService(mockk<JavaPlugin>(relaxed = true), url, { true }, guilds, t, templates = templates)
    }
    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(5)
        assertTrue(condition(), "Timed out waiting for dispatcher")
    }
    @Test fun `one creation then repeated edits use same message and finish once`() {
        val t = Transport(); val s = service(t); val id = UUID.randomUUID()
        try {
            s.sendLiveUpdate(id, "Summit 🏆", null, false, "15m"); await { t.calls.size == 1 }
            s.sendLiveUpdate(id, "Summit 🏆", null, true, "14m"); await { t.calls.size == 2 }
            s.sendLiveUpdate(id, "Summit 🏆", null, false, "13m"); await { t.calls.size == 3 }
            s.sendCapture(id, "Summit 🏆", TeamId(TeamMode.GUILD, UUID.randomUUID()), true)
            await { t.calls.size == 5 && s.liveMessageCount() == 0 }
            assertEquals(listOf("CREATE", "PATCH", "PATCH", "PATCH", "POST"), t.calls.map { it.method })
            assertEquals(1, t.calls.filter { it.method == "PATCH" }.map { it.id }.distinct().size)
            assertTrue(t.calls.last().payload.contains("Enthusiast"))
        } finally { s.shutdown() }
    }
    @Test fun `duplicate names in concurrent events have independent message IDs`() {
        val t = Transport(); val s = service(t); val a = UUID.randomUUID(); val b = UUID.randomUUID()
        try {
            s.sendLiveUpdate(a, "Same", null, false, "15m"); await { t.calls.size == 1 }
            s.sendLiveUpdate(b, "Same", null, false, "15m"); await { t.calls.size == 2 }
            s.sendLiveUpdate(a, "Same", null, false, "14m"); await { t.calls.size == 3 }
            s.sendLiveUpdate(b, "Same", null, false, "14m"); await { t.calls.size == 4 }
            assertNotEquals(t.calls[2].id, t.calls[3].id)
            s.sendNoWinner(a, "Same"); await { s.liveMessageCount() == 1 }
            s.sendLiveUpdate(b, "Same", null, false, "13m"); await { t.calls.size == 7 }
            assertEquals(t.calls[3].id, t.calls.last().id)
        } finally { s.shutdown() }
    }
    @Test fun `terminal state wins over queued update during in-flight create`() {
        val t = Transport(); val started = CountDownLatch(1); val release = CountDownLatch(1)
        t.createResult = { started.countDown(); release.await(2, TimeUnit.SECONDS); WebhookResponse(200, messageId = "123") }
        val s = service(t); val id = UUID.randomUUID()
        try {
            s.sendLiveUpdate(id, "Hill", null, false, "15m"); assertTrue(started.await(2, TimeUnit.SECONDS))
            s.sendLiveUpdate(id, "Hill", null, true, "14m")
            s.sendCancelled(id, "Hill", "Stopped by staff"); release.countDown()
            await { s.liveMessageCount() == 0 && t.calls.size == 3 }
            assertEquals(listOf("CREATE", "PATCH", "POST"), t.calls.map { it.method })
            assertTrue(t.calls[1].payload.contains("Stopped by staff"))
        } finally { release.countDown(); s.shutdown() }
    }
    @Test fun `uncertain create is not retried or recreated on next tick`() {
        val t = Transport(); t.createResult = { throw IOException("https://example.invalid/SECRET") }
        val s = service(t); val id = UUID.randomUUID()
        try {
            s.sendLiveUpdate(id, "Hill", null, false, "15m"); await { t.calls.size == 1 }
            s.sendNoWinner(id, "Hill"); await { s.liveMessageCount() == 0 && t.calls.size == 2 }
            assertEquals(listOf("CREATE", "POST"), t.calls.map { it.method })
        } finally { s.shutdown() }
    }
    @Test fun `deleted status is not recreated and terminal announcement still works`() {
        val t = Transport(); val s = service(t); val id = UUID.randomUUID()
        try {
            s.sendLiveUpdate(id, "Hill", null, false, "15m"); await { t.calls.size == 1 }
            t.editResult = { WebhookResponse(404) }
            s.sendLiveUpdate(id, "Hill", null, true, "14m"); await { t.calls.size == 2 }
            s.sendCancelled(id, "Hill", "Stopped by staff"); await { s.liveMessageCount() == 0 && t.calls.size == 3 }
            assertEquals(listOf("CREATE", "PATCH", "POST"), t.calls.map { it.method })
        } finally { s.shutdown() }
    }
    @Test fun `changing destination never patches old message ID at new webhook`() {
        val t = Transport(); var url = "https://example.invalid/one"; val s = service(t, { url }); val id = UUID.randomUUID()
        try {
            s.sendLiveUpdate(id, "Hill", null, false, "15m"); await { t.calls.size == 1 }
            url = "https://example.invalid/two"
            s.sendLiveUpdate(id, "Hill", null, true, "14m")
            s.sendNoWinner(id, "Hill"); await { s.liveMessageCount() == 0 && t.calls.size == 2 }
            assertEquals("POST", t.calls.last().method); assertEquals(url, t.calls.last().url)
        } finally { s.shutdown() }
    }
    @Test fun `disabled terminal announcement still finalizes existing status`() {
        val t = Transport(); val templates = DiscordEmbedDefaults.templates.toMutableMap()
        templates[DiscordMessageType.CANCELLED] = templates.getValue(DiscordMessageType.CANCELLED).copy(enabled = false)
        val s = service(t, templates = { templates }); val id = UUID.randomUUID()
        try {
            s.sendLiveUpdate(id, "Hill", null, false, "15m"); await { t.calls.size == 1 }
            s.sendCancelled(id, "Hill", "Stopped by staff"); await { s.liveMessageCount() == 0 && t.calls.size == 2 }
            assertEquals("PATCH", t.calls.last().method)
        } finally { s.shutdown() }
    }
    @Test fun `buffer coalesces live updates per event and terminal removes only its own`() {
        val q = DiscordDeliveryBuffer(4); val a = UUID.randomUUID(); val b = UUID.randomUUID()
        q.offer(WebhookDelivery("a", WebhookDeliveryKind.LIVE_UPDATE, eventId = a))
        q.offer(WebhookDelivery("b", WebhookDeliveryKind.LIVE_UPDATE, eventId = b))
        q.offer(WebhookDelivery("new-a", WebhookDeliveryKind.LIVE_UPDATE, eventId = a))
        assertEquals(2, q.size())
        q.offer(WebhookDelivery("end-a", WebhookDeliveryKind.IMPORTANT, eventId = a, terminal = true))
        assertEquals("b", q.poll()!!.payload); assertEquals("end-a", q.poll()!!.payload)
    }
    @Test fun `rate limited create retries once before subsequent updates PATCH`() {
        val t = Transport(); t.createResult = {
            if (t.calls.count { it.method == "CREATE" } == 1) WebhookResponse(429, java.time.Duration.ofMillis(250))
            else WebhookResponse(200, messageId = "123")
        }
        val s = service(t); val id = UUID.randomUUID()
        try {
            s.sendLiveUpdate(id, "Hill", null, false, "15m"); await { t.calls.size == 2 }
            s.sendLiveUpdate(id, "Hill", null, false, "14m"); await { t.calls.size == 3 }
            assertEquals(listOf("CREATE", "CREATE", "PATCH"), t.calls.map { it.method })
            assertEquals("123", t.calls.last().id)
        } finally { s.shutdown() }
    }
}
