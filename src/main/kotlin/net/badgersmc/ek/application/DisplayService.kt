package net.badgersmc.ek.application

import net.badgersmc.ek.infrastructure.i18n.arenaComponent
import net.badgersmc.ek.infrastructure.i18n.arenaLegacyText
import net.badgersmc.ek.infrastructure.i18n.arenaMsg
import net.badgersmc.ek.config.DisplayConfig
import net.badgersmc.ek.domain.KothEvent
import net.kyori.adventure.bossbar.BossBar
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.entity.TextDisplay
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.scoreboard.DisplaySlot
import org.bukkit.scoreboard.Scoreboard
import java.util.UUID

class DisplayService(
    private val plugin: JavaPlugin,
    private val lang: net.badgersmc.nexus.i18n.LangService,
    private val notificationsEnabled: (Player) -> Boolean = { true },
) : Listener {
    private data class Bar(val bar: BossBar, var viewers: Set<UUID>, var public: Boolean)
    private data class Sidebar(val original: Scoreboard, val owned: Scoreboard)
    private val bars = mutableMapOf<String, Bar>()
    private val holograms = mutableMapOf<String, TextDisplay>()
    private val sidebars = mutableMapOf<UUID, Sidebar>()
    private val sidebarOwners = mutableMapOf<UUID, String>()

    fun showKoth(kothName: String, capper: String?, timeLeft: String, contested: Boolean,
                 progress: Float, audience: Collection<Player>, isPublic: Boolean,
                 settings: DisplayConfig = DisplayConfig(), event: KothEvent? = null, displayName: String = kothName) {
        val text = if (settings.bossbarTitle.isNotBlank()) net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().deserialize(
            settings.bossbarTitle,
            net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.component("koth_name", displayName.arenaComponent()),
            net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.unparsed("capper", capper ?: "None"),
            net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.unparsed("time", timeLeft),
            net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.unparsed("contested", if (contested) "Contested" else ""),
        ) else if (capper != null) lang.arenaMsg("bossbar.format_with_capper", "koth_name" to displayName.arenaComponent(),
            "capper" to capper, "contested" to if (contested) lang.msg("bossbar.contested") else Component.empty(), "time" to timeLeft)
        else lang.arenaMsg("bossbar.format_no_capper", "koth_name" to displayName.arenaComponent(), "time" to timeLeft)
        val desired = audience.mapTo(mutableSetOf()) { it.uniqueId }
        if (settings.bossbar) {
            val state = bars.getOrPut(kothName) { Bar(BossBar.bossBar(text, progress.coerceIn(0f, 1f), BossBar.Color.RED, BossBar.Overlay.PROGRESS), emptySet(), isPublic) }
            state.bar.name(text); state.bar.progress(progress.coerceIn(0f, 1f))
            state.bar.color(runCatching { BossBar.Color.valueOf(settings.bossbarColor) }.getOrDefault(BossBar.Color.RED))
            state.bar.overlay(runCatching { BossBar.Overlay.valueOf(settings.bossbarOverlay) }.getOrDefault(BossBar.Overlay.PROGRESS))
            Bukkit.getOnlinePlayers().forEach { if (it.uniqueId in desired) state.bar.addViewer(it) else state.bar.removeViewer(it) }
            state.viewers = desired; state.public = isPublic
        } else clearBar(kothName)
        if (settings.hologram && event != null) {
            val world = Bukkit.getWorld(event.arena.zone.worldName)
            if (world != null) {
                val display = holograms.getOrPut(kothName) {
                    world.spawn(event.arena.zone.center(world).add(0.0, 2.5, 0.0), TextDisplay::class.java) {
                        it.isPersistent = false; it.isVisibleByDefault = false
                        it.billboard = org.bukkit.entity.Display.Billboard.CENTER
                    }
                }
                display.text(text)
                Bukkit.getOnlinePlayers().forEach { if (it.uniqueId in desired) it.showEntity(plugin, display) else it.hideEntity(plugin, display) }
            }
        } else holograms.remove(kothName)?.remove()
        val sidebarAudience = if (settings.scoreboard) desired else emptySet()
        sidebarOwners.filter { it.value == kothName && it.key !in sidebarAudience }.keys.toList().forEach(::restoreSidebar)
        if (settings.scoreboard) audience.forEach { player ->
            val existing = sidebars[player.uniqueId]
            if (existing != null && player.scoreboard !== existing.owned) { sidebars.remove(player.uniqueId); sidebarOwners.remove(player.uniqueId); return@forEach }
            if (existing == null && player.scoreboard.getObjective(DisplaySlot.SIDEBAR) != null) return@forEach
            if (existing != null && sidebarOwners[player.uniqueId] != kothName) return@forEach
            val board = existing ?: Sidebar(player.scoreboard, Bukkit.getScoreboardManager()!!.newScoreboard).also {
                sidebars[player.uniqueId] = it; sidebarOwners[player.uniqueId] = kothName; player.scoreboard = it.owned
            }
            val objective = board.owned.getObjective("ekoth") ?: board.owned.registerNewObjective("ekoth", org.bukkit.scoreboard.Criteria.DUMMY, text).also { it.displaySlot = DisplaySlot.SIDEBAR }
            objective.displayName(text)
            board.owned.entries.toList().forEach(board.owned::resetScores)
            objective.getScore("${displayName.arenaLegacyText()}§r: $timeLeft").score = 2
            objective.getScore("${capper ?: "None"}${if (contested) " (contested)" else ""}").score = 1
        }
    }

    @EventHandler(ignoreCancelled = true) fun onJoin(event: PlayerJoinEvent) {
        bars.values.filter { event.player.uniqueId in it.viewers && !it.public }.forEach { it.bar.addViewer(event.player) }
    }
    private fun clearBar(key: String) { bars.remove(key)?.bar?.let { bar -> Bukkit.getOnlinePlayers().forEach(bar::removeViewer) } }
    private fun restoreSidebar(id: UUID) {
        sidebars.remove(id)?.let { state -> Bukkit.getPlayer(id)?.let { if (it.scoreboard === state.owned) it.scoreboard = state.original } }
        sidebarOwners.remove(id)
    }
    fun clear(kothName: String? = null) {
        if (kothName == null) {
            bars.keys.toList().forEach(::clearBar); holograms.values.forEach { it.remove() }; holograms.clear()
            sidebars.keys.toList().forEach(::restoreSidebar)
        } else {
            clearBar(kothName); holograms.remove(kothName)?.remove()
            sidebarOwners.filterValues { it == kothName }.keys.toList().forEach(::restoreSidebar)
        }
    }
    fun updateActive(eventId: String?) { if (eventId == null) clear() }
}
