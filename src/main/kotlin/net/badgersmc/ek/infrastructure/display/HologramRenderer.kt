package net.badgersmc.ek.infrastructure.display

import net.badgersmc.ek.application.BlitzRound
import net.badgersmc.ek.application.ScheduledOccurrence
import net.badgersmc.ek.infrastructure.bukkit.MenuStyle
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextDecoration
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/** A live KOTH as a hologram shows it. A null capture percent means the arena scores by time rather than a capture. */
data class LiveArena(val capper: String?, val endsAt: Instant, val capturePercent: Double?)

/** Builds every hologram's lines in the studio style: gold titles, cream text, muted labels. */
class HologramRenderer(
    private val clock: Clock,
    private val zone: () -> ZoneId,
    private val arenaIds: () -> Collection<String>,
    private val arenaName: (String) -> Component,
    private val arenaFamily: (String) -> String?,
    private val live: (String) -> LiveArena?,
    private val nextStart: (String) -> Instant?,
    private val upcoming: (Int) -> List<ScheduledOccurrence>,
    private val nextBlitz: () -> List<ScheduledOccurrence>,
    private val blitzRoundSeconds: () -> Int,
    private val top: (prefix: String, count: Int) -> List<Pair<String, Int>>,
    private val placeholder: (String) -> String,
    private val custom: (String) -> Component,
) {
    private val time = DateTimeFormatter.ofPattern("HH:mm")

    fun render(spec: HologramSpec): List<Component> = when (spec.type) {
        HologramType.ARENA -> arena(spec.arena.orEmpty())
        HologramType.SCHEDULE -> schedule()
        HologramType.BLITZ -> blitz()
        HologramType.TOP_PLAYERS -> leaderboard("Top KOTH Players", "solo")
        HologramType.TOP_GUILDS -> leaderboard("Top KOTH Guilds", "guild")
        HologramType.PLACEHOLDERS -> placeholders()
        HologramType.CUSTOM -> spec.lines.ifEmpty { listOf("<color:#bfae98>Add lines to this hologram in holograms.yml") }.map(custom)
    }

    private fun title(text: String): Component = Component.text(text, MenuStyle.GOLD).decoration(TextDecoration.BOLD, true)
    private fun muted(text: String): Component = Component.text(text, MenuStyle.MUTED)

    internal fun arena(id: String): List<Component> {
        if (id !in arenaIds()) return listOf(title("KOTH"), Component.text("Arena '$id' is not loaded", MenuStyle.CORAL))
        val family = arenaFamily(id)?.replaceFirstChar { it.titlecase(Locale.ROOT) } ?: "KOTH"
        val lines = mutableListOf(arenaName(id).decoration(TextDecoration.BOLD, true), muted("$family KOTH"))
        val state = live(id)
        if (state != null) {
            lines += Component.text("Live now", MenuStyle.GOLD)
            lines += MenuStyle.line("Holding", state.capper ?: "Nobody")
            state.capturePercent?.let { lines += Component.text(bar(it), MenuStyle.GOLD).append(Component.text(" ${it.toInt()}%", MenuStyle.CREAM)) }
            lines += MenuStyle.line("Ends in", duration(Duration.between(clock.instant(), state.endsAt).seconds))
        } else {
            val next = nextStart(id)
            lines += if (next == null) muted("Not scheduled") else MenuStyle.line("Next start", when_(next))
        }
        return lines
    }

    internal fun schedule(): List<Component> {
        val next = upcoming(5)
        if (next.isEmpty()) return listOf(title("Upcoming KOTH"), muted("Nothing scheduled"))
        return listOf(title("Upcoming KOTH")) + next.map { occurrence ->
            val round = BlitzRound.parse(occurrence.id)
            arenaName(occurrence.arenaId).append(Component.text("  ${when_(occurrence.instant)}", MenuStyle.CREAM))
                .let { if (round != null) it.append(muted("  Blitz ${round.round}/${round.rounds}")) else it }
        }
    }

    internal fun blitz(): List<Component> {
        val rounds = nextBlitz()
        if (rounds.isEmpty()) return listOf(title("Weekend Blitz"), muted("No blitz this week"))
        val now = clock.instant()
        val length = blitzRoundSeconds().toLong()
        val opens = rounds.first().instant
        val header = if (now.isBefore(opens)) MenuStyle.line("Starts", when_(opens)) else Component.text("Running now", MenuStyle.GOLD)
        return listOf(title("Weekend Blitz"), header) + rounds.mapIndexed { index, occurrence ->
            val ends = occurrence.instant.plusSeconds(length)
            val label = Component.text("${index + 1}. ", MenuStyle.MUTED).append(arenaName(occurrence.arenaId))
            when {
                now.isBefore(occurrence.instant) -> label.append(Component.text("  ${occurrence.instant.atZone(zone()).format(time)}", MenuStyle.CREAM))
                now.isBefore(ends) && live(occurrence.arenaId) != null -> label.append(Component.text("  live", MenuStyle.GOLD))
                else -> label.append(muted("  done"))
            }
        }
    }

    internal fun leaderboard(heading: String, prefix: String): List<Component> {
        val entries = top(prefix, 5)
        if (entries.isEmpty()) return listOf(title(heading), muted("No wins yet"))
        return listOf(title(heading)) + entries.mapIndexed { index, (name, wins) ->
            Component.text("${index + 1}. ", MenuStyle.MUTED).append(Component.text(name, MenuStyle.CREAM))
                .append(Component.text("  $wins ${if (wins == 1) "win" else "wins"}", MenuStyle.GOLD))
        }
    }

    /** Every KOTH placeholder beside its live value, so staff can see exactly what scoreboards and other plugins will print. */
    internal fun placeholders(): List<Component> {
        val keys = listOf("is_active", "current_koth", "current_name", "current_capper", "current_timeleft", "current_capture_progress",
            "current_capture_timeleft", "next_koth", "next_name", "next_koth_time", "wins", "top_player_1_name", "top_player_1_wins",
            "top_guild_1_name", "top_guild_1_wins") +
            arenaIds().sorted().flatMap { id -> listOf("${id}_name", "${id}_capper", "${id}_timeleft", "${id}_capture_progress") }
        return listOf(title("KOTH Placeholders")) + keys.map { key ->
            Component.text("%enthusiakoth_$key%  ", MenuStyle.MUTED).append(legacyValue(placeholder(key).ifEmpty { "(empty)" }).colorIfAbsent(MenuStyle.CREAM))
        }
    }

    private fun bar(percent: Double): String {
        val filled = (percent.coerceIn(0.0, 100.0) / 10).toInt()
        return "■".repeat(filled) + "□".repeat(10 - filled)
    }

    private fun when_(instant: Instant): String {
        val seconds = Duration.between(clock.instant(), instant).seconds
        val local = instant.atZone(zone())
        val today = clock.instant().atZone(zone()).toLocalDate()
        val day = if (local.toLocalDate() == today) "" else local.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.ENGLISH) + " "
        return "$day${local.format(time)} (in ${duration(seconds)})"
    }

    private fun duration(seconds: Long): String {
        val safe = seconds.coerceAtLeast(0)
        val hours = safe / 3600
        val minutes = safe % 3600 / 60
        return when {
            hours > 0 -> "${hours}h ${minutes}m"
            minutes > 0 -> "${minutes}m ${safe % 60}s"
            else -> "${safe}s"
        }
    }
}
