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
import net.badgersmc.ek.api.KothRewardsMenuV1
import org.bukkit.plugin.java.JavaPlugin

internal class ProgressionMenu(val owner:java.util.UUID, val staff:Boolean, val actions:Map<Int,()->Unit>):InventoryHolder {
    lateinit var backing:Inventory
    override fun getInventory() = backing
}

/** Menu actions retain database IDs; no client lore/slot data authorizes payouts. */
class ProgressionController(
    private val store:SqlProgressionStore, private val settings:ProgressionSettings, private val claims:ProgressionClaims,
    private val service:KothService, private val cfg:()->EnthusiaKothConfig, private val arenas:()->Map<String,KothArena>,
    private val regions:WorldGuardRegionService, private val guilds:LumaGuildsAdapter,
    private val plugin:JavaPlugin,
):Listener, KothRewardsMenuV1 {
    override fun open(player:java.util.UUID,page:String):Boolean {
        if (!plugin.isEnabled || !Bukkit.isPrimaryThread() || page !in setOf("home","challenges","claims","results")) return false
        val online=Bukkit.getPlayer(player) ?: return false
        if (!online.isOnline || !online.hasPermission("enthusiakoth.command")) return false
        open(online,page)
        return true
    }
    fun command(sender:CommandSender,args:Array<out String>) {
        val staff=args[0] in setOf("history","reports","readiness","reconcile","holds","reviewmatch")
        if(staff && !sender.hasPermission("enthusiakoth.admin")) { sender.sendMessage("KOTH administrator permission required."); return }
        when(args[0]) {
            "holds" -> {
                val pending=store.decisions()
                if (pending.isEmpty()) sender.sendMessage("No held matches.")
                pending.forEach { sender.sendMessage("${it.id}: ${it.flags.joinToString()}. /ekoth reports ${it.id}") }
            }
            "reviewmatch" -> {
                if (args.size<4 || args[2] !in setOf("approve","reject")) {
                    sender.sendMessage("Inspect /ekoth reports first. /ekoth reviewmatch <match UUID> approve|reject <evidence>"); return
                }
                sender.sendMessage(if (store.reviewMatch(args[1],sender.name,args[2]=="approve",args.drop(3).joinToString(" ")))
                    "Match review recorded; approved rewards use the original rules." else "Match is not awaiting review.")
            }
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
        addAll(claims.definitionIssues(p))
        add("Readiness enforcement=${p.integrity.requireReadiness}; accepted arenas=${p.integrity.acceptedArenas}")
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
            "home" -> {
                entries += "Challenges" to listOf("Difficult milestones and exclusive prizes", "Click to see requirements and progress")
                actions[0]={ open(player,"challenges") }
                entries += "Your rewards" to listOf("Claim earned rewards or choose a package", "Pending delivery and staff reviews appear here")
                actions[1]={ open(player,"claims") }
                entries += "Match results" to listOf("Your recent public matches and challenge credit")
                actions[2]={ open(player,"results") }
                entries += "How to earn" to listOf("Score for your guild in qualifying public matches.", "Allied sides do not count as independent opposition.", "Private tests never earn challenges.", "Click for your eligibility details.")
                actions[3]={ player.closeInventory(); eligibility(player).forEach { player.sendMessage(it) } }
                entries += "Arenas" to listOf("View current events or start an arena")
                actions[4]={ player.performCommand("ekoth gui") }
                entries += "Schedule" to listOf("See upcoming public events")
                actions[5]={ player.closeInventory(); player.performCommand("ekoth schedule") }
                entries += "Leaderboards" to listOf("View recorded KOTH standings")
                actions[6]={ player.closeInventory(); player.performCommand("ekoth top") }
                if(player.hasPermission("enthusiakoth.admin")) {
                    entries += "Arena setup" to listOf("Area > Rules > Review", "Schedules and payouts: Review > Advanced settings")
                    actions[7]={ player.performCommand("ekoth setup") }
                    entries += "Global settings" to listOf("Schedules and displays shared by arenas")
                    actions[8]={ player.performCommand("ekoth manage") }
                }
            }
            "results" -> {
                store.results(player.uniqueId,100).forEach { result ->
                val slot=entries.size
                entries.add("${result.arena}: ${result.status}" to listOf(result.at.toString(),"Your scoring: ${result.scoringSeconds}s",
                    "Challenge credit: ${result.qualifying}",result.detail,"Click to view your claims"))
                actions[slot]={ open(player,"claims") }
                }
                val progress=store.progress(player.uniqueId)
                val slot=entries.size
                entries.add("Your challenge progress" to (progress?.map { "${it.key}: ${it.value}%" } ?: listOf("Progression inactive or unavailable")))
                actions[slot]={ open(player,"challenges") }
            }
            "history" -> store.history(100).forEach { match ->
                val slot=entries.size
                entries.add("${match.arena} ${match.at}" to listOf(match.winner ?: "No winner",match.detail,"Click for report"))
                actions[slot]={ player.closeInventory(); command(player,arrayOf("reports",match.id)) }
            }
            "claims" -> store.claims(player.uniqueId).forEach { c ->
                val slot=entries.size
                entries.add("${claimKind(c.kind)}: ${claimStatus(c.status)}" to listOf(
                    if(c.kind=="MONEY") "Currency: ${java.math.BigDecimal.valueOf(c.amount,2).toPlainString()}" else label(c.value),c.detail,
                    if(c.status=="PENDING") "Click to claim or choose" else "Click to check delivery"))
                actions[slot]={
                    if(c.kind=="CHOICE" && c.status=="PENDING") choose(player,c)
                    else { player.sendMessage(claims.redeem(player.uniqueId,c.id)); open(player,"claims",index) }
                }
            }
            else -> {
                val progress=store.progress(player.uniqueId)
                settings.policy().challenges.forEach { rule ->
                    entries.add("${label(rule.id)}: ${progress?.get(rule.id)?.let { "$it%" } ?: "Inactive or unavailable"}" to listOf(
                        "${rule.wins} wins / ${rule.opponents} independent sides / ${rule.arenas} arenas",
                        "${rule.days} separate UTC days / ${rule.seconds} scoring seconds",
                        if(rule.exclusive) "One recipient ever: ${if(store.exclusiveOwner(rule.id)==null) "unclaimed" else "already awarded"}" else "Individual achievement",
                        if(progress==null) "Inactive or provider unavailable" else "Verified KOTH records only"))
                }
            }
        }
        if(entries.isEmpty()) entries += when(page) {
            "claims" -> "No earned rewards yet" to listOf("Complete qualifying matches and challenges.", "Use Challenges to see the requirements.")
            "results" -> "No public match results yet" to listOf("Your recent public matches will appear here.")
            else -> "Nothing to show yet" to listOf("Ask staff if this feature is awaiting TEST.")
        }
        menu(player,"KOTH ${if(page=="claims") "rewards" else page}",page=="history",entries,actions,index,
            { open(player,page,it) }, { if(page=="home") player.closeInventory() else open(player,"home") },
            if(page=="home") "Close" else "Back to KOTH")
    }
    private fun choose(player:Player,c:ProgressionClaim) {
        val options=store.options(c.id,player.uniqueId)
        menu(player,"Choose reward package",false,options.map { it to listOf("Same fixed event pool share", "Choice is permanent") },
            options.indices.associateWith { { player.sendMessage(if(store.choose(c.id,player.uniqueId,options[it])) "Package selected." else "Choice unavailable."); open(player,"claims") } },0,{}, { open(player,"claims") },"Back to your rewards")
    }
    private fun menu(player:Player,title:String,staff:Boolean,entries:List<Pair<String,List<String>>>,allActions:Map<Int,()->Unit>,index:Int,next:(Int)->Unit,back:()->Unit,backName:String) {
        val page=ProgressionLayout.page(index,entries.size)
        val actions=mutableMapOf<Int,()->Unit>()
        val visible=entries.drop(page*21).take(21)
        actions.putAll(ProgressionLayout.actions(allActions,page,entries.size))
        if(page>0) actions[47]={ next(page-1) }
        if(page+1<ProgressionLayout.pages(entries.size)) actions[51]={ next(page+1) }
        actions[45]=back
        actions[53]={ player.closeInventory() }
        if(Bukkit.getPluginManager().getPlugin("EnthusiaTags")?.isEnabled==true) {
            actions[46]={ player.performCommand("tags") }
            actions[52]={ player.performCommand("rewards") }
        }
        val holder=ProgressionMenu(player.uniqueId,staff,actions)
        holder.backing=Bukkit.createInventory(holder,54,Component.text(title))
        for(slot in 0 until 54) if(slot<18 || slot>=45 || slot%9==0 || slot%9==8)
            holder.backing.setItem(slot,item(if(slot in 9..17) Material.GRAY_STAINED_GLASS_PANE else Material.BLACK_STAINED_GLASS_PANE," ",emptyList()))
        listOf(3,5).forEach { holder.backing.setItem(it,item(Material.ORANGE_STAINED_GLASS_PANE," ",emptyList())) }
        holder.backing.setItem(4,item(Material.BOOK,title,listOf("Provider-owned KOTH progress and rewards")))
        holder.backing.setItem(10,item(Material.KNOWLEDGE_BOOK,"Advancements",listOf("Open Minecraft Advancements (default L).", "KOTH milestones appear in the KOTH tab when enabled.","Verified KOTH records determine progress.")))
        visible.forEachIndexed { offset,(name,lines) -> holder.backing.setItem(ProgressionLayout.slots[offset],item(Material.PAPER,name,lines)) }
        if(page>0) holder.backing.setItem(47,item(Material.ARROW,"Previous",emptyList()))
        if(page+1<ProgressionLayout.pages(entries.size)) holder.backing.setItem(51,item(Material.ARROW,"Next",emptyList()))
        holder.backing.setItem(45,item(Material.ARROW,backName,emptyList()))
        holder.backing.setItem(49,item(Material.PAPER,"Page "+(page+1)+" / "+ProgressionLayout.pages(entries.size),emptyList()))
        holder.backing.setItem(53,item(Material.BARRIER,"Close",emptyList()))
        if(46 in actions) holder.backing.setItem(46,item(Material.NAME_TAG,"Tags",emptyList()))
        if(52 in actions) holder.backing.setItem(52,item(Material.CHEST,"Rewards",emptyList()))
        player.openInventory(holder.backing)
    }
    private fun label(value:String)=value.lowercase().split('_','-').joinToString(" ") { it.replaceFirstChar(Char::uppercaseChar) }
    private fun claimKind(value:String)=when(value) { "MONEY" -> "Currency"; "LORE" -> "Item"; "CHOICE" -> "Reward package"; else -> label(value) }
    private fun claimStatus(value:String)=when(value) {
        "PENDING" -> "Ready to claim"; "SENDING" -> "Delivery requested"; "QUEUED" -> "Queued for delivery"
        "REVIEW" -> "Needs staff review"; "PAID" -> "Paid"; "GRANTED" -> "Granted"; "DISPATCHED" -> "Command accepted"; else -> label(value)
    }
    private fun item(material:Material,name:String,lines:List<String>)=ItemStack(material).apply { itemMeta=itemMeta.apply {
        displayName(Component.text(name)); lore(lines.flatMap { it.chunked(60) }.map(Component::text))
    } }
    @EventHandler fun click(event:InventoryClickEvent) {
        val holder=event.view.topInventory.holder as? ProgressionMenu ?: return
        event.isCancelled=true
        val player=event.whoClicked as? Player ?: return
        if(player.uniqueId!=holder.owner || (holder.staff && !player.hasPermission("enthusiakoth.admin"))) { player.closeInventory(); return }
        if(event.clickedInventory!=event.view.topInventory || !event.isLeftClick || !player.hasPermission("enthusiakoth.command")) return
        val action=holder.actions[event.rawSlot] ?: return
        plugin.server.scheduler.runTask(plugin,Runnable {
            if(plugin.isEnabled && player.isOnline && player.hasPermission("enthusiakoth.command") &&
                player.openInventory.topInventory.holder===holder && (!holder.staff || player.hasPermission("enthusiakoth.admin"))) action()
        })
    }
    @EventHandler fun drag(event:InventoryDragEvent) { if(event.view.topInventory.holder is ProgressionMenu) event.isCancelled=true }
    @EventHandler fun joined(event:GuildMemberJoinEvent) { service.membershipChanged(event.playerId); store.change(event.playerId,event.guildId,"JOIN") }
    @EventHandler fun removed(event:GuildMemberRemovedEvent) { service.membershipChanged(event.playerId); store.change(event.playerId,event.guildId,"REMOVE") }
    @EventHandler fun relation(event:GuildRelationChangeEvent) {
        service.relationChanged(); store.change(null,event.guild1,"RELATION:${event.guild2}"); store.change(null,event.guild2,"RELATION:${event.guild1}")
    }
}
