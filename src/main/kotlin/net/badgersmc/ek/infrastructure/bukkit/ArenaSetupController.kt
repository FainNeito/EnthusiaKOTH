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

/** What a click in the arena editor does. Slots are a layout detail; the click handler only sees actions. */
enum class SetupAction { WAND, REGION, HEIGHT, CENTER, RADIUS, PREVIEW, FAMILY, NAME, DURATION, CAPTURE, LEAVE, INVENTORY, EXPERIENCE, ENABLED,
    MANAGE, TAB_AREA, TAB_RULES, TAB_REVIEW, CANCEL, SAVE }

/** Tabs across the top, the page's controls along the middle row, Discard / status / Save along the bottom. */
enum class ArenaSetupPage(private val layout: Map<Int, SetupAction>) {
    AREA(mapOf(8 to SetupAction.NAME, 19 to SetupAction.WAND, 20 to SetupAction.REGION, 21 to SetupAction.HEIGHT, 22 to SetupAction.CENTER,
        23 to SetupAction.RADIUS, 24 to SetupAction.PREVIEW, 25 to SetupAction.FAMILY)),
    RULES(mapOf(19 to SetupAction.DURATION, 20 to SetupAction.CAPTURE, 21 to SetupAction.LEAVE, 23 to SetupAction.INVENTORY,
        24 to SetupAction.EXPERIENCE, 25 to SetupAction.ENABLED)),
    REVIEW(mapOf(24 to SetupAction.MANAGE));

    /** Mode can change only while creating; advanced settings need a saved arena. */
    fun action(slot: Int, creating: Boolean): SetupAction? = (layout[slot] ?: SHARED[slot])?.takeUnless {
        (it == SetupAction.FAMILY && !creating) || (it == SetupAction.MANAGE && creating)
    }
    fun slotOf(action: SetupAction): Int? = layout.entries.firstOrNull { it.value == action }?.key

    companion object {
        val SHARED = mapOf(3 to SetupAction.TAB_AREA, 4 to SetupAction.TAB_RULES, 5 to SetupAction.TAB_REVIEW, 45 to SetupAction.CANCEL, 53 to SetupAction.SAVE)
        const val STATUS_SLOT = 49
    }
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

    private fun icon(material: Material, key: String, value: Any? = "", lore: String = "click", glow: Boolean = false) =
        MenuStyle.item(material, text(key, "value" to value), listOf(text(lore)), glow)
    private fun icon(material: Material, key: String, value: Any?, lore: List<Component>, glow: Boolean = false) =
        MenuStyle.item(material, text(key, "value" to value), lore, glow)

    private fun list(player: Player, page: Int) {
        val all = snapshot().arenas
        val ids = all.keys.sorted().drop(page * 45).take(45)
        val holder = ArenaSetupHolder(player.uniqueId, null, ids, page)
        val inv = Bukkit.createInventory(holder, 54, text("list-title")); holder.backing = inv
        ids.forEachIndexed { i, id ->
            val arena = all.getValue(id)
            inv.setItem(i, icon(MenuStyle.familyIcon(arena.family), "arena", net.badgersmc.ek.domain.ArenaName.resolve(id, arena.displayName).arenaComponent(), listOf(
                text("arena-id", "value" to id), MenuStyle.line("Mode", MenuStyle.label(arena.family)), MenuStyle.line("Enabled", MenuStyle.onOff(arena.enabled)), text("edit-hint"))))
        }
        if (page > 0) inv.setItem(45, icon(Material.ARROW, "previous"))
        inv.setItem(49, icon(Material.ANVIL, "create", lore = "create-hint"))
        if (all.size > (page + 1) * 45) inv.setItem(53, icon(Material.ARROW, "next"))
        MenuStyle.fill(inv)
        player.openInventory(inv)
    }

    private fun editor(player: Player, draft: ArenaSetupDraft, page: ArenaSetupPage = ArenaSetupPage.AREA) {
        val a = draft.arena
        val holder = ArenaSetupHolder(player.uniqueId, draft, editorPage = page)
        val name = net.badgersmc.ek.domain.ArenaName.resolve(draft.id, a.displayName).arenaComponent()
        val inv = Bukkit.createInventory(holder, 54, text("editor-title", "value" to name)); holder.backing = inv
        fun put(action: SetupAction, item: ItemStack) { page.slotOf(action)?.let { inv.setItem(it, item) } }
        val boundary = a.worldGuardRegion?.takeIf { it.isNotBlank() }?.let { "Region $it" } ?: if (draft.boundaryReady) "Selected" else "Not set"
        val center = "${a.center.x}, ${a.center.y}, ${a.center.z}"
        inv.setItem(0, icon(Material.PAPER, "draft-title", "${MenuStyle.label(a.family)} - ${a.world}", "draft-hint"))
        inv.setItem(8, icon(Material.NAME_TAG, "display-name", name, listOf(text("arena-id", "value" to draft.id),
            text(if (page == ArenaSetupPage.AREA) "display-name-hint" else "review-hint"))))
        listOf(Triple(ArenaSetupPage.AREA, Material.GRASS_BLOCK, 3), Triple(ArenaSetupPage.RULES, Material.BOOK, 4), Triple(ArenaSetupPage.REVIEW, Material.SPYGLASS, 5))
            .forEach { (tab, material, slot) ->
                inv.setItem(slot, icon(material, "tab-${tab.name.lowercase()}", lore = if (tab == page) "tab-current" else "tab-hint", glow = tab == page))
            }
        when (page) {
            ArenaSetupPage.AREA -> {
                put(SetupAction.WAND, icon(Material.WOODEN_AXE, "boundary", boundary, "boundary-hint"))
                put(SetupAction.REGION, icon(Material.MAP, "bind-region", lore = "bind-region-hint"))
                put(SetupAction.HEIGHT, icon(Material.SCAFFOLDING, "height", lore = "height-hint"))
                put(SetupAction.CENTER, icon(Material.COMPASS, "center", center, "center-hint"))
                put(SetupAction.RADIUS, icon(Material.TARGET, "radius", a.radius, "adjust-radius"))
                put(SetupAction.PREVIEW, icon(Material.ENDER_EYE, "preview", lore = "preview-hint"))
                if (draft.creating) put(SetupAction.FAMILY, icon(MenuStyle.familyIcon(a.family), "family", MenuStyle.label(a.family), "family-hint"))
            }
            ArenaSetupPage.RULES -> {
                put(SetupAction.DURATION, icon(Material.CLOCK, "duration", MenuStyle.duration(a.durationSeconds.toLong()), "adjust-minute"))
                put(SetupAction.CAPTURE, icon(Material.LIGHT_WEIGHTED_PRESSURE_PLATE, "capture", MenuStyle.duration(a.captureSeconds.toLong()), "adjust-capture"))
                put(SetupAction.LEAVE, icon(Material.REPEATER, "leave", MenuStyle.label(a.leaveBehavior), "leave-hint"))
                put(SetupAction.INVENTORY, icon(Material.CHEST, "inventory", MenuStyle.onOff(a.keepInventory), "inventory-hint"))
                put(SetupAction.EXPERIENCE, icon(Material.EXPERIENCE_BOTTLE, "experience", MenuStyle.onOff(a.keepExperience), "experience-hint"))
                put(SetupAction.ENABLED, icon(if (a.enabled) Material.LIME_DYE else Material.GRAY_DYE, "enabled", MenuStyle.onOff(a.enabled), "enable-hint"))
            }
            ArenaSetupPage.REVIEW -> {
                val r = text("review-hint")
                inv.setItem(20, icon(Material.MAP, "review-area", "", listOf(MenuStyle.line("Boundary", boundary), MenuStyle.line("Capture point", center),
                    MenuStyle.line("Capture area", "${a.radius} blocks out from the centre"), r)))
                inv.setItem(21, icon(Material.CLOCK, "review-rules", "", listOf(MenuStyle.line("Event length", MenuStyle.duration(a.durationSeconds.toLong())),
                    MenuStyle.line("Time to capture", MenuStyle.duration(a.captureSeconds.toLong())), MenuStyle.line("When the holder leaves", MenuStyle.label(a.leaveBehavior)), r)))
                inv.setItem(22, icon(Material.CHEST, "review-deaths", "", listOf(MenuStyle.line("Keep inventory", MenuStyle.onOff(a.keepInventory)),
                    MenuStyle.line("Keep XP", MenuStyle.onOff(a.keepExperience)), r)))
                inv.setItem(23, icon(if (a.enabled) Material.LIME_DYE else Material.GRAY_DYE, "review-status", "", listOf(MenuStyle.line("Arena enabled", MenuStyle.onOff(a.enabled)), r)))
                if (!draft.creating) put(SetupAction.MANAGE, icon(Material.WRITABLE_BOOK, "manage", lore = "manage-hint"))
            }
        }
        val issues = service.issues(draft)
        inv.setItem(ArenaSetupPage.STATUS_SLOT, MenuStyle.item(if (issues.isEmpty()) Material.LIME_CONCRETE else Material.RED_CONCRETE,
            text(if (issues.isEmpty()) "ready" else "not-ready"), issues.map { text("error.${it.name.lowercase()}") } + text("reward-hint")))
        inv.setItem(45, icon(Material.BARRIER, "cancel", lore = "cancel-hint"))
        inv.setItem(53, icon(Material.EMERALD, "save", lore = "save-hint"))
        MenuStyle.fill(inv)
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
                val action = holder.editorPage.action(slot, draft.creating) ?: return@guarded
                val a = draft.arena
                when (action) {
                    SetupAction.WAND -> { giveWand(player); return@guarded }
                    SetupAction.CENTER -> {
                        if (player.world.name != a.world) throw SetupException(SetupIssue.WORLD)
                        draft.arena = a.copy(center = position(player))
                    }
                    SetupAction.PREVIEW -> { preview(player, draft); return@guarded }
                    SetupAction.NAME -> { requestDisplayName(player, draft); return@guarded }
                    SetupAction.DURATION -> draft.arena = a.copy(durationSeconds = (a.durationSeconds + if (right) -60 else 60).coerceIn(15, 86400))
                    SetupAction.CAPTURE -> draft.arena = a.copy(captureSeconds = (a.captureSeconds + if (right) -15 else 15).coerceIn(1, 86400))
                    SetupAction.RADIUS -> draft.arena = a.copy(radius = (a.radius + if (right) -1 else 1).coerceIn(.5, 256.0))
                    SetupAction.INVENTORY -> draft.arena = a.copy(keepInventory = !a.keepInventory)
                    SetupAction.EXPERIENCE -> draft.arena = a.copy(keepExperience = !a.keepExperience)
                    SetupAction.LEAVE -> { val choices = listOf("RESET", "DECAY", "PAUSE"); draft.arena = a.copy(leaveBehavior = choices[(choices.indexOf(a.leaveBehavior) + 1) % choices.size]) }
                    SetupAction.HEIGHT -> {
                        if (!draft.boundaryReady || a.worldGuardRegion != null) throw SetupException(SetupIssue.BOUNDARY)
                        val world = Bukkit.getWorld(a.world) ?: throw SetupException(SetupIssue.WORLD)
                        draft.arena = a.copy(protectedRegion = a.protectedRegion.copy(
                            corner1 = a.protectedRegion.corner1.copy(y = world.minHeight.toDouble()),
                            corner2 = a.protectedRegion.corner2.copy(y = world.maxHeight.toDouble())))
                    }
                    SetupAction.ENABLED -> draft.arena = a.copy(enabled = !a.enabled)
                    SetupAction.FAMILY -> { val choices = listOf("capture", "moving", "conquest", "score"); draft.arena = a.copy(family = choices[(choices.indexOf(a.family) + 1) % choices.size]) }
                    SetupAction.REGION -> { regionList(player, draft, 0); return@guarded }
                    SetupAction.MANAGE -> {
                        if (snapshot().arenas[draft.id] != draft.arena) { tell(player, "save-first"); return@guarded }
                        clear(player); player.performCommand("ekoth manage ${draft.id}"); return@guarded
                    }
                    SetupAction.CANCEL -> { cancel(player); return@guarded }
                    SetupAction.TAB_AREA -> { editor(player, draft, ArenaSetupPage.AREA); return@guarded }
                    SetupAction.TAB_RULES -> { editor(player, draft, ArenaSetupPage.RULES); return@guarded }
                    SetupAction.TAB_REVIEW -> { editor(player, draft, ArenaSetupPage.REVIEW); return@guarded }
                    SetupAction.SAVE -> {
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
        ids.forEachIndexed { i, id -> inv.setItem(i, icon(Material.MAP, "arena", id, "region-pick-hint", glow = id == draft.arena.worldGuardRegion)) }
        if (page > 0) inv.setItem(45, icon(Material.ARROW, "previous"))
        inv.setItem(49, icon(Material.ARROW, "back", lore = "back-hint"))
        if (all.size > (page + 1) * 45) inv.setItem(53, icon(Material.ARROW, "next"))
        MenuStyle.fill(inv)
        player.openInventory(inv)
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun chat(event: AsyncChatEvent) {
        if (answer(event.player, PlainTextComponentSerializer.plainText().serialize(event.message()))) event.isCancelled = true
    }

    /**
     * A reply to a pending name prompt. Staff send it with /ekoth reply, which chat plugins never broadcast;
     * plain chat still works where no chat plugin takes the message first. Returns false when nothing is waiting.
     */
    fun answer(player: Player, raw: String): Boolean {
        val id = player.uniqueId
        val request = displayNamePrompts[id]
        if (request != null) {
            plugin.server.scheduler.runTask(plugin, Runnable {
                if (!displayNamePrompts.remove(id, request) || !player.isOnline || !allowed(player) || drafts[id] !== request.draft) return@Runnable
                guarded(player) {
                    val input = raw.trim()
                    if (!input.equals("cancel", true)) service.setName(request.draft, if (input == "-") null else input)
                }
                editor(player, request.draft)
            })
            return true
        }
        val token = pendingNames[id] ?: return false
        val name = raw.trim()
        plugin.server.scheduler.runTask(plugin, Runnable {
            if (!pendingNames.remove(id, token)) return@Runnable
            if (!player.isOnline || !allowed(player)) return@Runnable
            if (name.equals("cancel", true)) { tell(player, "discarded"); return@Runnable }
            create(player, name)
        })
        return true
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
