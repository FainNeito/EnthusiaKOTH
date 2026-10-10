package net.badgersmc.ek.infrastructure.discord

import com.google.gson.JsonParser
import net.badgersmc.ek.config.DiscordEmbedDefaults
import net.badgersmc.ek.config.DiscordEmbedTemplate
import net.badgersmc.ek.config.DiscordMessageType
import net.badgersmc.ek.domain.ArenaName
import net.badgersmc.ek.domain.TeamId
import net.badgersmc.ek.domain.TeamMode
import net.badgersmc.ek.infrastructure.lumaguilds.LumaGuildsAdapter
import net.badgersmc.ek.stripColors
import org.bukkit.plugin.java.JavaPlugin
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit

internal enum class WebhookDeliveryKind { LIVE_UPDATE, IMPORTANT }
internal data class WebhookDelivery(
    val payload: String, val kind: WebhookDeliveryKind, val attempts: Int = 0,
    val eventId: UUID? = null, val terminal: Boolean = false, val url: String = "",
    val announce: Boolean = true, val finalized: Boolean = false,
)
internal data class WebhookResponse(val statusCode: Int, val retryAfter: Duration? = null, val messageId: String? = null)

internal fun interface WebhookTransport {
    fun send(url: String, payload: String): WebhookResponse
    fun create(url: String, payload: String): WebhookResponse = send(url, payload)
    fun edit(url: String, messageId: String, payload: String): WebhookResponse = send("$url/messages/$messageId", payload)
}

internal class DiscordDeliveryBuffer(private val capacity: Int) {
    private val deliveries = ArrayDeque<WebhookDelivery>()
    fun removeLive(eventId: UUID?) { deliveries.removeIf { it.kind == WebhookDeliveryKind.LIVE_UPDATE && it.eventId == eventId } }
    fun offer(delivery: WebhookDelivery): Boolean {
        if (delivery.kind == WebhookDeliveryKind.LIVE_UPDATE || delivery.terminal) removeLive(delivery.eventId)
        if (deliveries.size >= capacity) {
            val live = deliveries.firstOrNull { it.kind == WebhookDeliveryKind.LIVE_UPDATE } ?: return false
            deliveries.remove(live)
        }
        deliveries.addLast(delivery)
        return true
    }
    fun retryFirst(delivery: WebhookDelivery): Boolean {
        if (deliveries.size >= capacity) {
            val live = deliveries.lastOrNull { it.kind == WebhookDeliveryKind.LIVE_UPDATE } ?: return false
            deliveries.remove(live)
        }
        deliveries.addFirst(delivery)
        return true
    }
    fun poll(): WebhookDelivery? = deliveries.pollFirst()
    fun clear() = deliveries.clear()
    fun isNotEmpty(): Boolean = deliveries.isNotEmpty()
    fun size(): Int = deliveries.size
    fun snapshotKinds(): List<WebhookDeliveryKind> = deliveries.map { it.kind }
}

internal object DiscordRetryPolicy {
    const val MAX_ATTEMPTS = 4
    fun delayFor(response: WebhookResponse, attempts: Int): Duration? {
        if (attempts >= MAX_ATTEMPTS) return null
        return when {
            response.statusCode == 429 -> response.retryAfter?.coerceIn(Duration.ofMillis(250), Duration.ofMinutes(2)) ?: Duration.ofSeconds(1)
            response.statusCode in 500..599 -> exponentialDelay(attempts)
            else -> null
        }
    }
    fun delayForFailure(attempts: Int): Duration? = if (attempts >= MAX_ATTEMPTS) null else exponentialDelay(attempts)
    private fun exponentialDelay(attempts: Int) = Duration.ofSeconds((1L shl attempts.coerceIn(0, 5)).coerceAtMost(30))
}

internal class HttpWebhookTransport(
    private val client: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(),
) : WebhookTransport {
    override fun send(url: String, payload: String): WebhookResponse = request("POST", url, payload)
    override fun create(url: String, payload: String): WebhookResponse = request("POST", destination(url, wait = true), payload)
    override fun edit(url: String, messageId: String, payload: String): WebhookResponse {
        require(messageId.matches(Regex("[0-9]{1,20}")))
        return request("PATCH", destination(url, messageId), payload)
    }
    private fun destination(url: String, messageId: String? = null, wait: Boolean = false): String {
        val uri = URI.create(url)
        val query = uri.rawQuery?.split('&')?.filterNot { it.substringBefore('=') == "wait" }.orEmpty().toMutableList()
        if (wait) query.add("wait=true")
        return URI(uri.scheme, uri.authority, uri.path.trimEnd('/') + (messageId?.let { "/messages/$it" } ?: ""),
            query.joinToString("&").ifBlank { null }, null).toASCIIString()
    }
    private fun request(method: String, url: String, payload: String): WebhookResponse {
        val request = HttpRequest.newBuilder().uri(URI.create(url)).header("Content-Type", "application/json")
            .method(method, HttpRequest.BodyPublishers.ofString(payload)).timeout(Duration.ofSeconds(5)).build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        val id = if (response.statusCode() in 200..299) runCatching {
            JsonParser.parseString(response.body()).asJsonObject.get("id")?.asString?.takeIf { it.matches(Regex("[0-9]{1,20}")) }
        }.getOrNull() else null
        val seconds = response.headers().firstValue("Retry-After").orElse(null)?.toDoubleOrNull()
            ?: response.headers().firstValue("X-RateLimit-Reset-After").orElse(null)?.toDoubleOrNull()
            ?: runCatching { JsonParser.parseString(response.body()).asJsonObject.get("retry_after")?.asDouble }.getOrNull()
        val retry = seconds?.takeIf { it.isFinite() }?.let { Duration.ofMillis((it.coerceIn(0.0, 120.0) * 1000).toLong()) }
            ?: response.headers().firstValue("Retry-After").orElse(null)?.let { date -> runCatching {
                Duration.between(Instant.now(), ZonedDateTime.parse(date, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()).coerceAtLeast(Duration.ZERO)
            }.getOrNull() }
        return WebhookResponse(response.statusCode(), retry, id)
    }
}

/** Bukkit name resolution/rendering happens on the caller; only HTTP runs on the worker. */
class DiscordWebhookService internal constructor(
    private val plugin: JavaPlugin,
    private val webhookUrl: () -> String,
    private val enabled: () -> Boolean,
    private val guilds: LumaGuildsAdapter,
    private val transport: WebhookTransport = HttpWebhookTransport(),
    queueCapacity: Int = 32,
    private val templates: () -> Map<DiscordMessageType, DiscordEmbedTemplate> = { DiscordEmbedDefaults.templates },
    private val startRoleId: () -> String = { "" },
) {
    private data class LiveMessage(val url: String, @Volatile var messageId: String? = null,
        @Volatile var unavailable: Boolean = false, @Volatile var ended: Boolean = false)
    private val capacity = queueCapacity.coerceAtLeast(1)
    private val lock = Any()
    private val buffer = DiscordDeliveryBuffer(capacity)
    private val messages = mutableMapOf<UUID, LiveMessage>()
    private val executor = ScheduledThreadPoolExecutor(1, ThreadFactory { Thread(it, "EnthusiaKOTH-Discord").apply { isDaemon = true } }).apply {
        removeOnCancelPolicy = true
        executeExistingDelayedTasksAfterShutdownPolicy = false
        continueExistingPeriodicTasksAfterShutdownPolicy = false
    }
    private var scheduled: ScheduledFuture<*>? = null
    private var inFlight = false
    @Volatile private var closed = false

    fun sendStart(kothName: String, location: String) = announcement(DiscordMessageType.START, values(kothName) + ("location" to location))
    fun sendStart(eventId: UUID, kothName: String, location: String) = announcement(DiscordMessageType.START,
        values(kothName) + mapOf("location" to location, "event_id" to eventId.toString(), "status" to "Started"))
    fun sendPreStart(kothName: String, minutes: Int) {
        if (minutes > 0) announcement(DiscordMessageType.PRE_START, values(kothName) + ("minutes" to minutes.toString()))
    }
    fun sendLiveUpdate(eventId: UUID, kothName: String, capper: TeamId?, isContested: Boolean, timeLeft: String) {
        if (!canSend()) return
        val template = template(DiscordMessageType.LIVE)
        if (!template.enabled) return
        val payload = DiscordEmbedRenderer.render(template, values(kothName) + mapOf(
            "event_id" to eventId.toString(), "capper" to (capper?.let(::resolveName) ?: "Nobody"),
            "time_left" to timeLeft, "status" to if (isContested) "⚔️ Contested" else if (capper == null) "Waiting for a capturer" else "🟢 Capturing",
        ))
        val url = webhookUrl()
        synchronized(lock) {
            val previous = messages[eventId]
            if (closed || previous?.ended == true || previous?.unavailable == true) return
            if (previous != null && previous.url != url) { previous.unavailable = true; return }
            if (previous == null && messages.size >= capacity) return
            val accepted = buffer.offer(WebhookDelivery(payload, WebhookDeliveryKind.LIVE_UPDATE, eventId = eventId, url = url))
            if (accepted) {
                messages.putIfAbsent(eventId, LiveMessage(url))
                if (!inFlight) scheduleLocked(Duration.ZERO)
            }
        }
    }
    fun sendCapture(eventId: UUID, kothName: String, winner: TeamId, wasContested: Boolean) = terminal(eventId, DiscordMessageType.WINNER,
        values(kothName) + mapOf("winner" to resolveName(winner), "status" to if (wasContested) "Won after contest" else "Captured", "event_id" to eventId.toString()))
    fun sendCancelled(eventId: UUID, kothName: String, reason: String) = terminal(eventId, DiscordMessageType.CANCELLED,
        values(kothName) + mapOf("reason" to reason, "status" to "Cancelled", "event_id" to eventId.toString()))
    fun sendNoWinner(eventId: UUID, kothName: String) = terminal(eventId, DiscordMessageType.NO_WINNER,
        values(kothName) + mapOf("status" to "Ended without a winner", "event_id" to eventId.toString()))

    private fun template(type: DiscordMessageType) = templates()[type] ?: DiscordEmbedDefaults.templates.getValue(type)
    private fun values(name: String) = mapOf("arena" to ArenaName.plain(name), "location" to "—", "capper" to "Nobody",
        "winner" to "Nobody", "time_left" to "—", "status" to "—", "reason" to "—", "minutes" to "—", "event_id" to "—")
    private fun announcement(type: DiscordMessageType, values: Map<String, String>) {
        if (!canSend()) return
        val template = template(type)
        if (template.enabled) enqueue(WebhookDelivery(
            DiscordEmbedRenderer.render(template, values, startRoleId = if (type == DiscordMessageType.START) startRoleId() else ""),
            WebhookDeliveryKind.IMPORTANT, url = webhookUrl()))
    }
    private fun terminal(eventId: UUID, type: DiscordMessageType, values: Map<String, String>) {
        val template = template(type)
        synchronized(lock) {
            val message = messages[eventId]
            if (closed || message?.ended == true) return
            message?.ended = true
            buffer.removeLive(eventId)
            if (!canSend()) { messages.remove(eventId); return }
            val delivery = WebhookDelivery(DiscordEmbedRenderer.render(template, values), WebhookDeliveryKind.IMPORTANT,
                eventId = eventId, terminal = true, url = webhookUrl(), announce = template.enabled)
            if (!buffer.offer(delivery)) { messages.remove(eventId); warn("queue is full; terminal delivery dropped") }
            else if (!inFlight) scheduleLocked(Duration.ZERO)
        }
    }
    private fun resolveName(team: TeamId): String = (if (team.mode == TeamMode.GUILD) guilds.guildName(team.id)
        else plugin.server.getOfflinePlayer(team.id).name)?.stripColors()?.takeIf { it.isNotBlank() } ?: team.id.toString().take(8)
    fun shutdown() {
        synchronized(lock) { closed = true; buffer.clear(); messages.clear(); scheduled?.cancel(true); scheduled = null }
        executor.shutdownNow()
    }
    internal fun pendingCount(): Int = synchronized(lock) { buffer.size() }
    internal fun liveMessageCount(): Int = synchronized(lock) { messages.size }
    private fun canSend() = !closed && enabled() && webhookUrl().isNotBlank()
    private fun warn(message: String) { plugin.logger.warning("Discord webhook $message") }
    private fun enqueue(delivery: WebhookDelivery) {
        synchronized(lock) {
            if (closed) return
            if (!buffer.offer(delivery)) warn("queue is full; delivery dropped")
            else if (!inFlight) scheduleLocked(Duration.ZERO)
        }
    }
    private fun scheduleLocked(delay: Duration) {
        if (closed || inFlight || !buffer.isNotEmpty() || scheduled?.isDone == false) return
        try { scheduled = executor.schedule(::deliverNext, delay.toMillis().coerceAtLeast(0), TimeUnit.MILLISECONDS) }
        catch (_: RejectedExecutionException) { closed = true; buffer.clear(); messages.clear() }
    }
    private fun deliverNext() {
        var delivery = synchronized(lock) {
            scheduled = null
            if (closed || inFlight) return
            val next = buffer.poll() ?: return
            inFlight = true
            next
        }
        var creating = false
        var retryDelay: Duration? = null
        try {
            if (enabled() && webhookUrl() == delivery.url) {
                val message = synchronized(lock) { messages[delivery.eventId] }
                val editable = message != null && !message.unavailable && message.url == delivery.url
                if (delivery.kind == WebhookDeliveryKind.LIVE_UPDATE && editable && !message!!.ended) {
                    creating = message.messageId == null
                    val response = if (creating) transport.create(delivery.url, delivery.payload)
                        else transport.edit(delivery.url, message.messageId!!, delivery.payload)
                    if (response.statusCode in 200..299) {
                        if (creating) synchronized(lock) {
                            if (response.messageId?.matches(Regex("[0-9]{1,20}")) == true) message.messageId = response.messageId
                            else { message.unavailable = true; warn("live message creation returned no message ID; updates stopped") }
                        }
                    } else {
                        retryDelay = if (creating && response.statusCode != 429) null else DiscordRetryPolicy.delayFor(response, delivery.attempts)
                        if (retryDelay == null) synchronized(lock) { message.unavailable = true; warn("live delivery failed with HTTP ${response.statusCode}; updates stopped") }
                    }
                } else if (delivery.kind == WebhookDeliveryKind.IMPORTANT) {
                    if (delivery.terminal && !delivery.finalized) {
                        if (editable && message!!.messageId != null) {
                            val response = transport.edit(delivery.url, message.messageId!!, delivery.payload)
                            retryDelay = if (response.statusCode in 200..299) null else DiscordRetryPolicy.delayFor(response, delivery.attempts)
                            if (retryDelay == null && response.statusCode !in 200..299) warn("final status edit failed with HTTP ${response.statusCode}")
                        }
                        if (retryDelay == null) delivery = delivery.copy(finalized = true, attempts = 0)
                    }
                    if (retryDelay == null && delivery.announce) {
                        val response = transport.send(delivery.url, delivery.payload)
                        retryDelay = if (response.statusCode in 200..299) null else DiscordRetryPolicy.delayFor(response, delivery.attempts)
                        if (retryDelay == null && response.statusCode !in 200..299) warn("announcement failed with HTTP ${response.statusCode}; delivery dropped")
                    }
                }
            }
        } catch (_: IllegalArgumentException) { warn("URL or request is invalid; delivery dropped") }
        catch (_: Exception) {
            retryDelay = if (creating) null else DiscordRetryPolicy.delayForFailure(delivery.attempts)
            if (creating) synchronized(lock) { messages[delivery.eventId]?.unavailable = true }
            if (retryDelay == null) {
                warn("transport failed; delivery dropped (details redacted)")
                if (delivery.terminal && !delivery.finalized && delivery.announce) {
                    delivery = delivery.copy(finalized = true, attempts = -1)
                    retryDelay = Duration.ZERO
                }
            }
        } finally {
            synchronized(lock) {
                inFlight = false
                if (!closed) {
                    val staleLive = delivery.kind == WebhookDeliveryKind.LIVE_UPDATE && messages[delivery.eventId]?.ended == true
                    val requeued = retryDelay != null && !staleLive && buffer.retryFirst(delivery.copy(attempts = delivery.attempts + 1))
                    if (delivery.terminal && !requeued) messages.remove(delivery.eventId)
                    if (retryDelay != null && !staleLive && !requeued) {
                        messages[delivery.eventId]?.unavailable = true
                        warn("retry queue is full; delivery dropped")
                    }
                    scheduleLocked(retryDelay ?: Duration.ZERO)
                }
            }
        }
    }
}
