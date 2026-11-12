package net.badgersmc.ek.infrastructure.bukkit

import net.badgersmc.ek.application.*
import net.badgersmc.ek.config.EnthusiaKothConfig
import net.badgersmc.ek.domain.KothArena
import net.badgersmc.ek.infrastructure.persistence.SqlProgressionStore
import net.badgersmc.ek.infrastructure.protection.WorldGuardRegionService
import net.badgersmc.ek.infrastructure.lumaguilds.LumaGuildsAdapter
import net.kyori.adventure.text.Component
import net.badgersmc.ek.infrastructure.i18n.arenaComponent
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

internal data class MenuEntry(val icon:Material, val name:Component, val lore:List<Component>, val glow:Boolean=false)

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
    /** Tab-completion sources for staff commands. */
    fun heldMatchIds():List<String> = store.decisions().map { it.id }
    fun recentMatchIds():List<String> = store.history(20).map { it.id }
    fun reviewClaimIds():List<String> = store.reviewClaimIds()
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
        val entries=mutableListOf<MenuEntry>()
        val actions=mutableMapOf<Int,()->Unit>()
        fun add(entry:MenuEntry, action:(()->Unit)?=null) { action?.let { actions[entries.size]=it }; entries+=entry }
        when(page) {
            "home" -> {
                val ready=store.claims(player.uniqueId).count { it.status=="PENDING" }
                add(entry(Material.DIAMOND_SWORD,"Challenges",listOf(text("Long-term goals and exclusive prizes."),hint("Click to see your progress")))) { open(player,"challenges") }
                add(entry(Material.CHEST,"Your rewards",listOfNotNull(text("Claim what you have earned."),
                    if(ready>0) line("Ready to claim",ready) else null,hint("Click to open")),glow=ready>0)) { open(player,"claims") }
                add(entry(Material.BOOK,"Match results",listOf(text("Your recent public matches."),hint("Click to open")))) { open(player,"results") }
                add(entry(Material.OAK_SIGN,"How to earn",listOf(text("Score for your side in public matches."),
                    text("Allies don't count as opponents."),text("Private tests never count."),hint("Click for the full rules")))) {
                    player.closeInventory(); eligibility(player).forEach { player.sendMessage(it) }
                }
                add(entry(Material.BEACON,"Arenas",listOf(text("See what's running or start an arena."),hint("Click to open")))) { player.performCommand("ekoth gui") }
                add(entry(Material.CLOCK,"Schedule",listOf(text("When the next events start."),hint("Click to show in chat")))) {
                    player.closeInventory(); player.performCommand("ekoth schedule")
                }
                add(entry(Material.GOLD_INGOT,"Leaderboards",listOf(text("Top players by wins."),hint("Click to show in chat")))) {
                    player.closeInventory(); player.performCommand("ekoth top")
                }
            }
            "results" -> {
                store.results(player.uniqueId,100).forEach { r ->
                    val (icon,state,color)=resultState(r.status,r.qualifying)
                    add(MenuEntry(icon,arenaName(r.arena).append(Component.text(" - $state",color)),listOf(
                        line("Played",playedAt(r.at)),line("Your scoring time",MenuStyle.duration(r.scoringSeconds)),
                        line("Counts toward challenges",if(r.qualifying) "Yes" else "No"))+wrap(r.detail)+hint("Click to see your rewards"))) { open(player,"claims") }
                }
                val progress=store.progress(player.uniqueId)
                add(MenuEntry(Material.EXPERIENCE_BOTTLE,Component.text("Challenge progress",MenuStyle.GOLD),
                    (progress?.map { line(MenuStyle.label(it.key),"${it.value}%") } ?: listOf(text("Not tracked yet.")))+hint("Click to see challenges"))) { open(player,"challenges") }
            }
            "history" -> store.history(100).forEach { m ->
                add(MenuEntry(if(m.winner==null) Material.GRAY_DYE else Material.LIME_DYE,
                    arenaName(m.arena).append(Component.text(" - ${playedAt(m.at)}",MenuStyle.MUTED)),listOf(
                        line("Winner",winnerName(m.winner)),line("Result",MenuStyle.label(m.detail.substringBefore(';').ifBlank { "-" })),
                        hint("Click to print the full report")))) { player.closeInventory(); command(player,arrayOf("reports",m.id)) }
            }
            "claims" -> store.claims(player.uniqueId).forEach { c ->
                val pending=c.status=="PENDING"
                val (statusText,statusColor)=claimStatus(c.status)
                add(MenuEntry(claimIcon(c.kind),Component.text(claimKind(c.kind),if(pending) MenuStyle.GOLD else MenuStyle.CREAM),listOfNotNull(
                    if(c.kind=="MONEY") line("Amount",java.math.BigDecimal.valueOf(c.amount,2).toPlainString()) else line("Reward",MenuStyle.label(c.value)),
                    Component.text("Status: ",MenuStyle.MUTED).append(Component.text(statusText,statusColor)),
                    c.detail.takeIf { it.isNotBlank() }?.let(::text),
                    hint(when { c.kind=="CHOICE" && pending -> "Click to choose a package"; pending -> "Click to claim"; else -> "Click to check delivery" })),
                    glow=pending)) {
                    if(c.kind=="CHOICE" && pending) choose(player,c)
                    else { player.sendMessage(claims.redeem(player.uniqueId,c.id)); open(player,"claims",index) }
                }
            }
            else -> {
                val progress=store.progress(player.uniqueId)
                settings.policy().challenges.forEach { rule ->
                    val percent=progress?.get(rule.id)
                    add(MenuEntry(when { rule.exclusive -> Material.NETHER_STAR; rule.wins>=25 -> Material.DIAMOND_SWORD; else -> Material.IRON_SWORD },
                        Component.text(MenuStyle.label(rule.id),MenuStyle.GOLD),listOf(
                            line("Progress",percent?.let { "$it%" } ?: "Not tracked yet"),Component.empty(),
                            line("Wins",rule.wins),line("Different opponents",rule.opponents),line("Different arenas",rule.arenas),
                            line("Separate days",rule.days),line("Time scoring",MenuStyle.duration(rule.seconds)),Component.empty(),
                            if(!rule.exclusive) text("Every player can earn this.")
                            else Component.text(when(store.exclusiveOwner(rule.id)) {
                                null -> "Only one player can ever earn this."
                                player.uniqueId -> "You earned this. No one else can."
                                else -> "Already earned by another player."
                            },MenuStyle.GOLD)),
                        glow=(percent ?: 0)>=100))
                }
            }
        }
        if(entries.isEmpty()) entries += when(page) {
            "claims" -> entry(Material.LIGHT_GRAY_DYE,"No rewards yet",listOf(text("Win qualifying public matches and challenges."),text("Challenges shows what to aim for.")))
            "results" -> entry(Material.LIGHT_GRAY_DYE,"No matches yet",listOf(text("Your public matches will appear here.")))
            else -> entry(Material.LIGHT_GRAY_DYE,"Nothing here yet",listOf(text("Ask staff if this feature is still being set up.")))
        }
        val title=when(page) { "home" -> "KOTH"; "claims" -> "Your KOTH rewards"; "results" -> "Your match results"; "history" -> "Match history"; else -> "KOTH challenges" }
        menu(player,title,page=="history",entries,actions,index,
            { open(player,page,it) }, { if(page=="home") player.closeInventory() else open(player,"home") },
            if(page=="home") "Close" else "Back")
    }
    private fun choose(player:Player,c:ProgressionClaim) {
        val options=store.options(c.id,player.uniqueId)
        menu(player,"Choose a reward package",false,options.map { MenuEntry(Material.CHEST,Component.text(it,MenuStyle.GOLD),listOf(
                text("An equal share of this event's reward pool."),Component.text("You can't change this later.",MenuStyle.CORAL),hint("Click to choose"))) },
            options.indices.associateWith { i -> { confirmChoice(player,c,options[i]) } },0,{}, { open(player,"claims") },"Back")
    }
    /** A permanent choice gets its own yes/no screen. */
    private fun confirmChoice(player:Player,c:ProgressionClaim,option:String) {
        val actions=mapOf<Int,()->Unit>(
            CONFIRM_SLOT to {
                val chosen=store.choose(c.id,player.uniqueId,option)
                player.sendMessage(Component.text(if(chosen) "Package selected." else "That package is no longer available.",if(chosen) MenuStyle.GOLD else MenuStyle.CORAL))
                open(player,"claims")
            },
            BACK_SLOT to { choose(player,c) })
        val holder=ProgressionMenu(player.uniqueId,false,actions)
        holder.backing=Bukkit.createInventory(holder,27,MenuStyle.title("Choose $option?"))
        holder.backing.setItem(CONFIRM_SLOT,MenuStyle.item(Material.LIME_CONCRETE,Component.text("Choose $option",MenuStyle.GOLD),listOf(Component.text("This can't be changed later.",MenuStyle.CORAL))))
        holder.backing.setItem(BACK_SLOT,MenuStyle.item(Material.RED_CONCRETE,Component.text("Go back",MenuStyle.CORAL),listOf(text("Pick a different package."))))
        MenuStyle.fill(holder.backing)
        player.openInventory(holder.backing)
    }
    /** Entries sit inside a one-slot border, seven to a row, with a footer row for page controls. */
    private fun menu(player:Player,title:String,staff:Boolean,entries:List<MenuEntry>,allActions:Map<Int,()->Unit>,index:Int,next:(Int)->Unit,back:()->Unit,backName:String) {
        val pages=((entries.size+PAGE_SIZE-1)/PAGE_SIZE).coerceAtLeast(1)
        val page=index.coerceIn(0,pages-1)
        val shown=entries.drop(page*PAGE_SIZE).take(PAGE_SIZE)
        val rows=(2+(shown.size+6)/7).coerceIn(3,6)
        val footer=(rows-1)*9
        val actions=mutableMapOf<Int,()->Unit>()
        val slots=shown.indices.map { 9*(1+it/7)+1+it%7 }
        shown.indices.forEach { i -> allActions[page*PAGE_SIZE+i]?.let { actions[slots[i]]=it } }
        if(page>0) actions[footer]={ next(page-1) }
        if(page+1<pages) actions[footer+8]={ next(page+1) }
        actions[footer+4]=back
        val tags=Bukkit.getPluginManager().getPlugin("EnthusiaTags")?.isEnabled==true
        if(tags) actions[footer+3]={ player.performCommand("rewards") }
        val holder=ProgressionMenu(player.uniqueId,staff,actions)
        holder.backing=Bukkit.createInventory(holder,rows*9,MenuStyle.title(if(pages>1) "$title (${page+1}/$pages)" else title))
        shown.forEachIndexed { i,e -> holder.backing.setItem(slots[i],MenuStyle.item(e.icon,e.name,e.lore,e.glow)) }
        if(page>0) holder.backing.setItem(footer,MenuStyle.item(Material.ARROW,Component.text("Previous page",MenuStyle.GOLD)))
        if(page+1<pages) holder.backing.setItem(footer+8,MenuStyle.item(Material.ARROW,Component.text("Next page",MenuStyle.GOLD)))
        holder.backing.setItem(footer+4,if(backName=="Close") MenuStyle.item(Material.BARRIER,Component.text("Close",MenuStyle.CORAL))
            else MenuStyle.item(Material.ARROW,Component.text(backName,MenuStyle.GOLD)))
        if(tags) holder.backing.setItem(footer+3,MenuStyle.item(Material.CHEST,Component.text("Open /rewards",MenuStyle.GOLD)))
        MenuStyle.fill(holder.backing)
        player.openInventory(holder.backing)
    }
    private fun entry(icon:Material,name:String,lore:List<Component>,glow:Boolean=false)=MenuEntry(icon,Component.text(name,MenuStyle.GOLD),lore,glow)
    private fun text(value:String)=MenuStyle.text(value)
    private fun hint(value:String)=MenuStyle.hint(value)
    private fun line(label:String,value:Any?)=MenuStyle.line(label,value)
    private fun arenaName(id:String)=(arenas()[id]?.name ?: id).arenaComponent()
    private fun playedAt(at:java.time.Instant)=PLAYED.format(at)
    private fun winnerName(winner:String?):String = when {
        winner==null -> "No winner"
        winner.startsWith("solo:") -> runCatching { java.util.UUID.fromString(winner.removePrefix("solo:")) }.getOrNull()
            ?.let { Bukkit.getOfflinePlayer(it).name } ?: winner.removePrefix("solo:")
        winner.startsWith("guild:") -> "Guild ${winner.removePrefix("guild:")}"
        else -> winner
    }
    /** Short word-wrapped lines; lore wider than about 40 characters runs off small screens. */
    private fun wrap(value:String,width:Int=40):List<Component> = value.split(' ').filter { it.isNotEmpty() }
        .fold(mutableListOf<String>()) { lines,word -> if(lines.isNotEmpty() && lines.last().length+word.length<width) lines[lines.size-1]="${lines.last()} $word" else lines.add(word); lines }
        .map(::text)
    private fun resultState(status:String,qualifying:Boolean):Triple<Material,String,net.kyori.adventure.text.format.TextColor> = when(status) {
        "HELD" -> Triple(Material.YELLOW_DYE,"Under review",MenuStyle.GOLD)
        "REJECTED" -> Triple(Material.RED_DYE,"Not counted",MenuStyle.CORAL)
        "APPROVED" -> if(qualifying) Triple(Material.LIME_DYE,"Counted",MenuStyle.GOLD) else Triple(Material.GRAY_DYE,"No credit",MenuStyle.MUTED)
        else -> Triple(Material.GRAY_DYE,"Older match",MenuStyle.MUTED)
    }
    private fun claimIcon(kind:String)=when(kind) { "MONEY" -> Material.GOLD_INGOT; "LORE" -> Material.ENCHANTED_BOOK; "CHOICE" -> Material.BUNDLE; else -> Material.PAPER }
    private fun claimKind(value:String)=when(value) { "MONEY" -> "Coins"; "LORE" -> "Item"; "CHOICE" -> "Reward package"; else -> MenuStyle.label(value) }
    private fun claimStatus(value:String):Pair<String,net.kyori.adventure.text.format.TextColor> = when(value) {
        "PENDING" -> "Ready to claim" to MenuStyle.GOLD; "SENDING" -> "Being delivered" to MenuStyle.GOLD
        "QUEUED" -> "Queued for delivery" to MenuStyle.GOLD; "REVIEW" -> "Waiting for staff review" to MenuStyle.GOLD
        "PAID", "GRANTED", "DISPATCHED" -> "Delivered" to MenuStyle.MUTED; else -> MenuStyle.label(value) to MenuStyle.MUTED
    }
    private companion object {
        const val PAGE_SIZE=28
        const val CONFIRM_SLOT=11
        const val BACK_SLOT=15
        val PLAYED: java.time.format.DateTimeFormatter =
            java.time.format.DateTimeFormatter.ofPattern("MMM d, HH:mm 'UTC'", java.util.Locale.ENGLISH).withZone(java.time.ZoneOffset.UTC)
    }
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
