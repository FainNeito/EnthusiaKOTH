// Compile-time mirror of LumaGuilds' public GuildVisualLookup service (registered in the ServicesManager).
// Excluded from the shadow JAR like the rest of this package; older LumaGuilds builds may not provide it.
package net.lumalyte.lg.api

import java.util.UUID

interface GuildVisualLookup {
    fun getGuildVisual(guildId: UUID): GuildVisualSummary?
}

data class GuildVisualSummary(
    val leaderId: UUID?,
    val banner: GuildBannerDesignSummary?,
)

/** [baseColor] is the dye name (RED); each pattern's type is its key in upper case (STRIPE_TOP) and its colour a dye name. */
data class GuildBannerDesignSummary(
    val baseColor: String,
    val patterns: List<GuildBannerPatternSummary>,
)

data class GuildBannerPatternSummary(
    val type: String,
    val color: String,
)
