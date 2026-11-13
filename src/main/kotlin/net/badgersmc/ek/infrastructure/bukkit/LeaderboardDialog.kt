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

data class LeaderboardRow(val rank: Int, val name: String, val wins: Int, val viewer: Boolean)

/** One page of a leaderboard, worked out without Bukkit so it can be tested. */
data class LeaderboardPage(val rows: List<LeaderboardRow>, val page: Int, val pages: Int, val viewer: LeaderboardRow?) {
    companion object {
        const val SIZE = 10

        fun of(wins: Map<String, Int>, side: LeaderboardSide, page: Int, viewerKey: String?, name: (String) -> String): LeaderboardPage {
            val ranked = wins.entries.filter { it.key.startsWith("${side.prefix}:") && it.value > 0 }
                .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            val pages = ((ranked.size + SIZE - 1) / SIZE).coerceAtLeast(1)
            val selected = page.coerceIn(1, pages)
            fun row(index: Int) = ranked[index].let { LeaderboardRow(index + 1, name(it.key), it.value, it.key == viewerKey) }
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
) {
    data class View(val period: String = "lifetime", val side: LeaderboardSide = LeaderboardSide.PLAYERS, val page: Int = 1)

    fun open(player: Player, view: View = View()) {
        val available = periods()
        val period = view.period.takeIf { it in available } ?: "lifetime"
        val board = LeaderboardPage.of(wins(period).orEmpty(), view.side, view.page, "solo:${player.uniqueId}", name)
        val current = view.copy(period = period, page = board.page)
        player.showDialog(Dialog.create { factory ->
            factory.empty()
                .base(DialogBase.builder(MenuStyle.title("KOTH Leaderboard"))
                    .canCloseWithEscape(true)
                    .pause(false)
                    .afterAction(DialogBase.DialogAfterAction.NONE)
                    .body(body(current, board))
                    .build())
                .type(DialogType.multiAction(buttons(current, board, available))
                    .columns(2)
                    .exitAction(ActionButton.builder(MenuStyle.text("Close")).width(WIDTH * 2 + 4)
                        .action(DialogAction.customClick({ _, audience -> audience.closeDialog() }, ONE_USE)).build())
                    .build())
        })
    }

    private fun body(view: View, board: LeaderboardPage): List<DialogBody> {
        val heading = Component.text("${title(view.period)}  ", MenuStyle.GOLD)
            .append(Component.text("${view.side.label}  ", MenuStyle.CREAM))
            .append(Component.text("Page ${board.page} of ${board.pages}", MenuStyle.MUTED))
        val rows = if (board.rows.isEmpty()) listOf(Component.text("No wins yet in this period", MenuStyle.MUTED))
            else board.rows.map(::line)
        val parts = mutableListOf<DialogBody>(
            DialogBody.plainMessage(heading, 300),
            DialogBody.plainMessage(Component.join(JoinConfiguration.newlines(), rows), 300),
        )
        if (view.side == LeaderboardSide.PLAYERS) parts += DialogBody.plainMessage(board.viewer?.let {
            Component.text("Your rank: ", MenuStyle.CREAM).append(Component.text("#${it.rank}", MenuStyle.GOLD))
                .append(Component.text("  ${wins(it.wins)}", MenuStyle.CREAM))
        } ?: Component.text("You have no wins in this period yet", MenuStyle.MUTED), 300)
        return parts
    }

    private fun line(row: LeaderboardRow): Component {
        val rankColor = if (row.rank <= 3) MenuStyle.GOLD else MenuStyle.MUTED
        val nameText = Component.text(row.name, if (row.viewer) MenuStyle.GOLD else MenuStyle.CREAM)
            .let { if (row.viewer) it.decoration(TextDecoration.BOLD, true) else it }
        return Component.text("#${row.rank}  ", rankColor).append(nameText).append(Component.text("  ${wins(row.wins)}", MenuStyle.GOLD))
    }

    /**
     * Two columns: Players | Guilds, Previous | Next, then the periods. Season only appears once a season start is set;
     * the periods come last so that, without it, Lifetime sits centred on its own row instead of pushing the others out of line.
     */
    private fun buttons(view: View, board: LeaderboardPage, available: List<String>): List<ActionButton> = buildList {
        LeaderboardSide.entries.forEach { side -> add(button(side.label, side == view.side, view.copy(side = side, page = 1))) }
        add(button("Previous", false, view.copy(page = view.page - 1), enabled = board.page > 1, unavailable = "This is the first page"))
        add(button("Next", false, view.copy(page = view.page + 1), enabled = board.page < board.pages, unavailable = "This is the last page"))
        PERIODS.filter { it in available }.forEach { period ->
            add(button(title(period), period == view.period, view.copy(period = period, page = 1)))
        }
    }

    private fun button(label: String, selected: Boolean, target: View, enabled: Boolean = true, unavailable: String = ""): ActionButton {
        val color = when { selected -> MenuStyle.GOLD; enabled -> MenuStyle.CREAM; else -> MenuStyle.MUTED }
        val text = Component.text(label, color).let { if (selected) it.decoration(TextDecoration.UNDERLINED, true) else it }
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
        val PERIODS = listOf("daily", "weekly", "season", "lifetime")
        val ONE_USE: ClickCallback.Options = ClickCallback.Options.builder().uses(1).lifetime(Duration.ofMinutes(10)).build()
    }

    private fun title(period: String) = period.replaceFirstChar { it.titlecase(Locale.ROOT) }
    private fun wins(count: Int) = "$count ${if (count == 1) "win" else "wins"}"
}
