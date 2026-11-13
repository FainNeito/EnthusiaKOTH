package net.badgersmc.ek.infrastructure.bukkit

import io.papermc.paper.dialog.Dialog
import io.papermc.paper.registry.data.dialog.ActionButton
import io.papermc.paper.registry.data.dialog.DialogBase
import io.papermc.paper.registry.data.dialog.action.DialogAction
import io.papermc.paper.registry.data.dialog.body.DialogBody
import io.papermc.paper.registry.data.dialog.type.DialogType
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.JoinConfiguration
import net.kyori.adventure.text.event.ClickCallback
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin
import java.time.Duration
import java.util.Locale

/** Which board the dialog shows. */
enum class LeaderboardSide(val prefix: String, val label: String) { PLAYERS("solo", "Players"), GUILDS("guild", "Guilds") }

data class LeaderboardRow(val rank: Int, val name: String, val wins: Int, val viewer: Boolean, val key: String = "")

/** One page of a leaderboard, worked out without Bukkit so it can be tested. */
data class LeaderboardPage(val rows: List<LeaderboardRow>, val page: Int, val pages: Int, val viewer: LeaderboardRow?) {
    companion object {
        const val SIZE = 10

        fun of(wins: Map<String, Int>, side: LeaderboardSide, page: Int, viewerKey: String?, name: (String) -> String): LeaderboardPage {
            val ranked = wins.entries.filter { it.key.startsWith("${side.prefix}:") && it.value > 0 }
                .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            val pages = ((ranked.size + SIZE - 1) / SIZE).coerceAtLeast(1)
            val selected = page.coerceIn(1, pages)
            fun row(index: Int) = ranked[index].let { LeaderboardRow(index + 1, name(it.key), it.value, it.key == viewerKey, it.key) }
            val rows = (((selected - 1) * SIZE) until minOf(selected * SIZE, ranked.size)).map(::row)
            val viewer = ranked.indexOfFirst { it.key == viewerKey }.takeIf { it >= 0 }?.let(::row)
            return LeaderboardPage(rows, selected, pages, viewer)
        }
    }
}

/**
 * The KOTH leaderboard as a dialog: ten ranks per page and the viewer's own rank, with buttons in pairs
 * (board, periods, pages). The dialog stays open while a button is handled and is replaced in place,
 * so the client never drops back to the game and re-centres the cursor between views.
 */
class LeaderboardDialog(
    private val plugin: JavaPlugin,
    /** Wins per entity key for a period ("daily", "weekly", "season", "lifetime"), or null when that period is unavailable. */
    private val wins: (String) -> Map<String, Int>?,
    private val periods: () -> List<String>,
    private val name: (String) -> String,
    /** A player's head drawn inline in text, or null for guilds and unknown keys. */
    private val head: (String) -> Component?,
    /** Whether the Enthusia pack's leaderboard art is available (Nexo installed, or forced on in config). */
    private val art: () -> Boolean = { false },
) {
    data class View(val period: String = "lifetime", val side: LeaderboardSide = LeaderboardSide.PLAYERS, val page: Int = 1)

    fun open(player: Player, view: View = View()) {
        val available = periods()
        val period = view.period.takeIf { it in available } ?: "lifetime"
        val board = LeaderboardPage.of(wins(period).orEmpty(), view.side, view.page, "solo:${player.uniqueId}", name)
        val current = view.copy(period = period, page = board.page)
        val themed = art()
        val close = if (themed) LeaderboardArt.button("Close", LeaderboardArt.Button.NORMAL, wide = true) else MenuStyle.text("Close")
        player.showDialog(Dialog.create { factory ->
            factory.empty()
                .base(DialogBase.builder(MenuStyle.title("KOTH Leaderboard"))
                    .canCloseWithEscape(true)
                    .pause(false)
                    .afterAction(DialogBase.DialogAfterAction.NONE)
                    .body(body(current, board, themed))
                    .build())
                .type(DialogType.multiAction(buttons(current, board, available, themed))
                    .columns(2)
                    .exitAction(ActionButton.builder(close).width(WIDTH * 2 + 4)
                        .action(DialogAction.customClick({ _, audience -> audience.closeDialog() }, ONE_USE)).build())
                    .build())
        })
    }

    /** Heading, one row per rank with its head or banner, then the viewer's own rank, like enthusia.info/leaderboards. */
    private fun body(view: View, board: LeaderboardPage, themed: Boolean): List<DialogBody> {
        val heading = Component.text("${title(view.period)}  ", MenuStyle.GOLD)
            .append(Component.text("${view.side.label}  ", MenuStyle.CREAM))
            .append(Component.text("Page ${board.page} of ${board.pages}", if (themed) MenuStyle.CREAM else MenuStyle.MUTED))
        // The banner line is measured before its cursor moves are applied, so it needs room for the art and the text.
        // Each line with the width it draws at, so the last one can carry the button panel centred under it.
        val lines = mutableListOf(if (themed) LeaderboardArt.banner(heading) to LeaderboardArt.BANNER_ADVANCE else heading to 0)
        if (board.rows.isEmpty()) lines += Component.text("No wins yet in this period", MenuStyle.MUTED).let { it to TextWidth.of(it) }
        board.rows.forEach { row -> lines += line(row, themed) to LeaderboardArt.ROW_ADVANCE }
        if (themed) lines += LeaderboardArt.divider() to LeaderboardArt.DIVIDER_ADVANCE
        if (view.side == LeaderboardSide.PLAYERS) lines += board.viewer?.let { yours(it, themed) to LeaderboardArt.ROW_ADVANCE }
            ?: Component.text("You have no wins in this period yet", MenuStyle.MUTED).let { it to TextWidth.of(it) }
        if (themed) lines[lines.lastIndex] = lines.last().let { (content, width) -> LeaderboardArt.withPanel(content, width) to width }
        // Lines are measured before their cursor moves are applied, so each needs room for the art and the text.
        // The panel line is the widest (panel plus content), so it gets the largest box the client allows; if it
        // wrapped, the extra lines would push the buttons down past the bottom of the panel.
        return lines.mapIndexed { index, (content, _) ->
            DialogBody.plainMessage(content, if (themed && index == lines.lastIndex) PANEL_LINE_WIDTH else BANNER_WIDTH)
        }
    }

    private fun rank(rank: Int, themed: Boolean): Component = if (themed) LeaderboardArt.rank(rank)
        else Component.text("#$rank", if (rank <= 3) MenuStyle.GOLD else MenuStyle.MUTED)

    private fun line(row: LeaderboardRow, themed: Boolean): Component {
        val nameText = Component.text(row.name, if (row.viewer) MenuStyle.GOLD else MenuStyle.CREAM)
            .let { if (row.viewer) it.decoration(TextDecoration.BOLD, true) else it }
        val face = head(row.key)
        val left = Component.text().append(rank(row.rank, themed)).append(Component.text(" "))
        face?.let { left.append(it).append(Component.text(" ")) }
        left.append(nameText)
        val score = Component.text(wins(row.wins), MenuStyle.GOLD)
        if (!themed) return left.append(Component.text("  ")).append(score).build()
        // On the art the row reads like the website: rank, head and name on the left, wins against the right edge.
        val leftWidth = LeaderboardArt.BADGE_ADVANCE + 4 + (if (face != null) LeaderboardArt.HEAD_ADVANCE + 4 else 0) +
            TextWidth.of(row.name, bold = row.viewer)
        return LeaderboardArt.row(left.build(), leftWidth, score, TextWidth.of(wins(row.wins)), row.viewer)
    }

    private fun yours(row: LeaderboardRow, themed: Boolean): Component {
        val label = Component.text("Your rank ", MenuStyle.CREAM)
        val score = Component.text(wins(row.wins), MenuStyle.GOLD)
        if (!themed) return label.append(rank(row.rank, false)).append(Component.text("  ")).append(score)
        return LeaderboardArt.row(label.append(LeaderboardArt.rank(row.rank)), TextWidth.of("Your rank ") + LeaderboardArt.BADGE_ADVANCE,
            score, TextWidth.of(wins(row.wins)), viewer = true)
    }

    /**
     * Two columns: Players | Guilds, Previous | Next, then the periods. Season only appears once a season start is set;
     * the periods come last so that, without it, Lifetime sits centred on its own row instead of pushing the others out of line.
     */
    private fun buttons(view: View, board: LeaderboardPage, available: List<String>, themed: Boolean): List<ActionButton> = buildList {
        LeaderboardSide.entries.forEach { side -> add(button(side.label, side == view.side, view.copy(side = side, page = 1), themed)) }
        add(button("Previous", false, view.copy(page = view.page - 1), themed, enabled = board.page > 1, unavailable = "This is the first page"))
        add(button("Next", false, view.copy(page = view.page + 1), themed, enabled = board.page < board.pages, unavailable = "This is the last page"))
        PERIODS.filter { it in available }.forEach { period ->
            add(button(title(period), period == view.period, view.copy(period = period, page = 1), themed))
        }
    }

    private fun button(label: String, selected: Boolean, target: View, themed: Boolean, enabled: Boolean = true, unavailable: String = ""): ActionButton {
        val color = when { selected -> MenuStyle.GOLD; enabled -> MenuStyle.CREAM; else -> MenuStyle.MUTED }
        val look = when { selected -> LeaderboardArt.Button.SELECTED; enabled -> LeaderboardArt.Button.NORMAL; else -> LeaderboardArt.Button.DISABLED }
        val text = if (themed) LeaderboardArt.button(label, look)
            else Component.text(label, color).let { if (selected) it.decoration(TextDecoration.UNDERLINED, true) else it }
        val builder = ActionButton.builder(text).width(WIDTH)
        // Buttons for the current view, or with nowhere to go, do nothing: the dialog simply stays as it is.
        if (selected) return builder.tooltip(MenuStyle.text("Showing now", MenuStyle.MUTED)).build()
        if (!enabled) return builder.tooltip(MenuStyle.text(unavailable, MenuStyle.MUTED)).build()
        return builder.action(DialogAction.customClick({ _, audience ->
            val player = audience as? Player ?: return@customClick
            plugin.server.scheduler.runTask(plugin, Runnable { if (player.isOnline) open(player, target) })
        }, ONE_USE)).build()
    }

    private companion object {
        const val WIDTH = 120
        const val ROW_WIDTH = 244
        const val BANNER_WIDTH = 520
        const val PANEL_LINE_WIDTH = 1024
        val PERIODS = listOf("daily", "weekly", "season", "lifetime")
        val ONE_USE: ClickCallback.Options = ClickCallback.Options.builder().uses(1).lifetime(Duration.ofMinutes(10)).build()
    }

    private fun title(period: String) = period.replaceFirstChar { it.titlecase(Locale.ROOT) }
    private fun wins(count: Int) = "$count ${if (count == 1) "win" else "wins"}"
}
