package net.badgersmc.ek.infrastructure.bukkit

import net.badgersmc.ek.infrastructure.i18n.arenaComponent
import net.badgersmc.ek.infrastructure.i18n.arenaMsg
import io.papermc.paper.event.player.AsyncChatEvent
import net.badgersmc.ek.application.*
import net.badgersmc.ek.config.EnthusiaKothConfig
import net.badgersmc.ek.domain.KothArena
import net.badgersmc.nexus.i18n.LangService
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.java.JavaPlugin
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

enum class StaffSettingsPage { HOME, SCHEDULE, REWARDS, DISPLAYS, PREVIEW }
class StaffSettingsHolder(val owner: UUID, val draft: StaffSettingsDraft, val page: StaffSettingsPage) : InventoryHolder {
    lateinit var backing: Inventory
    override fun getInventory() = backing
}

/** All mutations stay in session drafts until explicit, revision-checked Save. */
class StaffSettingsController(
    private val plugin: JavaPlugin, private val service: StaffSettingsService,
    private val cfg: () -> EnthusiaKothConfig, private val arenas: () -> Map<String, KothArena>,
    private val schedule: ScheduleService, private val applySaved: () -> Unit, private val lang: LangService,
) : Listener {
    private val drafts = mutableMapOf<UUID, StaffSettingsDraft>()
    private data class Prompt(val token: UUID, val draft: StaffSettingsDraft, val page: StaffSettingsPage, val key: String)
    private val prompts = ConcurrentHashMap<UUID, Prompt>()
    private fun text(key: String, vararg values: Pair<String, Any?>) = lang.arenaMsg("staff.$key", *values)
    private fun allowed(player: Player): Boolean {
        if (player.hasPermission("enthusiakoth.admin")) return true
        player.sendMessage(lang.msg("command.error.no_permission")); return false
    }
    private fun guard(player: Player, action: () -> Unit) {
        try { action() } catch (error: Exception) {
            val key = when (error.message) { "busy", "stale", "arena", "money", "times", "commands" -> error.message!!; else -> "invalid" }
            player.sendMessage(text("error.$key"))
            if (error is java.io.IOException || error is org.bukkit.configuration.InvalidConfigurationException) plugin.logger.warning("Staff settings save/read failed: ${error.message}")
        }
    }
    fun open(player: Player, id: String? = null, page: StaffSettingsPage = StaffSettingsPage.HOME) {
        if (!allowed(player)) return
        guard(player) {
            val current = drafts[player.uniqueId]
            if (current != null && current.arenaId != id) { player.sendMessage(text("finish")); return@guard }
            val draft = current ?: service.begin(id).also { drafts[player.uniqueId] = it }
            render(player, draft, page)
        }
    }
    fun cancel(player: Player) {
        if (!allowed(player)) return
        drafts.remove(player.uniqueId); prompts.remove(player.uniqueId); player.closeInventory(); player.sendMessage(text("discarded"))
    }
    fun releaseForSetup(player: Player): Boolean {
        val draft = drafts[player.uniqueId] ?: return true
        if (draft.changes.isNotEmpty()) { player.sendMessage(text("save-first")); return false }
        drafts.remove(player.uniqueId); prompts.remove(player.uniqueId)
        return true
    }
    fun shutdown() { drafts.clear(); prompts.clear() }
    @EventHandler fun quit(event: PlayerQuitEvent) { drafts.remove(event.player.uniqueId); prompts.remove(event.player.uniqueId) }
    private fun icon(material: Material, key: String, value: Any? = "", lore: List<net.kyori.adventure.text.Component> = listOf(text("click"))) = ItemStack(material).apply {
        editMeta { it.displayName(text(key, "value" to (value?.toString() ?: "-"))); it.lore(lore) }
    }
    private fun root(d: StaffSettingsDraft) = "arenas.${d.arenaId}"
    private fun render(player: Player, d: StaffSettingsDraft, page: StaffSettingsPage) {
        val holder = StaffSettingsHolder(player.uniqueId, d, page)
        val inv = Bukkit.createInventory(holder, 54, text("title", "value" to (d.arenaId?.let { (arenas()[it]?.name ?: it).arenaComponent().append(net.kyori.adventure.text.Component.text(" ($it)")) } ?: "Global"))); holder.backing = inv
        fun item(slot: Int, material: Material, key: String, value: Any? = "", lore: List<net.kyori.adventure.text.Component> = listOf(text("click"))) { inv.setItem(slot, icon(material, key, value, lore)) }
        when (page) {
            StaffSettingsPage.HOME -> {
                item(10, Material.CLOCK, "schedule"); if (d.arenaId != null) item(12, Material.CHEST, "rewards")
                item(14, Material.BEACON, "displays"); item(16, Material.PAPER, "preview")
            }
            StaffSettingsPage.SCHEDULE -> {
                item(10, Material.LEVER, "schedule-enabled", d.values["schedule.enabled"] ?: false)
                item(11, Material.COMPASS, "timezone", d.values["general.timezone"] ?: "America/New_York")
                item(12, Material.CLOCK, "rotation-times", d.values["schedule.times"] ?: emptyList<String>())
                if (d.arenaId != null) item(14, Material.CLOCK, "arena-times", d.values["${root(d)}.schedule"] ?: emptyList<String>())
                item(16, Material.MAP, "upcoming", lore = upcoming(d).ifEmpty { listOf(text("no-upcoming")) })
            }
            StaffSettingsPage.REWARDS -> {
                if (d.arenaId == null) return render(player, d, StaffSettingsPage.HOME)
                item(10, Material.COMMAND_BLOCK, "fixed", d.values["${root(d)}.rewards"] ?: emptyList<String>())
                item(11, Material.DROPPER, "chance", chanceRows(d).map { "${it["chance"]}% ${it["command"]}" })
                item(13, Material.GOLD_NUGGET, "solo-money", moneyValue(d, "solo"))
                item(14, Material.GOLD_BLOCK, "guild-money", moneyValue(d, "guild"))
                item(16, Material.PAPER, "reward-preview", lore = rewardPreview(d))
            }
            StaffSettingsPage.DISPLAYS -> {
                listOf("bossbar", "actionbar", "zone-border", "hologram", "scoreboard").forEachIndexed { index, key ->
                    item(10 + index, Material.LEVER, "display-$key", d.values["display.$key"] ?: (index < 3))
                }
                item(19, Material.RED_DYE, "color", d.values["display.bossbar-color"] ?: "RED")
                item(20, Material.REPEATER, "overlay", d.values["display.bossbar-overlay"] ?: "PROGRESS")
                item(22, Material.TARGET, "capacity", d.values["events.max-concurrent"] ?: 1)
                item(24, Material.MAP, "season", d.values["leaderboards.season-start"] ?: "-")
                item(25, Material.PAPER, "bar-title", d.values["display.bossbar-title"] ?: "-")
            }
            StaffSettingsPage.PREVIEW -> {
                item(10, Material.CLOCK, "upcoming", lore = upcoming(d).ifEmpty { listOf(text("no-upcoming")) })
                if (d.arenaId != null) item(12, Material.CHEST, "reward-preview", lore = rewardPreview(d))
                item(14, Material.MAP, "audience", cfg().captureNotificationRegions.joinToString(", "), listOf(text("audience-hint")))
                item(16, Material.PAPER, "draft", d.changes.size, listOf(text("draft-hint")))
            }
        }
        item(45, Material.BARRIER, "cancel"); item(47, Material.WOODEN_AXE, "area-setup"); item(48, Material.ARROW, "back"); item(49, Material.EMERALD, "save")
        player.openInventory(inv)
    }
    private fun moneyValue(d: StaffSettingsDraft, mode: String): Any {
        val family = d.values["${root(d)}.reward-family"]?.toString()?.takeIf { it.isNotBlank() } ?: d.values["${root(d)}.family"]
        return d.values["rewards.$family.$mode-vault-money"] ?: 0.0
    }
    private fun isolateMoney(d: StaffSettingsDraft) {
        if (d.changes.containsKey("${root(d)}.reward-family")) return
        val solo = moneyValue(d, "solo"); val guild = moneyValue(d, "guild")
        val family = "editor_" + UUID.randomUUID().toString().replace('-', '_')
        d.set("${root(d)}.reward-family", family)
        d.set("rewards.$family.solo-vault-money", (solo as Number).toDouble())
        d.set("rewards.$family.guild-vault-money", (guild as Number).toDouble())
    }
    @Suppress("UNCHECKED_CAST") private fun chanceRows(d: StaffSettingsDraft): List<Map<String, Any>> {
        val path = "${root(d)}.chanced-rewards"
        (d.values[path] as? List<*>)?.let { return it.mapNotNull { row -> (row as? Map<String, Any>) } }
        return d.values.filterKeys { it.startsWith("$path.") }.mapNotNull { (key, value) ->
            val chance = key.removePrefix("$path.").toDoubleOrNull() ?: return@mapNotNull null
            if (value !is String) return@mapNotNull null
            mapOf("command" to value, "chance" to chance)
        }
    }
    private fun rewardPreview(d: StaffSettingsDraft): List<net.kyori.adventure.text.Component> = listOf(
        text("payout-solo", "value" to moneyValue(d, "solo").toString()), text("payout-guild", "value" to moneyValue(d, "guild").toString()),
        text("contributors", "value" to cfg().fairness.contributorMinimumPercent.toString()), text("recipients"), text("preview-only"),
    ) + ((d.values["${root(d)}.rewards"] as? List<*>) ?: emptyList<Any>()).map { text("command", "value" to it.toString()) } +
        chanceRows(d).map { text("command", "value" to "${it["chance"]}%: ${it["command"]}") }
    private fun upcoming(d: StaffSettingsDraft): List<net.kyori.adventure.text.Component> {
        val zone = ZoneId.of(d.values["general.timezone"]?.toString() ?: "America/New_York")
        @Suppress("UNCHECKED_CAST") val times = d.values["schedule.times"] as? List<String> ?: emptyList()
        val config = cfg().let { it.copy(schedule = it.schedule.copy(enabled = d.values["schedule.enabled"] as? Boolean ?: false, zone = zone, times = times)) }
        val candidates = arenas().mapValues { (id, arena) ->
            @Suppress("UNCHECKED_CAST") val arenaTimes = d.values["arenas.$id.schedule"] as? List<String> ?: emptyList()
            arena.copy(schedule = arenaTimes)
        }
        return schedule.previewUpcoming(config, candidates).map {
            text("occurrence", "value" to net.kyori.adventure.text.Component.text("${it.instant.atZone(zone).format(DateTimeFormatter.ofPattern("EEE MM-dd HH:mm xxx"))} | ").append((candidates[it.arenaId]?.name ?: it.arenaId).arenaComponent()).append(net.kyori.adventure.text.Component.text(" (${it.arenaId}) | ${it.teamMode}")))
        }
    }
    @EventHandler fun click(event: InventoryClickEvent) {
        val holder = event.view.topInventory.holder as? StaffSettingsHolder ?: return
        event.isCancelled = true
        val player = event.whoClicked as? Player ?: return
        if (holder.owner != player.uniqueId || !allowed(player) || event.clickedInventory != event.view.topInventory) return
        val slot = event.rawSlot; val right = event.isRightClick
        plugin.server.scheduler.runTask(plugin, Runnable {
            if (!player.isOnline || !allowed(player) || player.openInventory.topInventory.holder !== holder || drafts[player.uniqueId] !== holder.draft) return@Runnable
            guard(player) {
                val d = holder.draft
                when (slot) {
                    45 -> { cancel(player); return@guard }
                    47 -> { if(releaseForSetup(player)) player.performCommand("ekoth setup" + (d.arenaId?.let { " $it" } ?: "")); return@guard }
                    48 -> { render(player, d, StaffSettingsPage.HOME); return@guard }
                    49 -> {
                        service.save(d); drafts.remove(player.uniqueId); prompts.remove(player.uniqueId)
                        try { applySaved() } catch (error: Exception) { player.sendMessage(text("saved-reload-failed")); plugin.logger.severe("Staff settings saved but reload failed: ${error.message}"); player.closeInventory(); return@guard }
                        player.sendMessage(text("saved"))
                        if(d.arenaId != null) player.performCommand("ekoth setup ${d.arenaId}") else player.closeInventory()
                        return@guard
                    }
                }
                when (holder.page) {
                    StaffSettingsPage.HOME -> when (slot) {
                        10 -> render(player, d, StaffSettingsPage.SCHEDULE); 12 -> if (d.arenaId != null) render(player, d, StaffSettingsPage.REWARDS)
                        14 -> render(player, d, StaffSettingsPage.DISPLAYS); 16 -> render(player, d, StaffSettingsPage.PREVIEW)
                    }
                    StaffSettingsPage.SCHEDULE -> {
                        when (slot) {
                            10 -> d.set("schedule.enabled", !(d.values["schedule.enabled"] as? Boolean ?: false))
                            11 -> { prompt(player, holder, "timezone"); return@guard }
                            12 -> { prompt(player, holder, "rotation-times"); return@guard }
                            14 -> if (d.arenaId != null) { prompt(player, holder, "arena-times"); return@guard }
                        }; render(player, d, holder.page)
                    }
                    StaffSettingsPage.REWARDS -> {
                        val key = mapOf(10 to "fixed", 11 to "chance", 13 to "solo-money", 14 to "guild-money")[slot]
                        if (key != null) prompt(player, holder, key)
                    }
                    StaffSettingsPage.DISPLAYS -> {
                        when (slot) {
                            in 10..14 -> { val key = "display." + listOf("bossbar", "actionbar", "zone-border", "hologram", "scoreboard")[slot - 10]; d.set(key, !(d.values[key] as? Boolean ?: (slot < 13))) }
                            19, 20 -> {
                                val key = if (slot == 19) "display.bossbar-color" else "display.bossbar-overlay"
                                val options = if (slot == 19) listOf("RED", "BLUE", "GREEN", "YELLOW", "PURPLE", "PINK", "WHITE") else listOf("PROGRESS", "NOTCHED_6", "NOTCHED_10", "NOTCHED_12", "NOTCHED_20")
                                d.set(key, options[(options.indexOf(d.values[key] ?: options.first()) + 1) % options.size])
                            }
                            22 -> d.set("events.max-concurrent", (((d.values["events.max-concurrent"] as? Number)?.toInt() ?: 1) + if (right) -1 else 1).coerceIn(1, 16))
                            24 -> { prompt(player, holder, "season"); return@guard }
                            25 -> { prompt(player, holder, "bar-title"); return@guard }
                        }; render(player, d, holder.page)
                    }
                    else -> Unit
                }
            }
        })
    }
    @EventHandler fun drag(event: InventoryDragEvent) { if (event.view.topInventory.holder is StaffSettingsHolder) event.isCancelled = true }
    private fun prompt(player: Player, holder: StaffSettingsHolder, key: String) {
        val request = Prompt(UUID.randomUUID(), holder.draft, holder.page, key)
        prompts[player.uniqueId] = request; player.closeInventory(); player.sendMessage(text("prompt-$key"))
        plugin.server.scheduler.runTaskLater(plugin, Runnable {
            if (prompts.remove(player.uniqueId, request) && player.isOnline) player.sendMessage(text("expired"))
        }, 1200L)
    }
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true) fun chat(event: AsyncChatEvent) {
        val request = prompts[event.player.uniqueId] ?: return
        event.isCancelled = true
        val raw = PlainTextComponentSerializer.plainText().serialize(event.message()).trim()
        plugin.server.scheduler.runTask(plugin, Runnable {
            val player = event.player
            if (!prompts.remove(player.uniqueId, request) || !player.isOnline || !allowed(player) || drafts[player.uniqueId] !== request.draft) return@Runnable
            guard(player) {
                val d = request.draft
                if (!raw.equals("cancel", true)) when (request.key) {
                    "timezone" -> { ZoneId.of(raw); d.set("general.timezone", raw) }
                    "rotation-times" -> d.set("schedule.times", service.times(raw))
                    "arena-times" -> d.set("${root(d)}.schedule", service.times(raw))
                    "fixed" -> d.set("${root(d)}.rewards", service.commands(raw))
                    "chance" -> {
                        val rows = if (raw == "-") emptyList() else raw.split(';').map { entry ->
                            val parts = entry.trim().split('|', limit = 2); require(parts.size == 2) { "commands" }
                            val chance = service.money(parts[0].trim()); require(chance <= 100) { "money" }; service.command(parts[1].trim())
                            mapOf("chance" to chance, "command" to parts[1].trim())
                        }
                        require(rows.size <= 45 && rows.map { it["command"] }.distinct().size == rows.size) { "commands" }
                        d.set("${root(d)}.chanced-rewards", rows)
                    }
                    "solo-money", "guild-money" -> {
                        val money = service.money(raw); isolateMoney(d)
                        d.set("rewards.${d.values["${root(d)}.reward-family"]}.${request.key.substringBefore('-')}-vault-money", money)
                    }
                    "bar-title" -> { val value = if (raw == "-") "" else raw; service.validate("display.bossbar-title", value); d.set("display.bossbar-title", value) }
                    "season" -> { if (raw != "-") java.time.LocalDate.parse(raw); d.set("leaderboards.season-start", if (raw == "-") "" else raw) }
                }
                render(player, d, request.page)
            }
        })
    }
}
