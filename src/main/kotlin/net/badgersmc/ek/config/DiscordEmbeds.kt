package net.badgersmc.ek.config

enum class DiscordMessageType(val key: String) {
    PRE_START("pre-start"), START("start"), LIVE("live"), WINNER("winner"), CANCELLED("cancelled"), NO_WINNER("no-winner"),
}

data class DiscordEmbedField(val name: String, val value: String, val inline: Boolean = false)

data class DiscordEmbedTemplate(
    val enabled: Boolean = true,
    val title: String,
    val description: String = "",
    val color: Int,
    val fields: List<DiscordEmbedField> = emptyList(),
    val timestamp: Boolean = true,
)

object DiscordEmbedDefaults {
    val templates: Map<DiscordMessageType, DiscordEmbedTemplate> = mapOf(
        DiscordMessageType.PRE_START to DiscordEmbedTemplate(title = "⏰ KOTH Starting Soon", color = 0xF39C12,
            fields = listOf(DiscordEmbedField("KOTH", "{arena}", true), DiscordEmbedField("Starts In", "{minutes} minute(s)", true))),
        DiscordMessageType.START to DiscordEmbedTemplate(title = "🔥 KOTH Started!", color = 0x3498DB,
            fields = listOf(DiscordEmbedField("KOTH", "{arena}", true), DiscordEmbedField("Location", "{location}", true))),
        DiscordMessageType.LIVE to DiscordEmbedTemplate(title = "🏆 KOTH — {arena}", color = 0xF1C40F,
            fields = listOf(DiscordEmbedField("KOTH", "{arena}", true), DiscordEmbedField("Currently Capped By", "{capper}", true),
                DiscordEmbedField("Time Left", "{time_left}", true), DiscordEmbedField("Status", "{status}"))),
        DiscordMessageType.WINNER to DiscordEmbedTemplate(title = "🎉 KOTH Captured!", color = 0x2ECC71,
            fields = listOf(DiscordEmbedField("KOTH", "{arena}", true), DiscordEmbedField("Captured By", "{winner}", true),
                DiscordEmbedField("Result", "{winner} captured {arena}!"))),
        DiscordMessageType.CANCELLED to DiscordEmbedTemplate(title = "🛑 KOTH Cancelled", color = 0xE67E22,
            fields = listOf(DiscordEmbedField("KOTH", "{arena}", true), DiscordEmbedField("Reason", "{reason}"))),
        DiscordMessageType.NO_WINNER to DiscordEmbedTemplate(title = "⌛ KOTH Ended — No Winner", color = 0x95A5A6,
            description = "{arena} ended without a winner.", fields = listOf(DiscordEmbedField("KOTH", "{arena}", true))),
    )
}
