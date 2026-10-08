package net.badgersmc.ek.infrastructure.bukkit

import io.github.badgersmc.advancements.pilot.ProjectionService
import net.badgersmc.ek.infrastructure.persistence.SqlProgressionStore
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.java.JavaPlugin

/** Current pilot contract: owner-bound, display-only, permille progress; no reward replay. */
class KothAdvancementProjection(private val plugin:JavaPlugin,private val settings:ProgressionSettings,private val store:SqlProgressionStore) {
    private var provider:ProjectionService?=null
    private var signature:List<String> = emptyList()
    fun tick() {
        val current=runCatching { Bukkit.getServicesManager().load(ProjectionService::class.java) }.getOrNull() ?: run { provider=null; return }
        val rules=settings.policy().challenges
        val keys=rules.map { "${it.id}:${it.wins}:${it.opponents}:${it.arenas}:${it.days}:${it.seconds}:${it.exclusive}" }
        if(current !== provider || keys != signature) {
            val nodes=rules.mapIndexed { index,rule -> ProjectionService.Node(rule.id,if(index==0) null else rules.first().id,
                rule.id.replace('_',' '),listOf("${rule.wins} protected wins; ${rule.opponents} independent opposing sides",
                    "${rule.arenas} arenas; ${rule.days} UTC days; ${rule.seconds} scoring seconds",
                    if(rule.exclusive) "Relic: one recipient ever" else "Rewards owned by KOTH"),
                if(rule.id.endsWith("crown")) Material.NETHERITE_HELMET else Material.NETHERITE_SWORD,
                if(rule.exclusive) "CHALLENGE" else "GOAL",index.toFloat(),0f) }
            if(nodes.isEmpty()) { current.removeTree(plugin,"ekoth"); provider=current; signature=keys; return }
            current.registerTree(plugin,"ekoth",ItemStack(Material.NETHERITE_SWORD),nodes)
            provider=current; signature=keys
        }
        Bukkit.getOnlinePlayers().forEach { player ->
            store.progress(player.uniqueId)?.takeIf { current.ready(player) }?.let { progress ->
                current.project(plugin,"ekoth",player,progress.mapValues { (_,value) -> value*10 })
            }
        }
    }
    fun close() { runCatching { provider?.removeTree(plugin,"ekoth") }; provider=null }
}
