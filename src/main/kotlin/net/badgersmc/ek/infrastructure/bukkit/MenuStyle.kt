package net.badgersmc.ek.infrastructure.bukkit

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.ShadowColor
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Material
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack

/** One look for every KOTH menu: upright text, a quiet background and plain-language values. */
internal object MenuStyle {
    /** The Enthusia message studio palette ("Balanced variation"): cream text, gold names and values, muted secondary text, coral problems. */
    val CREAM: TextColor = TextColor.color(0xFFF6E0)
    val GOLD: TextColor = TextColor.color(0xFFCF1F)
    val MUTED: TextColor = TextColor.color(0xBFAE98)
    val CORAL: TextColor = TextColor.color(0xFF8F82)
    private val SHADOW: ShadowColor = ShadowColor.shadowColor(0, 0, 0, 255)

    /** Menu titles are gold with a shadow, like the studio's KOTH menu title. */
    fun title(value: String): Component = Component.text(value, GOLD).shadowColor(SHADOW)

    /** Minecraft italicises custom item text unless told otherwise. */
    fun upright(text: Component): Component = text.decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE)

    fun item(material: Material, name: Component, lore: List<Component> = emptyList(), glow: Boolean = false): ItemStack =
        ItemStack(material).apply {
            editMeta { meta ->
                meta.displayName(upright(name))
                meta.lore(lore.map(::upright))
                if (glow) meta.setEnchantmentGlintOverride(true)
            }
        }

    fun text(value: String, color: TextColor = CREAM): Component = Component.text(value, color)

    /** A label and its value, the way every menu line reads. */
    fun line(label: String, value: Any?): Component =
        Component.text("$label: ", CREAM).append(Component.text(value?.toString() ?: "-", GOLD))

    fun hint(value: String): Component = Component.text(value, GOLD)

    /** Empty slots get a pane with no tooltip, so the menu's real buttons stand out. */
    fun fill(inventory: Inventory) {
        val pane = ItemStack(Material.GRAY_STAINED_GLASS_PANE).apply { editMeta { it.isHideTooltip = true } }
        for (slot in 0 until inventory.size) if (inventory.getItem(slot) == null) inventory.setItem(slot, pane)
    }

    fun onOff(value: Any?): String = if (value == true || value.toString().equals("true", true)) "On" else "Off"

    fun list(value: Any?): String = (value as? Collection<*>)?.filterNotNull()?.takeIf { it.isNotEmpty() }?.joinToString(", ") ?: "None"

    /** 90 -> "1m 30s", 3600 -> "1h", 1800 -> "30m". */
    fun duration(seconds: Long): String {
        val safe = seconds.coerceAtLeast(0)
        val h = safe / 3600; val m = safe % 3600 / 60; val s = safe % 60
        return listOfNotNull(h.takeIf { it > 0 }?.let { "${it}h" }, m.takeIf { it > 0 }?.let { "${it}m" }, s.takeIf { it > 0 || safe == 0L }?.let { "${it}s" })
            .joinToString(" ")
    }

    /** "capture" -> "Capture", "sovereign_blade" -> "Sovereign Blade". */
    fun label(value: String): String = value.lowercase().split('_', '-').filter { it.isNotEmpty() }.joinToString(" ") { it.replaceFirstChar(Char::uppercaseChar) }

    /** The same icon for an arena's mode everywhere it appears. */
    fun familyIcon(family: String): Material = when (family.lowercase()) {
        "moving" -> Material.COMPASS
        "conquest" -> Material.BELL
        "score" -> Material.GOLD_INGOT
        else -> Material.WHITE_BANNER
    }
}
