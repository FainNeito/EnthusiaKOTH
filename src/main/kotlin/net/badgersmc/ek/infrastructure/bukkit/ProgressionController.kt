package net.badgersmc.ek.infrastructure.bukkit

import net.badgersmc.ek.application.*
import net.badgersmc.ek.config.EnthusiaKothConfig
import net.badgersmc.ek.domain.KothArena
import net.badgersmc.ek.infrastructure.persistence.SqlProgressionStore
import net.badgersmc.ek.infrastructure.protection.WorldGuardRegionService
import net.badgersmc.ek.infrastructure.lumaguilds.LumaGuildsAdapter
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemStack
import net.lumalyte.lg.api.events.*

private class ProgressionMenu(val owner:java.util.UUID, val staff:Boolean, val actions:Map<Int,()->Unit>):InventoryHolder {
    lateinit var backing:Inventory
    override fun getInventory() = backing
}

/** Menu actions retain database IDs; no client lore/slot data authorizes payouts. */
class ProgressionController(
    private val store:SqlProgressionStore, private val settings:ProgressionSettings, private val claims:ProgressionClaims,
    private val service:KothService, private val cfg:()->EnthusiaKothConfig, private val arenas:()->Map<String,KothArena>,
    private val regions:WorldGuardRegionService, private val guilds:LumaGuildsAdapter,
):Listener {
    fun command(sender:CommandSender,args:Array<out String>) {
        val staff=args[0] in setOf("history","reports","readiness","reconcile")
        if(staff && !sender.hasPermission("enthusiakoth.admin")) { sender.sendMessage("KOTH administrator permission required."); return }
        when(args[0]) {
            "reconcile" -> {
                if(args.size<4 || args[2] !in setOf("paid","retry")) {
                    sender.sendMessage("First verify the external payout. /ekoth reconcile <claim-id> paid|retry <evidence>")
                    store.reviewClaims().forEach { sender.sendMessage(it) }; return
                }
                sender.sendMessage(if(store.reconcile(args[1],sender.name,args[2]=="paid",args.drop(3).joinToString(" "))) "Recorded staff reconciliation." else "No matching uncertain claim.")
            }
            "readiness" -> readiness().forEach { sender.sendMessage(it) }
            "reports" -> {
                val id=args.getOrNull(1) ?: run { sender.sendMessage("/ekoth reports <match UUID>"); return }
                store.history(100).firstOrNull { it.id==id }?.let { sender.sendMessage("${it.arena}: ${it.detail}") }
                store.report(id).forEach { sender.sendMessage(it) }
            }
            "eligibility" -> eligibility(sender).forEach { sender.sendMessage(it) }
            else -> if(sender is Player) open(sender,args[0],args.getOrNull(1)?.toIntOrNull()?.coerceAtLeast(0) ?: 0)
                else sender.sendMessage("Open this menu in game.")
        }
    }
    fun readiness():List<String> = buildList {
        val p=settings.policy()
        add("Progression: ${if(p.enabled) "enabled" else "disabled pending TEST"}; protection=${cfg().rewardProtection.enabled}")
        add("Guild alliance API: ${if(runCatching { guilds.protectionRoster() != null }.getOrDefault(false)) "available" else "unavailable - no verified credit"}")
        add("LoreItems queue=${claims.loreAvailable()}; durable Tags=${claims.tagsAvailable()}")
        add("MaceGuard follow-current-warzone=${cfg().followWarzoneCombat}; rotation scope must be verified in TEST")
        arenas().values.forEach { a ->
            val loaded=Bukkit.getWorld(a.zone.worldName)!=null
            val region=a.worldGuardRegion?.let { runCatching { regions.exists(a.zone.worldName,it) }.getOrDefault(false) }
            val overlap=arenas().values.filter { it.id != a.id && EventConcurrency.overlaps(a,it) }.map { it.id }
            add("${a.id}: world=$loaded; WorldGuard=${region ?: "cuboid"}; schedules=${a.schedule.size}; possible overlap=$overlap")
        }
        add("Exclusive definitions: ${p.challenges.filter { it.exclusive }.joinToString { "${it.id}=${if(it.loreDefinition.isBlank()) "inactive" else "template must be inspected in TEST"}" }}")
        add("Lore templates, Signature authenticity, item texture and XP command are manual TEST gates.")
    }
    private fun eligibility(sender:CommandSender):List<String> = buildList {
        val p=settings.policy(); val protection=cfg().rewardProtection
        add("Public protected matches only; admin/private tests never earn challenges.")
        add("Winning-team scoring share >= ${p.contributionPercent}%; your scoring >= ${p.minimumScoringSeconds}s; independent opposition >= ${p.minimumOppositionSeconds}s.")
        add("Account age >= ${protection.minimumAccountAgeDays}d; playtime >= ${protection.minimumPlaytimeMinutes}m; alliances count as one side.")
        add("Guild changes invalidate the current match. Optional observed roster age=${p.minimumRosterAgeSeconds}s (unknown history fails closed when enabled).")
        add("Inactive until TEST: ${!p.enabled}. Basic legacy contributor threshold remains ${cfg().fairness.contributorMinimumPercent}%.")
        if(sender is Player) add("Observed roster history: ${store.rosterAge(sender.uniqueId,java.time.Instant.now())?.let { "$it seconds since last change" } ?: "unknown"}")
    }
    private fun open(player:Player,page:String,index:Int=0) {
        val entries=mutableListOf<Pair<String,List<String>>>()
        val actions=mutableMapOf<Int,()->Unit>()
        when(page) {
            "history" -> store.history(100).forEach { match ->
                val slot=entries.size
                entries.add("${match.arena} ${match.at}" to listOf(match.winner ?: "No winner",match.detail,"Click for report"))
                actions[slot]={ player.closeInventory(); command(player,arrayOf("reports",match.id)) }
            }
            "claims" -> store.claims(player.uniqueId).forEach { c ->
                val slot=entries.size
                entries.add("${c.kind}: ${c.status}" to listOf(c.value,"Amount: ${c.amount}",c.detail,"Click to claim or choose"))
                actions[slot]={
                    if(c.kind=="CHOICE" && c.status=="PENDING") choose(player,c)
                    else { player.sendMessage(claims.redeem(player.uniqueId,c.id)); open(player,"claims",index) }
                }
            }
            else -> {
                val progress=store.progress(player.uniqueId)
                settings.policy().challenges.forEach { rule ->
                    entries.add("${rule.id}: ${progress?.get(rule.id) ?: 0}%" to listOf(
                        "${rule.wins} wins / ${rule.opponents} independent sides / ${rule.arenas} arenas",
                        "${rule.days} separate UTC days / ${rule.seconds} scoring seconds",
                        if(rule.exclusive) "One recipient ever: ${store.exclusiveOwner(rule.id) ?: "unclaimed"}" else "Individual achievement",
                        if(progress==null) "Inactive or provider unavailable" else "Verified KOTH records only"))
                }
            }
        }
        menu(player,"KOTH $page",page=="history",entries,actions,index) { open(player,page,it) }
    }
    private fun choose(player:Player,c:ProgressionClaim) {
        val options=store.options(c.id,player.uniqueId)
        menu(player,"Choose reward package",false,options.map { it to listOf("Same fixed event pool share", "Choice is permanent") },
            options.indices.associateWith { { player.sendMessage(if(store.choose(c.id,player.uniqueId,options[it])) "Package selected." else "Choice unavailable."); open(player,"claims") } },0) {}
    }
    private fun menu(player:Player,title:String,staff:Boolean,entries:List<Pair<String,List<String>>>,allActions:Map<Int,()->Unit>,index:Int,next:(Int)->Unit) {
        val page=index.coerceAtMost((entries.size-1).coerceAtLeast(0)/28)
        val actions=mutableMapOf<Int,()->Unit>()
        entries.drop(page*28).take(28).indices.forEach { slot -> allActions[page*28+slot]?.let { actions[slot]=it } }
        if(page>0) actions[45]={ next(page-1) }
        if((page+1)*28<entries.size) actions[53]={ next(page+1) }
        val holder=ProgressionMenu(player.uniqueId,staff,actions)
        holder.backing=Bukkit.createInventory(holder,54,Component.text("$title - ${page+1}"))
        entries.drop(page*28).take(28).forEachIndexed { slot,(name,lines) -> holder.backing.setItem(slot,item(Material.PAPER,name,lines)) }
        if(page>0) holder.backing.setItem(45,item(Material.ARROW,"Previous",emptyList()))
        if((page+1)*28<entries.size) holder.backing.setItem(53,item(Material.ARROW,"Next",emptyList()))
        player.openInventory(holder.backing)
    }
    private fun item(material:Material,name:String,lines:List<String>)=ItemStack(material).apply { itemMeta=itemMeta.apply {
        displayName(Component.text(name)); lore(lines.flatMap { it.chunked(60) }.map(Component::text))
    } }
    @EventHandler fun click(event:InventoryClickEvent) {
        val holder=event.view.topInventory.holder as? ProgressionMenu ?: return
        event.isCancelled=true
        val player=event.whoClicked as? Player ?: return
        if(player.uniqueId!=holder.owner || (holder.staff && !player.hasPermission("enthusiakoth.admin"))) { player.closeInventory(); return }
        if(event.rawSlot in 0 until event.view.topInventory.size && event.isLeftClick) holder.actions[event.rawSlot]?.invoke()
    }
    @EventHandler fun drag(event:InventoryDragEvent) { if(event.view.topInventory.holder is ProgressionMenu) event.isCancelled=true }
    @EventHandler fun joined(event:GuildMemberJoinEvent) { service.membershipChanged(event.playerId); store.change(event.playerId,event.guildId,"JOIN") }
    @EventHandler fun removed(event:GuildMemberRemovedEvent) { service.membershipChanged(event.playerId); store.change(event.playerId,event.guildId,"REMOVE") }
    @EventHandler fun relation(event:GuildRelationChangeEvent) {
        service.relationChanged(); store.change(null,event.guild1,"RELATION:${event.guild2}"); store.change(null,event.guild2,"RELATION:${event.guild1}")
    }
}
