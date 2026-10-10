package net.badgersmc.ek.infrastructure.discord

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList

class HttpWebhookTransportTest {
    @Test fun `HTTP create waits for message and PATCH preserves thread destination and UTF8`() {
        val calls = CopyOnWriteArrayList<Triple<String, String, String>>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/webhooks/1/test") { exchange ->
            calls.add(Triple(exchange.requestMethod, exchange.requestURI.toString(), exchange.requestBody.readAllBytes().toString(Charsets.UTF_8)))
            val body = """{"author":{"id":"999"},"id":"123"}""".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong()); exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val t = HttpWebhookTransport(); val url = "http://127.0.0.1:${server.address.port}/api/webhooks/1/test?thread_id=456&wait=false"
            val created = t.create(url, """{"embeds":[{"title":"Summit 🏆"}]}""")
            assertEquals("123", created.messageId)
            t.edit(url, created.messageId!!, "{}")
            assertEquals("POST", calls[0].first); assertEquals("/api/webhooks/1/test?thread_id=456&wait=true", calls[0].second)
            assertTrue(calls[0].third.contains("Summit 🏆"))
            assertEquals("PATCH", calls[1].first); assertEquals("/api/webhooks/1/test/messages/123?thread_id=456", calls[1].second)
        } finally { server.stop(0) }
    }
    @Test fun `body-only rate limit retry delay is parsed`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/hook") { exchange ->
            val body = """{"retry_after":1.5}""".toByteArray()
            exchange.sendResponseHeaders(429, body.size.toLong()); exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val response = HttpWebhookTransport().create("http://127.0.0.1:${server.address.port}/hook", "{}")
            assertEquals(429, response.statusCode); assertEquals(Duration.ofMillis(1500), response.retryAfter)
        } finally { server.stop(0) }
    }
}
