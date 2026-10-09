package net.badgersmc.ek.infrastructure.bukkit

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
) : CommandExecutor, TabCompleter {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (args.isEmpty()) {
            sendHelp(sender)
            return true
        }
        when (args[0].lowercase()) {
            "history", "reports", "readiness", "reconcile", "holds", "reviewmatch", "claims", "challenges", "eligibility", "results" -> progression?.command(sender,args.mapIndexed { i,s -> if(i==0) s.lowercase() else s }.toTypedArray())
            "manage", "schedules", "rewards", "displays" -> if (sender is Player) {
                if (args.getOrNull(1) == "cancel") settings?.cancel(sender)
                else settings?.open(sender, args.getOrNull(1), when(args[0].lowercase()) {
                    "schedules" -> StaffSettingsPage.SCHEDULE; "rewards" -> StaffSettingsPage.REWARDS
                    "displays" -> StaffSettingsPage.DISPLAYS; else -> StaffSettingsPage.HOME
                })
            } else sender.sendMessage(lang.msg("command.error.not_a_player"))
            "info" -> arenaInfo(sender, args.getOrNull(1))
            "tp" -> arenaTeleport(sender, args.getOrNull(1))
            "gui" -> gui(sender)
            "schedule" -> schedule(sender)
            "top" -> top(sender, args.getOrNull(2)?.toIntOrNull() ?: args.getOrNull(1)?.toIntOrNull() ?: 1, args.getOrNull(1)?.takeIf { it.toIntOrNull() == null } ?: "lifetime")
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
                if (args.getOrNull(1) == "cancel") setup?.cancel(sender) else setup?.open(sender, args.getOrNull(1))
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
        if (args.size == 1) {
            val options = mutableListOf("gui", "schedule", "top", "stats", "private", "notifications", "claims", "challenges", "eligibility", "results")
            if (sender.hasPermission("enthusiakoth.start.basic") || sender.hasPermission("enthusiakoth.start.advanced") || sender.hasPermission("enthusiakoth.admin")) {
                options += "start"
            }
            if (canStartPrivate(sender) || canJoinPrivate(sender)) options += "test"
            if (sender.hasPermission("enthusiakoth.admin")) {
                options += listOf("history", "reports", "readiness", "reconcile", "holds", "reviewmatch")
                options += listOf("stop", "cancel", "giveflare", "reload", "status", "lock", "arena", "setup", "editor", "wand", "manage", "schedules", "rewards", "displays", "info", "tp")
            }
            return options.distinct().filter { it.startsWith(args[0], ignoreCase = true) }.toMutableList()
        }
        if (args.size == 2) {
            when (args[0].lowercase()) {
                "start" -> return arenas().keys.filter { it.startsWith(args[1], true) }.toMutableList()
                "private", "test" -> return listOf("start", "join", "leave", "cancel").filter { it.startsWith(args[1], true) }.toMutableList()
                "lock" -> return LockState.entries.map { it.name.lowercase() }.filter { it.startsWith(args[1], true) }.toMutableList()
                "arena" -> if (sender.hasPermission("enthusiakoth.admin")) {
                    return listOf("create", "region", "center", "enable", "disable").filter { it.startsWith(args[1], true) }.toMutableList()
                }
                "top" -> return listOf("lifetime", "daily", "weekly", "season").filter { it.startsWith(args[1], true) }.toMutableList()
                "manage", "schedules", "rewards", "displays", "info", "tp", "stop", "cancel" -> if (sender.hasPermission("enthusiakoth.admin")) return (cfgLoader().arenas.keys + "cancel").filter { it.startsWith(args[1], true) }.toMutableList()
                "setup", "editor" -> if (sender.hasPermission("enthusiakoth.admin")) return (cfgLoader().arenas.keys + "cancel").filter { it.startsWith(args[1], true) }.toMutableList()
            }
        }
        if (args.size == 3 && args[0].equals("arena", true) && sender.hasPermission("enthusiakoth.admin")) {
            return cfgLoader().arenas.keys.filter { it.startsWith(args[2], true) }.toMutableList()
        }
        if (args.size == 4 && args[0].equals("arena", true) && args[1].equals("region", true) && sender is Player) {
            return arenaRegionSuggestions(sender.world.name).filter { it.startsWith(args[3], true) }.toMutableList()
        }
        if (args.size == 4 && args[0].equals("arena", true) && args[1].equals("create", true) && sender.hasPermission("enthusiakoth.admin")) {
            return listOf("capture", "moving", "conquest", "score").filter { it.startsWith(args[3], true) }.toMutableList()
        }
        if (args.size == 3 && args[0].equals("start", true)) {
            return listOf("solo", "guild", "basic", "advanced").filter { it.startsWith(args[2], true) }.toMutableList()
        }
        if (args.size == 4 && args[0].equals("start", true)) {
            val first = args[2].lowercase()
            val options = if (first in setOf("basic", "advanced")) listOf("solo", "guild") else listOf("basic", "advanced")
            return options.filter { it.startsWith(args[3], true) }.toMutableList()
        }
        if (args.size >= 3 && (args[0].equals("private", true) || args[0].equals("test", true)) && args[1].equals("start", true)) {
            val options = when (args.size) {
                3 -> arenas().keys.toList()
                4 -> listOf("solo", "guild")
                5 -> listOf("self", "staff")
                6 -> listOf("quick", "production")
                else -> emptyList()
            }
            return options.filter { it.startsWith(args.last(), true) }.toMutableList()
        }
        return mutableListOf()
    }

    private fun sendHelp(sender: CommandSender) {
        sender.sendMessage(lang.msg("command.help.header"))
        listOf("gui", "schedule", "top", "stats").forEach { sender.sendMessage(lang.msg("command.help.$it")) }
        sender.sendMessage(lang.msg("command.help.notifications"))
        if (sender.hasPermission("enthusiakoth.start.basic") || sender.hasPermission("enthusiakoth.start.advanced") || sender.hasPermission("enthusiakoth.admin")) {
            sender.sendMessage(lang.msg("command.help.start"))
        }
        if (canStartPrivate(sender) || canJoinPrivate(sender)) sender.sendMessage(lang.msg("command.help.private_usage"))
        if (sender.hasPermission("enthusiakoth.admin")) {
            listOf("stop", "cancel", "giveflare", "reload", "status", "lock").forEach {
                sender.sendMessage(lang.msg("command.help.$it"))
            }
            sender.sendMessage(Component.text("/ekoth arena region <arena> <worldguard-region> | center <arena> | enable <arena> | disable <arena>"))
            sender.sendMessage(lang.msg("setup.help"))
            sender.sendMessage(lang.msg("staff.help"))
        }
    }

    private fun gui(sender: CommandSender) {
        if (sender !is Player) {
            sender.sendMessage(lang.msg("command.error.not_a_player"))
            return
        }
        val allArenaIds = arenas().keys.toList()
        val size = ((allArenaIds.size + 1 + 8) / 9).coerceIn(1, 6) * 9
        val modeSlot = size - 1
        val arenaIds = allArenaIds.take(modeSlot)
        val holder = KothGuiHolder(arenaIds, modeSlot)
        val inventory = Bukkit.createInventory(holder, size, lang.msg("command.gui.title"))
        holder.backingInventory = inventory
        arenaIds.forEachIndexed { slot, id ->
            val activeEvent = kothService.eventForArena(id)
            val active = activeEvent != null
            val capper = activeEvent?.let(kothService::capperName) ?: "None"
            val time = activeEvent
                ?.let { formatTime(it.endsAt.epochSecond - System.currentTimeMillis() / 1000) }
                ?: "Scheduled"
            val item = ItemStack(if (active) Material.GLOWSTONE_DUST else Material.REDSTONE_TORCH)
            item.itemMeta = item.itemMeta?.apply {
                displayName(lang.msg(if (active) "command.gui.item_name_active" else "command.gui.item_name_inactive", "id" to id.uppercase()))
                lore(
                    listOf(
                        lang.msg(if (active) "command.gui.lore_active_yes" else "command.gui.lore_active_no", "status" to active.toString()),
                        lang.msg(if (active) "command.gui.lore_capturing_yes" else "command.gui.lore_capturing_no", "capper" to capper),
                        lang.msg(if (active) "command.gui.lore_time_left_yes" else "command.gui.lore_time_left_no", "time" to time),
                    ),
                )
            }
            inventory.setItem(slot, item)
        }
        inventory.setItem(modeSlot, teamModeItem(holder.teamMode))
        sender.openInventory(inventory)
    }

    private fun teamModeItem(mode: TeamMode): ItemStack = ItemStack(Material.PLAYER_HEAD).apply {
        itemMeta = itemMeta?.apply {
            displayName(lang.msg("command.gui.mode_name", "mode" to mode.name.lowercase()))
            lore(listOf(lang.msg("command.gui.mode_lore")))
        }
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
            sender.sendMessage(lang.msg("command.schedule.entry", "name" to arenaId))
            occurrences.forEach { occurrence ->
                val time = occurrence.instant.atZone(cfg.schedule.zone).format(DateTimeFormatter.ofPattern("HH:mm"))
                sender.sendMessage(lang.msg("command.schedule.entry_time", "time" to time))
            }
        }
    }

    private fun top(sender: CommandSender, page: Int, period: String = "lifetime") {
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
        sender.sendMessage(lang.msg("staff.info", "value" to "$id | ${arena.family} | enabled=${arena.enabled} | ${arena.world} ${arena.center.x}, ${arena.center.y}, ${arena.center.z}"))
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
                lang.msg(if (fromGui) "command.success.started_from_gui" else "command.success.started", "arena" to arenaId),
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
        sender.sendMessage(lang.msg("command.error.flare_given", "player" to target.name, "amount" to flare.amount.toString(), "koth" to arenaId))
        target.sendMessage(lang.msg("command.error.flare_received", "amount" to flare.amount.toString(), "koth" to arenaId))
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
        sender.sendMessage(lang.msg("staff.active-list", "value" to kothService.allEvents().joinToString(", ") { it.arena.id }))
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
        val arena = arenas()[arenaId] ?: return
        val result = startService.start(
            StartRequest(
                actor = startActor(player),
                arena = arena,
                source = StartSource.GUI,
                tier = StartTier.BASIC,
                teamMode = holder.teamMode,
            ),
        )
        if (result is StartResult.Started) player.closeInventory()
        sendStartResult(player, arenaId, result, true)
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
