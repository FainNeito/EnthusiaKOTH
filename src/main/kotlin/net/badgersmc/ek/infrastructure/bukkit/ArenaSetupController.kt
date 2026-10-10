package net.badgersmc.ek.infrastructure.bukkit

import net.badgersmc.ek.infrastructure.i18n.arenaComponent
import net.badgersmc.ek.infrastructure.i18n.arenaMsg
import io.papermc.paper.event.player.AsyncChatEvent
import net.badgersmc.ek.application.*
import net.badgersmc.ek.config.PositionConfig
import net.badgersmc.nexus.i18n.LangService
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.*
import org.bukkit.entity.Player
import org.bukkit.enchantments.Enchantment
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerDropItemEvent
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.inventory.*
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.scheduler.BukkitTask
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

enum class ArenaSetupPage(val slots: Set<Int>) {
    AREA(setOf(10, 11, 12, 13, 16, 22, 24, 25)), RULES(setOf(14, 15, 19, 20, 21, 23)), REVIEW(setOf(34));
    fun accepts(slot: Int) = slot in slots || slot in setOf(45, 46, 47, 48, 49)
}

class ArenaSetupHolder(val owner: UUID, val draft: ArenaSetupDraft?, val ids: List<String> = emptyList(), val page: Int = 0, val regions: Boolean = false, val editorPage: ArenaSetupPage = ArenaSetupPage.AREA) : InventoryHolder {
    lateinit var backing: Inventory
    override fun getInventory() = backing
}

/** Staff-only UI and selection adapter. Closing retains a draft; saving is explicit. */
class ArenaSetupController(
    private val plugin: JavaPlugin,
    private val service: ArenaSetupService,
    private val snapshot: () -> ArenaSetupSnapshot,
    private val applySaved: () -> Unit,
    private val lang: LangService,
    private val regionIds: (String) -> List<String> = { emptyList() },
) : Listener {
    private val drafts = mutableMapOf<UUID, ArenaSetupDraft>()
    private val firstCorners = mutableMapOf<UUID, PositionConfig>()
    private val selectionWorlds = mutableMapOf<UUID, String>()
    private val selecting = mutableSetOf<UUID>()
    private val previews = mutableMapOf<UUID, BukkitTask>()
    private val selectionPreviews = mutableMapOf<UUID, BukkitTask>()
    private val pendingNames = ConcurrentHashMap<UUID, UUID>()
    private data class DisplayNamePrompt(val token: UUID, val draft: ArenaSetupDraft)
    private val displayNamePrompts = ConcurrentHashMap<UUID, DisplayNamePrompt>()
    private val wandKey = NamespacedKey(plugin, "arena-setup-wand")
    private fun text(key: String, vararg values: Pair<String, Any?>) = lang.arenaMsg("setup.$key", *values)
    private fun tell(player: Player, key: String, vararg values: Pair<String, Any?>) = player.sendMessage(text(key, *values))
    private fun allowed(player: Player): Boolean {
        if (player.hasPermission("enthusiakoth.admin")) return true
        player.sendMessage(lang.msg("command.error.no_permission")); return false
    }
    private fun position(player: Player) = PositionConfig(player.location.blockX + .5, player.location.y, player.location.blockZ + .5)
    private fun guarded(player: Player, action: () -> Unit) {
        try { action() } catch (error: SetupException) {
            tell(player, "error.${error.issue.name.lowercase()}")
            if (error.issue == SetupIssue.IO) plugin.logger.warning("Arena setup storage failed: ${error.cause?.message}")
        }
    }

    fun open(player: Player, id: String? = null) {
        if (!allowed(player)) return
        guarded(player) {
            if (id != null) {
                val current = drafts[player.uniqueId]
                if (current != null && current.id != id) { tell(player, "finish-draft"); return@guarded }
                val draft = current ?: service.begin(id, player.world.name, position(player)).also { drafts[player.uniqueId] = it }
                editor(player, draft)
            } else drafts[player.uniqueId]?.let { editor(player, it) } ?: list(player, 0)
        }
    }

    fun create(player: Player, id: String, family: String = "capture") {
        if (!allowed(player)) return
        guarded(player) {
            if (drafts.containsKey(player.uniqueId)) { tell(player, "finish-draft"); return@guarded }
            val draft = service.begin(id, player.world.name, position(player), family)
            drafts[player.uniqueId] = draft; editor(player, draft)
        }
    }

    fun cancel(player: Player) {
        if (!allowed(player)) return
        clear(player); player.closeInventory(); tell(player, "discarded")
    }

    /** Prevent simultaneous geometry/settings drafts from overwriting each other. */
    fun releaseForManagement(player: Player): Boolean {
        val draft = drafts[player.uniqueId] ?: return true
        if (draft.creating || snapshot().arenas[draft.id] != draft.arena) { tell(player, "save-first"); return false }
        clear(player)
        return true
    }

    private fun icon(material: Material, key: String, value: Any? = "", lore: String = "click") = ItemStack(material).apply {
        editMeta { meta -> meta.displayName(text(key, "value" to value)); meta.lore(listOf(text(lore))) }
    }

    private fun list(player: Player, page: Int) {
        val all = snapshot().arenas
        val ids = all.keys.sorted().drop(page * 45).take(45)
        val holder = ArenaSetupHolder(player.uniqueId, null, ids, page)
        val inv = Bukkit.createInventory(holder, 54, text("list-title")); holder.backing = inv
        ids.forEachIndexed { i, id -> inv.setItem(i, icon(if (all.getValue(id).enabled) Material.LIME_DYE else Material.GRAY_DYE, "arena", net.badgersmc.ek.domain.ArenaName.resolve(id, all.getValue(id).displayName).arenaComponent(), "edit-hint").apply { editMeta { it.lore(listOf(text("arena-id", "value" to id), text("edit-hint"))) } }) }
        if (page > 0) inv.setItem(45, icon(Material.ARROW, "previous"))
        inv.setItem(49, icon(Material.ANVIL, "create", lore = "create-hint"))
        if (all.size > (page + 1) * 45) inv.setItem(53, icon(Material.ARROW, "next"))
        player.openInventory(inv)
    }

    private fun editor(player: Player, draft: ArenaSetupDraft, page: ArenaSetupPage = ArenaSetupPage.AREA) {
        val a = draft.arena
        val holder = ArenaSetupHolder(player.uniqueId, draft, editorPage = page)
        val inv = Bukkit.createInventory(holder, 54, text("editor-title", "value" to net.badgersmc.ek.domain.ArenaName.resolve(draft.id, a.displayName).arenaComponent())); holder.backing = inv
        inv.setItem(13, icon(Material.NAME_TAG, "display-name", net.badgersmc.ek.domain.ArenaName.resolve(draft.id, a.displayName).arenaComponent(), if (page == ArenaSetupPage.AREA) "display-name-hint" else "review-hint").apply { editMeta { it.lore(listOf(text("arena-id", "value" to draft.id), text(if (page == ArenaSetupPage.AREA) "display-name-hint" else "review-hint"))) } })
        inv.setItem(0, icon(Material.PAPER, "page-${page.name.lowercase()}", "${a.family} | ${a.world}", "draft-hint"))
        if (page == ArenaSetupPage.AREA) {
            inv.setItem(10, icon(Material.WOODEN_AXE, "boundary", a.worldGuardRegion ?: if (draft.boundaryReady) "Native selection" else "Not selected", "boundary-hint"))
            inv.setItem(11, icon(Material.COMPASS, "center", "${a.center.x}, ${a.center.y}, ${a.center.z}", "center-hint"))
            inv.setItem(12, icon(Material.END_ROD, "preview", lore = "preview-hint"))
            inv.setItem(16, icon(Material.TARGET, "radius", a.radius.toString(), "adjust-radius"))
            inv.setItem(22, icon(Material.SCAFFOLDING, "height", lore = "height-hint"))
            if (draft.creating) inv.setItem(24, icon(Material.NETHER_STAR, "family", a.family))
            inv.setItem(25, icon(Material.MAP, "bind-region", lore = "bind-region-hint"))
        }
        if (page == ArenaSetupPage.RULES) {
            inv.setItem(14, icon(Material.CLOCK, "duration", a.durationSeconds.toString(), "adjust-minute"))
            inv.setItem(15, icon(Material.CLOCK, "capture", a.captureSeconds.toString(), "adjust-capture"))
            inv.setItem(19, icon(Material.CHEST, "inventory", a.keepInventory.toString()))
            inv.setItem(20, icon(Material.EXPERIENCE_BOTTLE, "experience", a.keepExperience.toString(), "experience-hint"))
            inv.setItem(21, icon(Material.REPEATER, "leave", a.leaveBehavior))
            inv.setItem(23, icon(Material.LEVER, "enabled", a.enabled.toString(), "enable-hint"))
        }
        if (page == ArenaSetupPage.REVIEW) {
            inv.setItem(10, icon(Material.PAPER, "summary", "${a.family} | ${a.world}", "review-hint"))
            inv.setItem(11, icon(Material.TARGET, "radius", a.radius.toString(), "review-hint"))
            inv.setItem(12, icon(Material.COMPASS, "center", "${a.center.x}, ${a.center.y}, ${a.center.z}", "review-hint"))
            inv.setItem(14, icon(Material.CLOCK, "duration", a.durationSeconds.toString(), "review-hint"))
            inv.setItem(15, icon(Material.CLOCK, "capture", a.captureSeconds.toString(), "review-hint"))
            inv.setItem(19, icon(Material.CHEST, "inventory", a.keepInventory.toString(), "review-hint"))
            inv.setItem(20, icon(Material.EXPERIENCE_BOTTLE, "experience", a.keepExperience.toString(), "review-hint"))
            inv.setItem(21, icon(Material.REPEATER, "leave", a.leaveBehavior, "review-hint"))
            inv.setItem(23, icon(Material.LEVER, "enabled", a.enabled.toString(), "review-hint"))
            if (!draft.creating) inv.setItem(34, icon(Material.WRITABLE_BOOK, "manage", lore = "manage-hint"))
        }
        val issues = service.issues(draft)
        inv.setItem(31, ItemStack(if (issues.isEmpty()) Material.LIME_DYE else Material.RED_DYE).apply {
            editMeta { meta -> meta.displayName(text(if (issues.isEmpty()) "ready" else "not-ready"))
                meta.lore(issues.map { text("error.${it.name.lowercase()}") } + text("reward-hint")) }
        })
        inv.setItem(45, icon(Material.BARRIER, "cancel", lore = "cancel-hint"))
        ArenaSetupPage.entries.forEachIndexed { i, tab ->
            inv.setItem(46 + i, icon(if (tab == page) Material.LIME_DYE else Material.ARROW, "tab-${tab.name.lowercase()}", lore = "tab-hint"))
        }
        inv.setItem(49, icon(Material.EMERALD, "save", lore = "save-hint"))
        player.openInventory(inv)
    }

    @EventHandler fun click(event: InventoryClickEvent) {
        val holder = event.view.topInventory.holder as? ArenaSetupHolder ?: return
        event.isCancelled = true
        val player = event.whoClicked as? Player ?: return
        if (holder.owner != player.uniqueId || !allowed(player) || event.clickedInventory != event.view.topInventory) return
        val slot = event.rawSlot; val right = event.isRightClick
        // Inventory replacement occurs next tick, outside the inventory transaction.
        plugin.server.scheduler.runTask(plugin, Runnable {
            if (!player.isOnline || !allowed(player) || player.openInventory.topInventory.holder !== holder) return@Runnable
            guarded(player) {
                val draft = holder.draft
                if (draft == null) {
                    when {
                        slot in holder.ids.indices -> open(player, holder.ids[slot])
                        slot == 45 && holder.page > 0 -> list(player, holder.page - 1)
                        slot == 53 -> list(player, holder.page + 1)
                        slot == 49 -> requestName(player)
                    }
                    return@guarded
                }
                if (drafts[player.uniqueId] !== draft) return@guarded
                if (holder.regions) {
                    when {
                        slot in holder.ids.indices -> { draft.arena = draft.arena.copy(worldGuardRegion = holder.ids[slot]); draft.boundaryReady = true; editor(player, draft) }
                        slot == 45 && holder.page > 0 -> regionList(player, draft, holder.page - 1)
                        slot == 53 -> regionList(player, draft, holder.page + 1)
                        slot == 49 -> editor(player, draft)
                    }
                    return@guarded
                }
                if (!holder.editorPage.accepts(slot)) return@guarded
                val a = draft.arena
                when (slot) {
                    10 -> { giveWand(player); return@guarded }
                    11 -> {
                        if (player.world.name != a.world) throw SetupException(SetupIssue.WORLD)
                        draft.arena = a.copy(center = position(player))
                    }
                    12 -> { preview(player, draft); return@guarded }
                    13 -> { requestDisplayName(player, draft); return@guarded }
                    14 -> draft.arena = a.copy(durationSeconds = (a.durationSeconds + if (right) -60 else 60).coerceIn(15, 86400))
                    15 -> draft.arena = a.copy(captureSeconds = (a.captureSeconds + if (right) -15 else 15).coerceIn(1, 86400))
                    16 -> draft.arena = a.copy(radius = (a.radius + if (right) -1 else 1).coerceIn(.5, 256.0))
                    19 -> draft.arena = a.copy(keepInventory = !a.keepInventory)
                    20 -> draft.arena = a.copy(keepExperience = !a.keepExperience)
                    21 -> { val choices = listOf("RESET", "DECAY", "PAUSE"); draft.arena = a.copy(leaveBehavior = choices[(choices.indexOf(a.leaveBehavior) + 1) % choices.size]) }
                    22 -> {
                        if (!draft.boundaryReady || a.worldGuardRegion != null) throw SetupException(SetupIssue.BOUNDARY)
                        val world = Bukkit.getWorld(a.world) ?: throw SetupException(SetupIssue.WORLD)
                        draft.arena = a.copy(protectedRegion = a.protectedRegion.copy(
                            corner1 = a.protectedRegion.corner1.copy(y = world.minHeight.toDouble()),
                            corner2 = a.protectedRegion.corner2.copy(y = world.maxHeight.toDouble())))
                    }
                    23 -> draft.arena = a.copy(enabled = !a.enabled)
                    24 -> if (draft.creating) { val choices = listOf("capture", "moving", "conquest", "score"); draft.arena = a.copy(family = choices[(choices.indexOf(a.family) + 1) % choices.size]) }
                    25 -> { regionList(player, draft, 0); return@guarded }
                    34 -> if (!draft.creating) {
                        if (snapshot().arenas[draft.id] != draft.arena) { tell(player, "save-first"); return@guarded }
                        clear(player); player.performCommand("ekoth manage ${draft.id}"); return@guarded
                    }
                    45 -> { cancel(player); return@guarded }
                    46, 47, 48 -> { editor(player, draft, ArenaSetupPage.entries[slot - 46]); return@guarded }
                    49 -> {
                        service.save(draft); clear(player)
                        try { applySaved() } catch (error: Exception) {
                            plugin.logger.severe("Arena draft saved but runtime reload failed: ${error.message}")
                            tell(player, "saved-reload-failed"); player.closeInventory(); return@guarded
                        }
                        tell(player, "saved", "value" to draft.id)
                        val fresh = service.begin(draft.id, player.world.name, position(player))
                        drafts[player.uniqueId] = fresh; editor(player, fresh, ArenaSetupPage.REVIEW); return@guarded
                    }
                }
                editor(player, draft, holder.editorPage)
            }
        })
    }

    @EventHandler fun drag(event: InventoryDragEvent) {
        if (event.view.topInventory.holder is ArenaSetupHolder) event.isCancelled = true
    }

    private fun requestDisplayName(player: Player, draft: ArenaSetupDraft) {
        pendingNames.remove(player.uniqueId)
        val request = DisplayNamePrompt(UUID.randomUUID(), draft)
        displayNamePrompts[player.uniqueId] = request
        player.closeInventory(); tell(player, "display-name-prompt")
        plugin.server.scheduler.runTaskLater(plugin, Runnable {
            if (displayNamePrompts.remove(player.uniqueId, request) && player.isOnline) tell(player, "name-expired")
        }, 1200L)
    }

    private fun requestName(player: Player) {
        displayNamePrompts.remove(player.uniqueId)
        player.closeInventory(); tell(player, "name-prompt")
        val token = UUID.randomUUID(); pendingNames[player.uniqueId] = token
        plugin.server.scheduler.runTaskLater(plugin, Runnable {
            if (pendingNames.remove(player.uniqueId, token) && player.isOnline) tell(player, "name-expired")
        }, 1200L)
    }

    private fun regionList(player: Player, draft: ArenaSetupDraft, page: Int) {
        val all = regionIds(draft.arena.world).sorted()
        val ids = all.drop(page * 45).take(45)
        val holder = ArenaSetupHolder(player.uniqueId, draft, ids, page, true)
        val inv = Bukkit.createInventory(holder, 54, text("regions-title")); holder.backing = inv
        ids.forEachIndexed { i, id -> inv.setItem(i, icon(Material.MAP, "arena", id, "region-pick-hint")) }
        if (page > 0) inv.setItem(45, icon(Material.ARROW, "previous"))
        inv.setItem(49, icon(Material.ARROW, "back"))
        if (all.size > (page + 1) * 45) inv.setItem(53, icon(Material.ARROW, "next"))
        player.openInventory(inv)
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun chat(event: AsyncChatEvent) {
        val id = event.player.uniqueId
        val request = displayNamePrompts[id]
        if (request != null) {
            event.isCancelled = true
            val raw = PlainTextComponentSerializer.plainText().serialize(event.message())
            plugin.server.scheduler.runTask(plugin, Runnable {
                val player = event.player
                if (!displayNamePrompts.remove(id, request) || !player.isOnline || !allowed(player) || drafts[id] !== request.draft) return@Runnable
                guarded(player) {
                    val input = raw.trim()
                    if (!input.equals("cancel", true)) service.setName(request.draft, if (input == "-") null else raw)
                }
                editor(player, request.draft)
            })
            return
        }
        val token = pendingNames[id] ?: return
        event.isCancelled = true
        val name = PlainTextComponentSerializer.plainText().serialize(event.message()).trim()
        plugin.server.scheduler.runTask(plugin, Runnable {
            if (!pendingNames.remove(id, token)) return@Runnable
            if (!event.player.isOnline || !allowed(event.player)) return@Runnable
            if (name.equals("cancel", true)) { tell(event.player, "discarded"); return@Runnable }
            create(event.player, name)
        })
    }

    fun giveWand(player: Player) {
        if (!allowed(player)) return
        val draft = drafts[player.uniqueId] ?: run { tell(player, "open-first"); return }
        removeWands(player)
        val slot = player.inventory.firstEmpty()
        if (slot < 0) { tell(player, "inventory-full"); return }
        val wand = icon(Material.WOODEN_AXE, "wand", draft.id, "wand-hint")
        wand.editMeta {
            it.addEnchant(Enchantment.UNBREAKING, 1, true)
            it.addItemFlags(ItemFlag.HIDE_ENCHANTS)
            it.persistentDataContainer.set(wandKey, PersistentDataType.STRING, player.uniqueId.toString())
        }
        player.inventory.setItem(slot, wand)
        firstCorners.remove(player.uniqueId); selectionWorlds.remove(player.uniqueId); selecting.add(player.uniqueId)
        selectionPreviews.remove(player.uniqueId)?.cancel()
        previews.remove(player.uniqueId)?.cancel()
        player.closeInventory(); tell(player, "select-prompt")
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun interact(event: PlayerInteractEvent) {
        val item = event.item ?: return
        val owner = item.itemMeta?.persistentDataContainer?.get(wandKey, PersistentDataType.STRING) ?: return
        event.isCancelled = true
        val player = event.player
        if (event.hand != EquipmentSlot.HAND || !allowed(player) || owner != player.uniqueId.toString()) return
        val draft = drafts[player.uniqueId] ?: return
        if (player.uniqueId !in selecting) return
        val block = event.clickedBlock ?: return
        guarded(player) {
            if (block.world.name != draft.arena.world) throw SetupException(SetupIssue.WORLD)
            val point = PositionConfig(block.x.toDouble(), block.y.toDouble(), block.z.toDouble())
            when (event.action) {
                Action.LEFT_CLICK_BLOCK -> {
                    firstCorners[player.uniqueId] = point; selectionWorlds[player.uniqueId] = block.world.name
                    selectionOutline(player, draft)
                    tell(player, "first-corner")
                }
                Action.RIGHT_CLICK_BLOCK -> {
                    val first = firstCorners[player.uniqueId] ?: run { tell(player, "first-required"); return@guarded }
                    service.selectBoundary(draft, selectionWorlds.getValue(player.uniqueId), first, point)
                    selecting.remove(player.uniqueId); removeWands(player); tell(player, "selected")
                    selectionOutline(player, draft)
                    plugin.server.scheduler.runTask(plugin, Runnable { if (player.isOnline && allowed(player) && drafts[player.uniqueId] === draft) editor(player, draft) })
                }
                else -> Unit
            }
        }
    }

    private fun selectionOutline(player: Player, draft: ArenaSetupDraft) {
        selectionPreviews.remove(player.uniqueId)?.cancel()
        selectionPreviews[player.uniqueId] = plugin.server.scheduler.runTaskTimer(plugin, Runnable {
            if (!player.isOnline || !player.hasPermission("enthusiakoth.admin") ||
                drafts[player.uniqueId] !== draft || player.world.name != draft.arena.world) {
                selectionPreviews.remove(player.uniqueId)?.cancel(); return@Runnable
            }
            val selectingNow = player.uniqueId in selecting
            val points = if (selectingNow) {
                val first = firstCorners[player.uniqueId] ?: return@Runnable
                val target = player.getTargetBlockExact(6)
                SelectionOutline.selectedBlocks(first, target?.let {
                    PositionConfig(it.x.toDouble(), it.y.toDouble(), it.z.toDouble())
                } ?: first)
            } else {
                if (!draft.boundaryReady || draft.arena.worldGuardRegion != null) return@Runnable
                SelectionOutline.points(draft.arena.protectedRegion.corner1, draft.arena.protectedRegion.corner2)
            }
            val location = player.location
            val dust = Particle.DustOptions(if (selectingNow) Color.ORANGE else Color.AQUA, 1.4f)
            points.filter { point ->
                val dx = point.x - location.x; val dy = point.y - location.y; val dz = point.z - location.z
                dx * dx + dy * dy + dz * dz <= 64.0 * 64.0
            }.forEach { point ->
                player.spawnParticle(Particle.DUST, point.x, point.y, point.z, 1, 0.0, 0.0, 0.0, 0.0, dust)
            }
            if (selectingNow) firstCorners[player.uniqueId]?.let { first ->
                    SelectionOutline.selectedBlocks(first, first).filter { point ->
                        location.distanceSquared(Location(player.world, point.x, point.y, point.z)) <= 64.0 * 64.0
                    }.forEach { point ->
                        player.spawnParticle(Particle.DUST, point.x, point.y, point.z, 1, 0.0, 0.0, 0.0, 0.0,
                            Particle.DustOptions(Color.YELLOW, 1.8f))
                    }
            }
        }, 1L, 10L)
    }

    private fun preview(player: Player, draft: ArenaSetupDraft) {
        if (SetupIssue.GEOMETRY in service.issues(draft)) { tell(player, "error.geometry"); return }
        if (player.world.name != draft.arena.world) { tell(player, "error.world"); return }
        previews.remove(player.uniqueId)?.cancel()
        player.closeInventory(); tell(player, "preview-prompt")
        var frames = 0
        val a = draft.arena
        val points = mutableListOf<PositionConfig>()
        if (a.radius.isFinite() && a.radius in .1..256.0) repeat(48) { n ->
            val angle = n * Math.PI * 2 / 48
            points += PositionConfig(a.center.x + kotlin.math.cos(angle) * a.radius, a.center.y + .1, a.center.z + kotlin.math.sin(angle) * a.radius)
        }
        if (draft.boundaryReady && a.worldGuardRegion == null) {
            val p = a.protectedRegion.corner1; val q = a.protectedRegion.corner2
            val corners = listOf(PositionConfig(p.x, p.y, p.z), PositionConfig(q.x, p.y, p.z), PositionConfig(q.x, p.y, q.z), PositionConfig(p.x, p.y, q.z),
                PositionConfig(p.x, q.y, p.z), PositionConfig(q.x, q.y, p.z), PositionConfig(q.x, q.y, q.z), PositionConfig(p.x, q.y, q.z))
            val edges = listOf(0 to 1, 1 to 2, 2 to 3, 3 to 0, 4 to 5, 5 to 6, 6 to 7, 7 to 4, 0 to 4, 1 to 5, 2 to 6, 3 to 7)
            edges.forEach { (i, j) -> repeat(9) { n -> val t = n / 8.0; val from = corners[i]; val to = corners[j]
                points += PositionConfig(from.x + (to.x - from.x) * t, from.y + (to.y - from.y) * t, from.z + (to.z - from.z) * t)
            } }
        }
        val task = plugin.server.scheduler.runTaskTimer(plugin, Runnable {
            if (++frames > 30 || !player.isOnline || !player.hasPermission("enthusiakoth.admin") || drafts[player.uniqueId] !== draft) {
                previews.remove(player.uniqueId)?.cancel(); return@Runnable
            }
            if (player.world.name == a.world) points.forEach { point -> player.spawnParticle(Particle.END_ROD, point.x, point.y, point.z, 1, 0.0, 0.0, 0.0, 0.0) }
        }, 1L, 20L)
        previews[player.uniqueId] = task
    }

    private fun removeWands(player: Player) {
        player.inventory.contents.forEachIndexed { index, item ->
            if (item?.itemMeta?.persistentDataContainer?.has(wandKey, PersistentDataType.STRING) == true) player.inventory.setItem(index, null)
        }
    }
    private fun clear(player: Player) {
        drafts.remove(player.uniqueId); firstCorners.remove(player.uniqueId); selectionWorlds.remove(player.uniqueId)
        selecting.remove(player.uniqueId); pendingNames.remove(player.uniqueId); displayNamePrompts.remove(player.uniqueId); previews.remove(player.uniqueId)?.cancel(); removeWands(player)
        selectionPreviews.remove(player.uniqueId)?.cancel()
    }
    @EventHandler fun quit(event: PlayerQuitEvent) = clear(event.player)
    @EventHandler fun changeWorld(event: PlayerChangedWorldEvent) { selectionPreviews.remove(event.player.uniqueId)?.cancel() }
    @EventHandler(priority = EventPriority.HIGHEST) fun breakBlock(event: BlockBreakEvent) {
        if (event.player.inventory.itemInMainHand.itemMeta?.persistentDataContainer?.has(wandKey, PersistentDataType.STRING) == true) event.isCancelled = true
    }
    @EventHandler fun drop(event: PlayerDropItemEvent) {
        if (event.itemDrop.itemStack.itemMeta?.persistentDataContainer?.has(wandKey, PersistentDataType.STRING) == true) event.isCancelled = true
    }
    fun shutdown() { previews.values.forEach { it.cancel() }; selectionPreviews.values.forEach { it.cancel() }; selectionPreviews.clear(); Bukkit.getOnlinePlayers().forEach(::clear); pendingNames.clear(); displayNamePrompts.clear() }
}
