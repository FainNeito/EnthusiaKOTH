package net.badgersmc.ek.infrastructure.bukkit

import net.badgersmc.ek.infrastructure.i18n.arenaComponent
import net.badgersmc.ek.infrastructure.i18n.arenaMsg
import net.badgersmc.ek.application.CancellationReason
import net.badgersmc.ek.application.FlareService
import net.badgersmc.ek.application.KothService
import net.badgersmc.ek.application.ScheduleService
import net.badgersmc.ek.application.StartActor
import net.badgersmc.ek.application.StartFailure
import net.badgersmc.ek.application.StartRequest
import net.badgersmc.ek.application.StartResult
import net.badgersmc.ek.application.StartService
import net.badgersmc.ek.application.StartSource
import net.badgersmc.ek.application.StartTier
import net.badgersmc.ek.config.EnthusiaKothConfig
import net.badgersmc.ek.domain.KothArena
import net.badgersmc.ek.domain.LockState
import net.badgersmc.ek.domain.PrivateJoinResult
import net.badgersmc.ek.domain.PrivateTestAccess
import net.badgersmc.ek.domain.TeamMode
import net.badgersmc.ek.infrastructure.lumaguilds.LumaGuildsAdapter
import net.badgersmc.ek.infrastructure.persistence.SqlStatsRepository
import net.badgersmc.nexus.i18n.LangService
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.Location
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemStack
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

class KothGuiHolder(
    val arenaIds: List<String>,
    val modeSlot: Int,
    var teamMode: TeamMode = TeamMode.SOLO,
) : InventoryHolder {
    lateinit var backingInventory: Inventory
    override fun getInventory(): Inventory = backingInventory
}

/** The confirmation step between clicking an arena and paying to start it. */
class KothStartConfirmHolder(val arenaId: String, val teamMode: TeamMode) : InventoryHolder {
    lateinit var backingInventory: Inventory
    override fun getInventory(): Inventory = backingInventory
    companion object { const val START_SLOT = 11; const val BACK_SLOT = 15 }
}

class KothCommand(
    private val plugin: org.bukkit.plugin.java.JavaPlugin,
    private val cfgLoader: () -> EnthusiaKothConfig,
    private val kothService: KothService,
    private val scheduleService: ScheduleService,
    private val stats: SqlStatsRepository,
    private val guilds: LumaGuildsAdapter,
    private val flareService: FlareService,
    private val startService: StartService,
    private val lang: LangService,
    private val arenas: () -> Map<String, KothArena>,
    private val reloadAction: () -> Unit,
    private val lockAction: (LockState) -> Unit,
    private val arenaRegionAction: (String, String, String) -> String,
    private val arenaCenterAction: (String, Location) -> String,
    private val arenaEnabledAction: (String, Boolean) -> String,
    private val arenaRegionSuggestions: (String) -> List<String>,
    private val setup: ArenaSetupController? = null,
    private val settings: StaffSettingsController? = null,
    private val progression: ProgressionController? = null,
    private val holograms: () -> net.badgersmc.ek.infrastructure.display.KothHologramService? = { null },
) : CommandExecutor, TabCompleter {

    /** /ekoth hologram create <name> <type> [arena] | move <name> | remove <name> | list, or null when the line is not a hologram command. */
    internal fun hologramTabOptions(sender: CommandSender, args: Array<out String>): List<String>? {
        if (args.getOrNull(0)?.lowercase() !in setOf("hologram", "holo") || args.size < 2) return null
        if (!sender.hasPermission("enthusiakoth.admin")) return emptyList()
        val action = args[1].lowercase()
        val names = holograms()?.names().orEmpty()
        val options = when (args.size) {
            2 -> listOf("create", "move", "remove", "list")
            3 -> when (action) { "move", "remove", "tp" -> names; "create" -> listOf("<name>"); else -> emptyList() }
            4 -> if (action == "create") net.badgersmc.ek.infrastructure.display.HologramType.entries.map { it.id } else emptyList()
            5 -> if (action == "create" && net.badgersmc.ek.infrastructure.display.HologramType.parse(args[3])?.needsArena == true) arenas().keys.sorted() else emptyList()
            else -> emptyList()
        }
        return options.filter { it.startsWith(args.last(), true) }
    }

    private fun hologramReply(result: net.badgersmc.ek.infrastructure.display.HologramChange, done: String, name: String, type: String = ""): Component =
        lang.msg(when (result) {
            net.badgersmc.ek.infrastructure.display.HologramChange.DONE -> done
            net.badgersmc.ek.infrastructure.display.HologramChange.EXISTS -> "hologram.exists"
            net.badgersmc.ek.infrastructure.display.HologramChange.UNKNOWN -> "hologram.unknown"
            net.badgersmc.ek.infrastructure.display.HologramChange.SAVE_FAILED -> "hologram.save_failed"
            net.badgersmc.ek.infrastructure.display.HologramChange.FILE_UNREADABLE -> "hologram.file_unreadable"
        }, "name" to name, "type" to type)

    private fun hologram(sender: CommandSender, args: Array<out String>) {
        if (!sender.hasPermission("enthusiakoth.admin")) { sender.sendMessage(lang.msg("command.error.no_permission")); return }
        val service = holograms() ?: return
        val action = args.getOrNull(1)?.lowercase()
        val name = args.getOrNull(2)
        when (action) {
            "list" -> {
                val all = service.all()
                if (all.isEmpty()) { sender.sendMessage(lang.msg("hologram.none")); return }
                sender.sendMessage(lang.msg("hologram.list_header", "count" to all.size.toString()))
                all.forEach { spec ->
                    sender.sendMessage(lang.msg("hologram.list_entry", "name" to spec.name,
                        "type" to (spec.type.id + (spec.arena?.let { " $it" } ?: "")),
                        "where" to "${spec.world} ${spec.x.toInt()}, ${spec.y.toInt()}, ${spec.z.toInt()}"))
                }
            }
            "create" -> {
                if (sender !is Player) { sender.sendMessage(lang.msg("command.error.not_a_player")); return }
                val type = net.badgersmc.ek.infrastructure.display.HologramType.parse(args.getOrNull(3))
                if (name == null || type == null) { sender.sendMessage(lang.msg("hologram.usage")); return }
                if (!net.badgersmc.ek.infrastructure.display.KothHologramService.validName(name)) { sender.sendMessage(lang.msg("hologram.bad_name")); return }
                val arena = args.getOrNull(4)?.let { requested -> arenas().keys.firstOrNull { it.equals(requested, true) } ?: requested }
                if (type.needsArena && (arena == null || arena !in arenas())) { sender.sendMessage(lang.msg("hologram.needs_arena")); return }
                val at = sender.location
                val result = service.create(net.badgersmc.ek.infrastructure.display.HologramSpec(name, type, at.world.name,
                    at.blockX + 0.5, at.y + 2.0, at.blockZ + 0.5, arena.takeIf { type.needsArena },
                    if (type == net.badgersmc.ek.infrastructure.display.HologramType.CUSTOM) listOf("<color:#ffcf1f><bold>%enthusiakoth_next_name%", "<color:#fff6e0>Starts in <color:#ffcf1f>%enthusiakoth_next_koth_time%") else emptyList()))
                sender.sendMessage(hologramReply(result, "hologram.created", name.lowercase(), type.id))
            }
            "move" -> {
                if (sender !is Player) { sender.sendMessage(lang.msg("command.error.not_a_player")); return }
                val at = sender.location.clone().add(0.0, 2.0, 0.0)
                sender.sendMessage(if (name == null) lang.msg("hologram.usage") else hologramReply(service.move(name, at), "hologram.moved", name))
            }
            "remove", "delete" -> sender.sendMessage(if (name == null) lang.msg("hologram.usage") else hologramReply(service.remove(name), "hologram.removed", name))
            else -> sender.sendMessage(lang.msg("hologram.usage"))
        }
    }

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (args.isEmpty()) {
            if (sender is Player && progression != null && sender.hasPermission("enthusiakoth.command")) progression.command(sender,arrayOf("home"))
            else sendHelp(sender)
            return true
        }
        when (args[0].lowercase()) {
            "help" -> sendHelp(sender, args.getOrNull(1).equals("all", true))
            "reply" -> if (sender !is Player) sender.sendMessage(lang.msg("command.error.not_a_player")) else {
                val text = args.drop(1).joinToString(" ")
                val setupAt = setup?.promptOpenedAt(sender.uniqueId)
                val settingsAt = settings?.promptOpenedAt(sender.uniqueId)
                // With both prompts pending, the one opened last is the one the player is answering.
                val answered = when {
                    text.isBlank() -> { sender.sendMessage(lang.msg("command.reply.usage")); true }
                    settingsAt != null && (setupAt == null || settingsAt > setupAt) -> settings?.answer(sender, text) == true
                    setupAt != null -> setup?.answer(sender, text) == true
                    else -> false
                }
                if (!answered) sender.sendMessage(lang.msg("command.reply.none"))
            }
            "history", "reports", "readiness", "reconcile", "holds", "reviewmatch", "claims", "challenges", "eligibility", "results" -> progression?.command(sender,args.mapIndexed { i,s -> if(i==0) s.lowercase() else s }.toTypedArray())
            "manage", "schedules", "rewards", "displays" -> if (sender is Player) {
                if (!sender.hasPermission("enthusiakoth.admin")) { sender.sendMessage(lang.msg("command.error.no_permission")); return true }
                if (args.getOrNull(1) == "cancel") settings?.cancel(sender)
                else if(setup?.releaseForManagement(sender) != false) settings?.open(sender, args.getOrNull(1), when(args[0].lowercase()) {
                    "schedules" -> StaffSettingsPage.SCHEDULE; "rewards" -> StaffSettingsPage.REWARDS
                    "displays" -> StaffSettingsPage.DISPLAYS; else -> StaffSettingsPage.HOME
                })
            } else sender.sendMessage(lang.msg("command.error.not_a_player"))
            "info" -> arenaInfo(sender, args.getOrNull(1))
            "tp" -> arenaTeleport(sender, args.getOrNull(1))
            "gui" -> gui(sender)
            "schedule" -> schedule(sender)
            "blitz" -> blitz(sender)
            "hologram", "holo" -> hologram(sender, args)
            "leaderboard", "lb", "top" -> top(sender, args.getOrNull(2)?.toIntOrNull() ?: args.getOrNull(1)?.toIntOrNull() ?: 1, args.getOrNull(1)?.takeIf { it.toIntOrNull() == null } ?: "lifetime")
            "stats" -> stats(sender, args.getOrNull(1))
            "start" -> start(sender, args.getOrNull(1) ?: "", args.drop(2))
            "stop", "cancel" -> stop(sender, args.getOrNull(1))
            "giveflare" -> giveFlare(sender, args.getOrNull(1) ?: "", args.getOrNull(2) ?: "", args.getOrNull(3)?.toIntOrNull() ?: 1)
            "reload" -> doReload(sender)
            "private", "test" -> privateTest(sender, args)
            "status" -> status(sender)
            "lock" -> lock(sender, args.getOrNull(1) ?: "")
            "arena" -> arena(sender, args)
            "setup", "editor" -> if (sender is Player) {
                if (!sender.hasPermission("enthusiakoth.admin")) { sender.sendMessage(lang.msg("command.error.no_permission")); return true }
                if (args.getOrNull(1) == "cancel") setup?.cancel(sender)
                else if(settings?.releaseForSetup(sender) != false) setup?.open(sender, args.getOrNull(1))
            } else sender.sendMessage(lang.msg("command.error.not_a_player"))
            "wand" -> if (sender is Player) setup?.giveWand(sender) else sender.sendMessage(lang.msg("command.error.not_a_player"))
            "notifications", "messages" -> {
                if (sender !is Player) sender.sendMessage(lang.msg("command.error.not_a_player"))
                else sender.sendMessage(lang.msg(if (kothService.toggleNotification(sender)) "command.notifications.enabled" else "command.notifications.disabled"))
            }
            else -> sendHelp(sender)
        }
        return true
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): MutableList<String> {
        hologramTabOptions(sender, args)?.let { return it.toMutableList() }
        val admin = sender.hasPermission("enthusiakoth.admin")
        val arenaIds = arenas().keys.sorted()
        val players = { Bukkit.getOnlinePlayers().map { it.name }.sorted() }
        val sub = args.getOrNull(0)?.lowercase() ?: ""
        val privateStart = (sub == "private" || sub == "test") && args.getOrNull(1).equals("start", true) && canStartPrivate(sender)
        // One spelling per command is suggested; aliases such as cancel, editor, test and messages still work.
        val options: List<String> = when (args.size) {
            1 -> buildList {
                addAll(listOf("gui", "schedule", "blitz", "leaderboard", "stats", "notifications", "claims", "challenges", "results", "eligibility", "help"))
                if (canStartFromCommand(sender)) add("start")
                if (canStartPrivate(sender) || canJoinPrivate(sender)) add("private")
                if (admin) addAll(listOf("setup", "manage", "schedules", "rewards", "displays", "stop", "status", "lock", "holds", "reviewmatch",
                    "reports", "reconcile", "history", "readiness", "giveflare", "info", "tp", "arena", "wand", "hologram", "reply", "reload"))
            }
            2 -> when (sub) {
                "start" -> if (canStartFromCommand(sender)) arenaIds else emptyList()
                "leaderboard", "lb", "top" -> listOf("daily", "weekly", "season", "lifetime")
                "stats" -> players()
                "help" -> if (admin) listOf("all") else emptyList()
                "private", "test" -> buildList {
                    if (canStartPrivate(sender)) addAll(listOf("start", "cancel"))
                    if (canJoinPrivate(sender)) addAll(listOf("join", "leave"))
                }
                "lock" -> if (admin) LockState.entries.map { it.name.lowercase() } else emptyList()
                "arena" -> if (admin) listOf("create", "region", "center", "enable", "disable") else emptyList()
                "setup", "editor", "manage", "schedules", "rewards", "displays" -> if (admin) arenaIds + "cancel" else emptyList()
                "info", "tp", "stop", "cancel" -> if (admin) arenaIds else emptyList()
                "giveflare" -> if (admin) players() else emptyList()
                "reviewmatch" -> if (admin) progression?.heldMatchIds().orEmpty() else emptyList()
                "reports" -> if (admin) progression?.recentMatchIds().orEmpty() else emptyList()
                "reconcile" -> if (admin) progression?.reviewClaimIds().orEmpty() else emptyList()
                "reply" -> if (admin) listOf("cancel", "-") else emptyList()
                else -> emptyList()
            }
            3 -> when {
                sub == "start" && canStartFromCommand(sender) -> listOf("solo", "guild") + startTiers(sender)
                !admin && !privateStart -> emptyList()
                sub == "giveflare" -> arenaIds
                sub == "arena" && !args[1].equals("create", true) -> arenaIds
                sub == "reviewmatch" -> listOf("approve", "reject")
                sub == "reconcile" -> listOf("paid", "retry")
                privateStart -> arenaIds
                else -> emptyList()
            }
            4 -> when {
                sub == "start" && canStartFromCommand(sender) ->
                    if (args[2].lowercase() in setOf("basic", "advanced")) listOf("solo", "guild") else startTiers(sender)
                privateStart -> listOf("solo", "guild")
                !admin -> emptyList()
                sub == "giveflare" -> listOf("1", "5", "10")
                sub == "arena" && args[1].equals("region", true) && sender is Player -> arenaRegionSuggestions(sender.world.name)
                sub == "arena" && args[1].equals("create", true) -> listOf("capture", "moving", "conquest", "score")
                else -> emptyList()
            }
            5 -> if (privateStart) listOf("self", "staff") else emptyList()
            6 -> if (privateStart) listOf("quick", "production") else emptyList()
            else -> emptyList()
        }
        return options.filter { it.startsWith(args.last(), true) }.toMutableList()
    }

    /** Advanced starts need their own permission; basic is open to anyone who may start. */
    private fun startTiers(sender: CommandSender): List<String> = buildList {
        add("basic")
        if (sender.hasPermission("enthusiakoth.start.advanced") || sender.hasPermission("enthusiakoth.admin")) add("advanced")
    }

    private fun canStartFromCommand(sender: CommandSender): Boolean = sender.hasPermission("enthusiakoth.start.basic") ||
        sender.hasPermission("enthusiakoth.start.advanced") || sender.hasPermission("enthusiakoth.admin")

    /** Each line can be clicked to put the command in the chat box, ready to finish. */
    private fun sendHelp(sender: CommandSender, detailed: Boolean = false) {
        sender.sendMessage(lang.msg("command.help.header"))
        fun row(usage: String, description: String) {
            val typed = usage.substringBefore(" <").substringBefore(" [") + " "
            sender.sendMessage(Component.text(usage, MenuStyle.GOLD)
                .clickEvent(net.kyori.adventure.text.event.ClickEvent.suggestCommand(typed))
                .hoverEvent(net.kyori.adventure.text.event.HoverEvent.showText(Component.text("Click to type this command")))
                .append(Component.text(" - $description", MenuStyle.CREAM)))
        }
        row("/ekoth", "Open the KOTH menu")
        row("/ekoth gui", "See the arenas and start one")
        row("/ekoth schedule", "When the next events start")
        row("/ekoth blitz", "The next weekend blitz, round by round")
        row("/ekoth leaderboard [daily|weekly|season]", "Leaderboard")
        row("/ekoth stats [player]", "KOTH wins")
        row("/ekoth notifications", "Turn KOTH chat messages on or off")
        if (canStartFromCommand(sender)) row("/ekoth start <arena> [solo|guild] [basic|advanced]", "Start an arena")
        if (canStartPrivate(sender) || canJoinPrivate(sender)) row("/ekoth private <start|join|leave|cancel>", "Private test events")
        if (!sender.hasPermission("enthusiakoth.admin")) return
        row("/ekoth setup [arena]", "Create or edit arenas")
        row("/ekoth manage [arena]", "Schedules, rewards and displays")
        row("/ekoth stop [arena]", "End a running KOTH")
        row("/ekoth hologram <create|move|remove|list>", "Place live KOTH holograms")
        row("/ekoth holds", "Matches waiting for review")
        if (!detailed) { row("/ekoth help all", "Every staff command"); return }
        row("/ekoth reviewmatch <match> <approve|reject> <note>", "Decide a held match")
        row("/ekoth reports <match>", "Full report for one match")
        row("/ekoth history", "Recent matches")
        row("/ekoth reconcile <claim> <paid|retry> <note>", "Settle a payout that needs review")
        row("/ekoth status", "What's running and what's next")
        row("/ekoth lock <unlocked|manual_locked|all_locked>", "Allow or block starts")
        row("/ekoth giveflare <player> <arena> [amount]", "Give KOTH flares")
        row("/ekoth info <arena>", "Arena details")
        row("/ekoth tp <arena>", "Teleport to an arena")
        row("/ekoth arena <create|region|center|enable|disable> <arena>", "Quick arena changes")
        row("/ekoth readiness", "Check the setup before TEST")
        row("/ekoth reload", "Reload the configuration")
    }

    private fun gui(sender: CommandSender, mode: TeamMode = TeamMode.SOLO) {
        if (sender !is Player) {
            sender.sendMessage(lang.msg("command.error.not_a_player"))
            return
        }
        val allArenaIds = arenas().keys.sorted()
        val size = ((allArenaIds.size + 1 + 8) / 9).coerceIn(1, 6) * 9
        val modeSlot = size - 1
        val arenaIds = allArenaIds.take(modeSlot)
        val holder = KothGuiHolder(arenaIds, modeSlot, mode)
        val inventory = Bukkit.createInventory(holder, size, lang.msg("command.gui.title"))
        holder.backingInventory = inventory
        val canStart = canStartFromMenu(sender)
        arenaIds.forEachIndexed { slot, id -> inventory.setItem(slot, arenaItem(id, canStart)) }
        inventory.setItem(modeSlot, teamModeItem(holder.teamMode))
        MenuStyle.fill(inventory)
        sender.openInventory(inventory)
    }

    private fun canStartFromMenu(player: Player): Boolean = cfgLoader().manualStart.enabled &&
        (player.hasPermission("enthusiakoth.start.basic") || player.hasPermission("enthusiakoth.start.advanced"))

    /** Running arenas glow and show who holds them; idle ones say whether a click starts them. */
    private fun arenaItem(id: String, canStart: Boolean): ItemStack {
        val arena = arenas()[id]
        val family = arena?.family ?: "capture"
        val name = (arena?.name ?: id).arenaComponent()
        val event = kothService.eventForArena(id)
        val lore = if (event != null) listOf(
            lang.msg("command.gui.lore_holding", "capper" to kothService.capperName(event)),
            lang.msg("command.gui.lore_time_left", "time" to formatTime(event.endsAt.epochSecond - System.currentTimeMillis() / 1000)),
            lang.msg("command.gui.hint_running"),
        ) else listOf(
            lang.msg("command.gui.lore_mode", "mode" to MenuStyle.label(family)),
            lang.msg(if (canStart) "command.gui.hint_start" else "command.gui.hint_view_only"),
        )
        return MenuStyle.item(MenuStyle.familyIcon(family),
            lang.arenaMsg(if (event != null) "command.gui.item_name_active" else "command.gui.item_name_inactive", "id" to name), lore, glow = event != null)
    }

    private fun teamModeItem(mode: TeamMode): ItemStack = MenuStyle.item(
        if (mode == TeamMode.SOLO) Material.PLAYER_HEAD else Material.SHIELD,
        lang.msg("command.gui.mode_name", "mode" to MenuStyle.label(mode.name)),
        listOf(lang.msg("command.gui.mode_lore")),
    )

    /** Starting can cost money, so a click opens a confirmation instead of starting at once. */
    private fun confirmStart(player: Player, arenaId: String, mode: TeamMode) {
        val holder = KothStartConfirmHolder(arenaId, mode)
        val name = (arenas()[arenaId]?.name ?: arenaId).arenaComponent()
        val inventory = Bukkit.createInventory(holder, 27, lang.arenaMsg("command.gui.confirm_title", "arena" to name))
        holder.backingInventory = inventory
        val cost = cfgLoader().manualStart.basicCost.coerceAtLeast(0.0)
        inventory.setItem(KothStartConfirmHolder.START_SLOT, MenuStyle.item(Material.LIME_CONCRETE,
            lang.arenaMsg("command.gui.confirm_start", "arena" to name), listOf(
                lang.msg("command.gui.confirm_teams", "mode" to MenuStyle.label(mode.name)),
                if (cost > 0.0) lang.msg("command.gui.confirm_cost", "cost" to "%.2f".format(cost)) else lang.msg("command.gui.confirm_free"),
            )))
        inventory.setItem(KothStartConfirmHolder.BACK_SLOT, MenuStyle.item(Material.RED_CONCRETE,
            lang.msg("command.gui.confirm_back"), listOf(lang.msg("command.gui.confirm_back_hint"))))
        MenuStyle.fill(inventory)
        player.openInventory(inventory)
    }

    private fun schedule(sender: CommandSender) {
        val cfg = cfgLoader()
        if (!cfg.schedule.enabled) {
            sender.sendMessage(lang.msg("command.error.schedule_disabled"))
            return
        }
        val now = ZonedDateTime.now(cfg.schedule.zone)
        sender.sendMessage(lang.msg("command.schedule.header"))
        sender.sendMessage(lang.msg("command.schedule.time_now", "time" to now.format(DateTimeFormatter.ofPattern("HH:mm"))))
        sender.sendMessage(lang.msg("command.schedule.timezone", "zone" to cfg.schedule.zone.id))
        sender.sendMessage(Component.empty())
        val resolved = scheduleService.occurrencesForDate(now.toLocalDate())
        val grouped = resolved.groupBy { it.arenaId }
        grouped.forEach { (arenaId, occurrences) ->
            sender.sendMessage(lang.arenaMsg("command.schedule.entry", "name" to (arenas()[arenaId]?.name ?: arenaId).arenaComponent()))
            occurrences.forEach { occurrence ->
                val time = occurrence.instant.atZone(cfg.schedule.zone).format(DateTimeFormatter.ofPattern("HH:mm"))
                sender.sendMessage(lang.msg("command.schedule.entry_time", "time" to time))
            }
        }
    }

    /** The next weekend blitz: when it opens and which arena each round is in. */
    private fun blitz(sender: CommandSender) {
        val cfg = cfgLoader()
        val rounds = scheduleService.nextBlitz()
        if (!cfg.schedule.enabled || !cfg.schedule.blitz.enabled || rounds.isEmpty()) {
            sender.sendMessage(lang.msg("command.blitz.none"))
            return
        }
        val zone = cfg.schedule.zone
        val now = java.time.Instant.now()
        val first = rounds.first().instant.atZone(zone)
        sender.sendMessage(lang.msg("command.blitz.header"))
        sender.sendMessage(lang.msg("command.blitz.summary",
            "day" to first.dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.ENGLISH),
            "time" to first.format(DateTimeFormatter.ofPattern("HH:mm")), "zone" to zone.id,
            "rounds" to rounds.size.toString(), "length" to formatTime(cfg.schedule.blitz.roundSeconds.toLong()),
            "capture" to formatTime(cfg.schedule.blitz.captureSeconds.toLong())))
        rounds.forEachIndexed { index, occurrence ->
            val state = net.badgersmc.ek.application.BlitzRound.status(now, occurrence.instant, cfg.schedule.blitz.roundSeconds,
                kothService.isOccurrenceLive(occurrence.id)).name.lowercase()
            sender.sendMessage(lang.arenaMsg("command.blitz.round_$state", "round" to (index + 1).toString(),
                "arena" to (arenas()[occurrence.arenaId]?.name ?: occurrence.arenaId).arenaComponent(),
                "time" to occurrence.instant.atZone(zone).format(DateTimeFormatter.ofPattern("HH:mm"))))
        }
    }

    private val leaderboardDialog by lazy {
        LeaderboardDialog(plugin,
            wins = { period ->
                if (period == "lifetime") stats.allWins()
                else net.badgersmc.ek.application.LeaderboardWindow.forPeriod(period, java.time.Instant.now(), cfgLoader().timezone, cfgLoader().seasonStart)?.let(stats::winsIn)
            },
            periods = { listOfNotNull("daily", "weekly", "season".takeIf { cfgLoader().seasonStart != null }, "lifetime") },
            name = ::formatStatUser,
            head = ::leaderboardHead,
            art = {
                when (plugin.config.getString("leaderboards.dialog-art", "auto")?.lowercase()) {
                    "on", "true" -> true
                    "off", "false" -> false
                    else -> Bukkit.getPluginManager().isPluginEnabled("Nexo")
                }
            })
    }

    /**
     * A player's head as an inline text object. The skin comes from the server's copy of their profile when it has one
     * (online, or cached from an earlier join); otherwise the client looks it up by name.
     */
    private fun leaderboardHead(key: String): Component? {
        if (!key.startsWith("solo:")) return null
        val id = runCatching { UUID.fromString(key.substringAfter(':')) }.getOrNull() ?: return null
        val profile = (Bukkit.getPlayer(id) ?: Bukkit.getOfflinePlayer(id)).playerProfile
        val head = net.kyori.adventure.text.`object`.ObjectContents.playerHead().id(id)
        val name = profile.name ?: formatStatUser(key)
        if (name.length in 1..16 && name.all { it.isLetterOrDigit() || it == '_' }) head.name(name)
        profile.properties.filter { it.name == "textures" }.forEach { property ->
            head.profileProperty(net.kyori.adventure.text.`object`.PlayerHeadObjectContents.property(property.name, property.value, property.signature))
        }
        return Component.`object`(head.build())
    }

    private fun top(sender: CommandSender, page: Int, period: String = "lifetime") {
        if (sender is Player) {
            if (period != "lifetime" && net.badgersmc.ek.application.LeaderboardWindow.forPeriod(period, java.time.Instant.now(), cfgLoader().timezone, cfgLoader().seasonStart) == null) {
                sender.sendMessage(lang.msg("staff.error.period")); return
            }
            leaderboardDialog.open(sender, LeaderboardDialog.View(period.lowercase(), LeaderboardSide.PLAYERS, page))
            return
        }
        val window = net.badgersmc.ek.application.LeaderboardWindow.forPeriod(period, java.time.Instant.now(), cfgLoader().timezone, cfgLoader().seasonStart)
        if (period != "lifetime" && window == null) { sender.sendMessage(lang.msg("staff.error.period")); return }
        val wins = if (window == null) stats.allWins() else stats.winsIn(window)
        val maximum = ((wins.size + 9) / 10).coerceAtLeast(1)
        val selected = page.coerceIn(1, maximum)
        sender.sendMessage(lang.msg("command.top.header"))
        sender.sendMessage(lang.msg("staff.standings", "value" to period))
        wins.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key }).drop((selected - 1) * 10).take(10)
            .forEachIndexed { offset, entry ->
                sender.sendMessage(
                    lang.msg(
                        "command.top.entry",
                        "rank" to ((selected - 1) * 10 + offset + 1).toString(),
                        "player" to formatStatUser(entry.key),
                        "wins" to entry.value.toString(),
                    ),
                )
            }
    }

    private fun arenaInfo(sender: CommandSender, id: String?) {
        if (!sender.hasPermission("enthusiakoth.admin")) { sender.sendMessage(lang.msg("command.error.no_permission")); return }
        val arena = cfgLoader().arenas[id] ?: run { sender.sendMessage(lang.msg("staff.error.arena")); return }
        sender.sendMessage(lang.arenaMsg("staff.info", "value" to net.badgersmc.ek.domain.ArenaName.resolve(id!!, arena.displayName).arenaComponent().append(Component.text(" (ID: $id) | ${arena.family} | enabled=${arena.enabled} | ${arena.world} ${arena.center.x}, ${arena.center.y}, ${arena.center.z}"))))
        sender.sendMessage(lang.msg("staff.info", "value" to "duration=${arena.durationSeconds}s | capture=${arena.captureSeconds}s | leave=${arena.leaveBehavior} | inventory=${arena.keepInventory} | XP=${arena.keepExperience}"))
        sender.sendMessage(lang.msg("staff.info", "value" to "region=${arena.worldGuardRegion ?: "native"} | radius=${arena.radius} | schedule=${arena.schedule} | ${cfgLoader().timezone.id}"))
        sender.sendMessage(lang.msg("staff.info", "value" to "money=${cfgLoader().rewards[arena.rewardFamily ?: arena.family]} | fixed=${arena.rewards} | chance=${arena.chancedRewards}"))
        sender.sendMessage(lang.msg("staff.info", "value" to "rules=${cfgLoader().rules.rules[arena.family]} | event=${kothService.eventForArena(id!!)?.state ?: "idle"}"))
    }

    private fun arenaTeleport(sender: CommandSender, id: String?) {
        if (!sender.hasPermission("enthusiakoth.admin")) { sender.sendMessage(lang.msg("command.error.no_permission")); return }
        if (sender !is Player) { sender.sendMessage(lang.msg("command.error.not_a_player")); return }
        val arena = cfgLoader().arenas[id] ?: run { sender.sendMessage(lang.msg("staff.error.arena")); return }
        val world = Bukkit.getWorld(arena.world) ?: run { sender.sendMessage(lang.msg("staff.error.teleport")); return }
        val origin = Location(world, arena.center.x, arena.center.y, arena.center.z)
        val offsets = listOf(0) + (1..8).flatMap { listOf(it, -it) }
        val hazardous = setOf(Material.LAVA, Material.FIRE, Material.SOUL_FIRE, Material.MAGMA_BLOCK, Material.CACTUS,
            Material.CAMPFIRE, Material.SOUL_CAMPFIRE, Material.POWDER_SNOW, Material.SWEET_BERRY_BUSH, Material.WATER)
        val target = offsets.map { origin.clone().add(0.0, it.toDouble(), 0.0) }.firstOrNull { location ->
            if (location.blockY <= world.minHeight || location.blockY + 1 >= world.maxHeight) false
            else {
                val feet = location.block; val head = location.clone().add(0.0, 1.0, 0.0).block
                val floor = location.clone().add(0.0, -1.0, 0.0).block
                feet.isPassable && head.isPassable && floor.type.isSolid &&
                    feet.type !in hazardous && head.type !in hazardous && floor.type !in hazardous
            }
        } ?: run { sender.sendMessage(lang.msg("staff.error.teleport")); return }
        // Normal Bukkit teleport honors companion cancellation; no invulnerability or combat bypass.
        sender.sendMessage(lang.msg(if (sender.teleport(target, org.bukkit.event.player.PlayerTeleportEvent.TeleportCause.COMMAND)) "staff.teleported" else "staff.error.teleport"))
    }

    private fun stats(sender: CommandSender, target: String?) {
        val player = target?.let(Bukkit::getPlayerExact) ?: sender as? Player
        if (player == null) {
            sender.sendMessage(lang.msg("command.error.player_not_found"))
            return
        }
        val wins = stats.totalWins("solo:${player.uniqueId}")
        sender.sendMessage(
            if (sender == player) lang.msg("command.error.stats_self", "wins" to wins.toString())
            else lang.msg("command.error.stats_other", "player" to player.name, "wins" to wins.toString()),
        )
    }

    private data class ParsedStartOptions(val tier: StartTier?, val teamMode: TeamMode)

    private fun start(sender: CommandSender, arenaId: String, optionArgs: List<String>) {
        val arena = arenas()[arenaId]
        if (arena == null) {
            sender.sendMessage(lang.msg("command.error.koth_not_found", "koths" to arenas().keys.joinToString(", ")))
            return
        }
        val options = parseStartOptions(sender, optionArgs) ?: return
        val source = when {
            sender !is Player -> StartSource.CONSOLE
            sender.hasPermission("enthusiakoth.admin") -> StartSource.ADMIN_COMMAND
            else -> StartSource.PLAYER_COMMAND
        }
        sendStartResult(
            sender,
            arenaId,
            startService.start(
                StartRequest(
                    actor = startActor(sender),
                    arena = arena,
                    source = source,
                    tier = options.tier,
                    teamMode = options.teamMode,
                ),
            ),
            false,
        )
    }

    private fun parseStartOptions(sender: CommandSender, optionArgs: List<String>): ParsedStartOptions? {
        var tier: StartTier? = null
        var teamMode = TeamMode.SOLO
        var modeSpecified = false
        for (raw in optionArgs.filter { it.isNotBlank() }) {
            when (raw.lowercase()) {
                "basic" -> if (tier == null) tier = StartTier.BASIC else return invalidStartOptions(sender)
                "advanced" -> if (tier == null) tier = StartTier.ADVANCED else return invalidStartOptions(sender)
                "solo" -> if (!modeSpecified) {
                    teamMode = TeamMode.SOLO
                    modeSpecified = true
                } else return invalidStartOptions(sender)
                "guild" -> if (!modeSpecified) {
                    teamMode = TeamMode.GUILD
                    modeSpecified = true
                } else return invalidStartOptions(sender)
                else -> return invalidStartOptions(sender)
            }
        }
        return ParsedStartOptions(tier, teamMode)
    }

    private fun invalidStartOptions(sender: CommandSender): ParsedStartOptions? {
        sender.sendMessage(lang.msg("command.error.invalid_start_options"))
        return null
    }

    private fun startActor(sender: CommandSender) = StartActor(
        playerId = (sender as? Player)?.uniqueId,
        isConsole = sender !is Player,
        isAdmin = sender.hasPermission("enthusiakoth.admin"),
        canStartBasic = sender.hasPermission("enthusiakoth.start.basic"),
        canStartAdvanced = sender.hasPermission("enthusiakoth.start.advanced"),
        canUseFlare = sender.hasPermission("enthusiakoth.flare.use"),
    )

    private fun sendStartResult(sender: CommandSender, arenaId: String, result: StartResult, fromGui: Boolean) {
        when (result) {
            is StartResult.Started -> sender.sendMessage(
                lang.arenaMsg(if (fromGui) "command.success.started_from_gui" else "command.success.started", "arena" to (arenas()[arenaId]?.name ?: arenaId).arenaComponent()),
            )
            is StartResult.Rejected -> when (result.failure) {
                StartFailure.STARTER_COOLDOWN -> sender.sendMessage(lang.msg("command.error.starter_cooldown"))
                StartFailure.INSUFFICIENT_TEAMS -> sender.sendMessage(lang.msg("command.error.insufficient_teams"))
                StartFailure.COOLDOWN_STATE_FAILED -> sender.sendMessage(lang.msg("command.error.cooldown_state_failed"))
                StartFailure.NO_PERMISSION -> sender.sendMessage(lang.msg("command.error.no_permission_start"))
                StartFailure.FEATURE_DISABLED -> sender.sendMessage(lang.msg("command.error.manual_start_disabled"))
                StartFailure.LOCKED -> sender.sendMessage(lang.msg("command.error.locked"))
                StartFailure.ALREADY_ACTIVE -> sender.sendMessage(lang.msg("command.error.already_active"))
                StartFailure.ECONOMY_UNAVAILABLE -> sender.sendMessage(lang.msg("command.error.economy_unavailable"))
                StartFailure.INSUFFICIENT_FUNDS -> sender.sendMessage(
                    lang.msg("command.error.insufficient_funds", "cost" to result.cost.toString(), "balance" to (result.balance ?: 0.0).toString()),
                )
                StartFailure.REFUND_FAILED -> sender.sendMessage(lang.msg("command.error.refund_failed"))
                StartFailure.CONCURRENT_REQUEST -> sender.sendMessage(lang.msg("command.error.concurrent_start"))
                StartFailure.INVALID_ARENA -> sender.sendMessage(lang.msg("command.error.koth_not_found_short"))
                else -> sender.sendMessage(lang.msg("command.error.payment_failed"))
            }
        }
    }

    private fun stop(sender: CommandSender, arenaId: String? = null) {
        if (!sender.hasPermission("enthusiakoth.admin")) {
            sender.sendMessage(lang.msg("command.error.no_permission"))
            return
        }
        if (arenaId == null && kothService.allEvents().size > 1) {
            sender.sendMessage(lang.msg("staff.stop-select")); return
        }
        if (!kothService.forceEnd(arenaId = arenaId)) {
            sender.sendMessage(lang.msg("command.error.no_active"))
        } else if (kothService.lastCancellationRefundPending) {
            sender.sendMessage(lang.msg("command.error.refund_failed"))
        } else {
            sender.sendMessage(lang.msg("command.success.ended"))
        }
    }

    private fun giveFlare(sender: CommandSender, playerName: String, arenaId: String, amount: Int) {
        if (!sender.hasPermission("enthusiakoth.admin")) {
            sender.sendMessage(lang.msg("command.error.no_permission"))
            return
        }
        val target = Bukkit.getPlayerExact(playerName) ?: run {
            sender.sendMessage(lang.msg("command.error.player_not_found"))
            return
        }
        val arena = arenas()[arenaId] ?: run {
            sender.sendMessage(lang.msg("command.error.koth_not_found_short"))
            return
        }
        val flare = flareService.createFlare(arena).apply { this.amount = amount.coerceAtLeast(1) }
        sender.sendMessage(lang.arenaMsg("command.error.flare_given", "player" to target.name, "amount" to flare.amount.toString(), "koth" to arena.name.arenaComponent()))
        target.sendMessage(lang.arenaMsg("command.error.flare_received", "amount" to flare.amount.toString(), "koth" to arena.name.arenaComponent()))
        target.inventory.addItem(flare).values.forEach { target.world.dropItem(target.location, it) }
    }

    private fun doReload(sender: CommandSender) {
        if (!sender.hasPermission("enthusiakoth.admin")) {
            sender.sendMessage(lang.msg("command.error.no_permission"))
            return
        }
        reloadAction()
        if (kothService.lastCancellationRefundPending) {
            sender.sendMessage(lang.msg("command.error.refund_failed"))
        }
        sender.sendMessage(lang.msg("command.error.reloaded"))
    }

    private fun status(sender: CommandSender) {
        if (!sender.hasPermission("enthusiakoth.admin")) {
            sender.sendMessage(lang.msg("command.error.no_permission"))
            return
        }
        val event = kothService.activeEvent
        val activeNames = kothService.allEvents().foldIndexed(Component.empty()) { index, names, active ->
            names.append(Component.text(if (index == 0) "" else ", "))
                .append(active.arena.name.arenaComponent()).append(Component.text(" (${active.arena.id})"))
        }
        sender.sendMessage(lang.arenaMsg("staff.active-list", "value" to activeNames))
        val line = lang.msg("command.status.line")
        sender.sendMessage(line)
        sender.sendMessage(lang.msg("command.status.header"))
        sender.sendMessage(lang.msg("command.status.lock", "state" to cfgLoader().locks.state.name.lowercase()))
        sender.sendMessage(lang.msg("command.status.active", "arena" to (event?.arena?.id ?: "None")))
        if (event != null) {
            sender.sendMessage(lang.msg("command.status.capper", "capper" to (kothService.capperName(event) ?: "None")))
            sender.sendMessage(lang.msg("command.status.time_left", "time" to formatTime(event.endsAt.epochSecond - System.currentTimeMillis() / 1000)))
        }
        scheduleService.nextScheduledStart()?.let { next ->
            val seconds = java.time.Duration.between(java.time.Instant.now(), next).toSeconds().coerceAtLeast(0)
            sender.sendMessage(lang.msg("command.status.next_scheduled", "time" to formatTime(seconds)))
        }
        sender.sendMessage(line)
    }

    private fun lock(sender: CommandSender, stateArg: String) {
        if (!sender.hasPermission("enthusiakoth.admin")) {
            sender.sendMessage(lang.msg("command.error.no_permission"))
            return
        }
        val state = when (stateArg.lowercase()) {
            "off" -> LockState.UNLOCKED
            "manual" -> LockState.MANUAL_LOCKED
            "all" -> LockState.ALL_LOCKED
            else -> runCatching { LockState.valueOf(stateArg.uppercase()) }.getOrNull()
        }
        if (state == null) {
            sender.sendMessage(lang.msg("command.error.invalid_lock", "states" to LockState.entries.joinToString(", ") { it.name.lowercase() }))
            return
        }
        lockAction(state)
        sender.sendMessage(lang.msg("command.error.lock_set", "state" to state.name.lowercase()))
    }

    private fun arena(sender: CommandSender, args: Array<out String>) {
        if (!sender.hasPermission("enthusiakoth.admin")) {
            sender.sendMessage(lang.msg("command.error.no_permission"))
            return
        }
        if (sender !is Player) {
            sender.sendMessage(lang.msg("command.error.not_a_player"))
            return
        }
        val action = args.getOrNull(1)?.lowercase()
        val arenaId = args.getOrNull(2)
        if (action == "create" && arenaId != null) {
            setup?.create(sender, arenaId, args.getOrNull(3)?.lowercase() ?: "capture")
            return
        }
        if (action == null || arenaId == null || arenaId !in cfgLoader().arenas) {
            sendArenaUsage(sender)
            return
        }
        val message = when (action) {
            "region" -> {
                val regionId = args.getOrNull(3)
                if (regionId == null) {
                    sendArenaUsage(sender)
                    return
                }
                arenaRegionAction(arenaId, sender.world.name, regionId)
            }
            "center" -> arenaCenterAction(arenaId, sender.location)
            "enable" -> arenaEnabledAction(arenaId, true)
            "disable" -> arenaEnabledAction(arenaId, false)
            else -> {
                sendArenaUsage(sender)
                return
            }
        }
        sender.sendMessage(Component.text(message))
    }

    private fun sendArenaUsage(sender: CommandSender) {
        sender.sendMessage(Component.text("/ekoth arena region <arena> <worldguard-region>"))
        sender.sendMessage(Component.text("/ekoth arena center <arena>"))
        sender.sendMessage(Component.text("/ekoth arena enable <arena>"))
        sender.sendMessage(Component.text("/ekoth arena disable <arena>"))
    }

    private fun privateTest(sender: CommandSender, args: Array<out String>) {
        if (sender !is Player) {
            sender.sendMessage(lang.msg("command.error.not_a_player"))
            return
        }
        when (args.getOrNull(1)?.lowercase()) {
            "start" -> if (canStartPrivate(sender)) privateStart(sender, args) else sender.sendMessage(lang.msg("command.error.no_permission"))
            "cancel" -> if (canStartPrivate(sender)) privateCancel(sender) else sender.sendMessage(lang.msg("command.error.no_permission"))
            "join" -> if (canJoinPrivate(sender)) privateJoin(sender) else sender.sendMessage(lang.msg("command.error.no_permission"))
            "leave" -> privateLeave(sender)
            else -> sender.sendMessage(lang.msg("private.usage"))
        }
    }

    private fun canStartPrivate(sender: CommandSender): Boolean =
        sender.hasPermission("enthusiakoth.admin") ||
            sender.hasPermission("enthusiakoth.test.start") ||
            sender.hasPermission("enthusiakoth.privatetest")

    private fun canJoinPrivate(sender: CommandSender): Boolean =
        sender.hasPermission("enthusiakoth.admin") || sender.hasPermission("enthusiakoth.test.join")

    private fun privateStart(player: Player, args: Array<out String>) {
        val arenaId = args.getOrNull(2)
        val teamMode = when (args.getOrNull(3)?.lowercase()) {
            "solo" -> TeamMode.SOLO
            "guild" -> TeamMode.GUILD
            else -> {
                player.sendMessage(lang.msg("private.usage"))
                return
            }
        }
        val access = when (args.getOrNull(4)?.lowercase()) {
            "self" -> PrivateTestAccess.OWNER_ONLY
            "staff" -> PrivateTestAccess.PERMISSION_JOIN
            else -> {
                player.sendMessage(lang.msg("private.usage"))
                return
            }
        }
        val quickTiming = when (args.getOrNull(5)?.lowercase()) {
            null, "quick" -> true
            "production" -> false
            else -> {
                player.sendMessage(lang.msg("private.error.invalid_timing"))
                return
            }
        }
        val arena = arenaId?.let { arenas()[it] } ?: run {
            player.sendMessage(lang.msg("private.error.arena_not_found", "koths" to arenas().keys.joinToString(", ")))
            return
        }
        if (!KothService.supportsPrivateTesting(arena)) {
            player.sendMessage(lang.msg("private.error.conquest_unsupported"))
            return
        }
        if (!kothService.startPrivateTest(arena, player.uniqueId, cfgLoader(), teamMode, access, quickTiming)) {
            player.sendMessage(lang.msg(if (kothService.activeEvent != null) "private.error.already_active" else "private.error.locked"))
        }
    }

    private fun privateJoin(player: Player) {
        val event = kothService.privateEvent(player.uniqueId) ?: run {
            player.sendMessage(lang.msg("private.error.no_active"))
            return
        }
        when (event.joinPrivate(player.uniqueId)) {
            PrivateJoinResult.JOINED -> player.sendMessage(lang.msg("private.success.joined"))
            PrivateJoinResult.NOT_PRIVATE -> player.sendMessage(lang.msg("private.error.not_private"))
            PrivateJoinResult.EXPIRED -> player.sendMessage(lang.msg("private.error.expired"))
            PrivateJoinResult.OWNER -> player.sendMessage(lang.msg("private.error.is_owner"))
            PrivateJoinResult.OWNER_ONLY -> player.sendMessage(lang.msg("private.error.owner_only"))
            PrivateJoinResult.ALREADY_JOINED -> player.sendMessage(lang.msg("private.error.already_joined"))
        }
    }

    private fun privateLeave(player: Player) {
        val event = kothService.activeEvent
        if (event == null || !event.isPrivateTest || !event.isParticipant(player.uniqueId)) {
            player.sendMessage(lang.msg("private.error.not_in_private"))
            return
        }
        if (event.isOwner(player.uniqueId)) {
            player.sendMessage(lang.msg("private.error.owner_cancel"))
            return
        }
        event.leave(player.uniqueId)
        player.sendMessage(lang.msg("private.success.left"))
    }

    private fun privateCancel(player: Player) {
        val event = kothService.activeEvent
        if (event == null || !event.isPrivateTest) {
            player.sendMessage(lang.msg("private.error.no_private_active"))
            return
        }
        if (!event.isOwner(player.uniqueId)) {
            player.sendMessage(lang.msg("private.error.not_owner"))
            return
        }
        kothService.forceEnd(CancellationReason.PRIVATE_OWNER, announce = false, arenaId = event.arena.id)
        player.sendMessage(lang.msg("private.success.cancelled"))
    }

    fun handleGuiClick(player: Player, slot: Int, holder: KothGuiHolder) {
        if (slot == holder.modeSlot) {
            holder.teamMode = if (holder.teamMode == TeamMode.SOLO) TeamMode.GUILD else TeamMode.SOLO
            holder.backingInventory.setItem(holder.modeSlot, teamModeItem(holder.teamMode))
            return
        }
        val arenaId = holder.arenaIds.getOrNull(slot) ?: return
        if (arenas()[arenaId] == null || kothService.eventForArena(arenaId) != null || !canStartFromMenu(player)) return
        // Inventory replacement happens next tick, outside the click transaction.
        plugin.server.scheduler.runTask(plugin, Runnable { if (player.isOnline) confirmStart(player, arenaId, holder.teamMode) })
    }

    fun handleConfirmClick(player: Player, slot: Int, holder: KothStartConfirmHolder) {
        when (slot) {
            KothStartConfirmHolder.START_SLOT -> plugin.server.scheduler.runTask(plugin, Runnable {
                val arena = arenas()[holder.arenaId]
                player.closeInventory()
                if (arena == null || !player.isOnline) return@Runnable
                val result = startService.start(StartRequest(actor = startActor(player), arena = arena, source = StartSource.GUI,
                    tier = StartTier.BASIC, teamMode = holder.teamMode))
                sendStartResult(player, holder.arenaId, result, true)
            })
            KothStartConfirmHolder.BACK_SLOT -> plugin.server.scheduler.runTask(plugin, Runnable { if (player.isOnline) gui(player, holder.teamMode) })
        }
    }

    private fun formatTime(seconds: Long): String {
        val safe = seconds.coerceAtLeast(0)
        val minutes = safe / 60
        val remainder = safe % 60
        return if (minutes > 0) "${minutes}m ${remainder}s" else "${remainder}s"
    }

    private fun formatStatUser(key: String): String {
        val parts = key.split(":", limit = 2)
        if (parts.size != 2) return key
        return when (parts[0]) {
            "solo" -> runCatching { UUID.fromString(parts[1]) }.getOrNull()?.let { Bukkit.getOfflinePlayer(it).name } ?: parts[1].take(8)
            "guild" -> runCatching { UUID.fromString(parts[1]) }.getOrNull()?.let { guilds.guildName(it) } ?: parts[1].take(8)
            else -> parts[1]
        }
    }
}
