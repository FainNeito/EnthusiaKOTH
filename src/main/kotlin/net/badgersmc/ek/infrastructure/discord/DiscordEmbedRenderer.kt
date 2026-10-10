package net.badgersmc.ek.infrastructure.discord

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import net.badgersmc.ek.config.DiscordEmbedTemplate
import java.time.Instant

/** Templates are trusted configuration. Values are inserted once, never re-expanded. */
internal object DiscordEmbedRenderer {
    private val placeholder = Regex("\\{([a-z_]+)}")

    fun render(template: DiscordEmbedTemplate, values: Map<String, String>, now: Instant = Instant.now()): String {
        var remaining = 6000
        fun text(raw: String, limit: Int): String {
            val expanded = placeholder.replace(raw) { values[it.groupValues[1]] ?: it.value }
                .filter { !it.isISOControl() || it == '\n' || it == '\t' }
            var end = minOf(expanded.length, limit, remaining)
            if (end > 0 && end < expanded.length && expanded[end - 1].isHighSurrogate()) end--
            return expanded.take(end).also { remaining -= it.length }
        }
        val embed = JsonObject().apply {
            addProperty("title", text(template.title, 256))
            if (template.description.isNotBlank()) addProperty("description", text(template.description, 4096))
            addProperty("color", template.color.coerceIn(0, 0xFFFFFF))
            val fields = JsonArray()
            template.fields.take(25).forEach { field ->
                // Discord requires nonempty field names/values; reserve one unit for the value.
                if (remaining >= 2) {
                    val name = text(field.name.ifBlank { "—" }, minOf(256, remaining - 1)).ifBlank { remaining--; "—" }
                    val value = text(field.value.ifBlank { "—" }, 1024).ifBlank { remaining--; "—" }
                    fields.add(JsonObject().apply { addProperty("name", name); addProperty("value", value); addProperty("inline", field.inline) })
                }
            }
            if (!fields.isEmpty) add("fields", fields)
            if (template.timestamp) addProperty("timestamp", now.toString())
        }
        return JsonObject().apply {
            add("allowed_mentions", JsonObject().apply { add("parse", JsonArray()) })
            add("embeds", JsonArray().apply { add(embed) })
        }.toString()
    }
}
