package net.badgersmc.ek.infrastructure.bukkit

import net.kyori.adventure.key.Key
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.Style
import net.kyori.adventure.text.format.TextColor

/**
 * The Enthusia leaderboard art shipped by the Enthusia-Pack (font `enthusiakoth:leaderboard`, packed by Nexo).
 * Text is drawn over the art by moving the cursor with the font's space characters, so this must stay in step
 * with `assets/enthusiakoth/font/leaderboard.json` in the pack.
 */
object LeaderboardArt {
    val FONT: Key = Key.key("enthusiakoth", "leaderboard")
    private const val BANNER = ''
    private const val BADGE_GOLD = ''
    private const val BADGE_SILVER = ''
    private const val BADGE_BRONZE = ''
    private const val BADGE = ''
    private const val DIVIDER = ''

    /** Glyph widths in GUI pixels, including the one-pixel gap the client adds after a bitmap glyph. */
    const val BANNER_ADVANCE = 241
    const val BADGE_ADVANCE = 15
    const val DIVIDER_ADVANCE = 241

    /** Space characters U+E010..U+E017 move left by 1, 2, 4 ... 128; U+E018..U+E01F move right by the same. */
    private val LEFT = (0..7).map { (0xE010 + it).toChar() }
    private val RIGHT = (0..7).map { (0xE018 + it).toChar() }

    private val art = Style.style().font(FONT).color(NamedTextColor.WHITE).build()

    private fun glyph(char: Char): Component = Component.text(char.toString(), art)

    /** A cursor move of [pixels] (negative moves left) written with the font's power-of-two spaces. */
    fun shift(pixels: Int): Component {
        if (pixels == 0) return Component.empty()
        val chars = if (pixels < 0) LEFT else RIGHT
        var left = kotlin.math.abs(pixels)
        val text = StringBuilder()
        for (bit in 7 downTo 0) {
            val step = 1 shl bit
            while (left >= step) { text.append(chars[bit]); left -= step }
        }
        return Component.text(text.toString(), Style.style().font(FONT).build())
    }

    /** [content] centred on top of [glyph], leaving the cursor after the glyph. */
    private fun over(glyph: Char, advance: Int, content: Component, width: Int): Component {
        val back = (advance + width) / 2
        return Component.text().append(glyph(glyph)).append(shift(-back)).append(content).append(shift(back - width)).build()
    }

    fun banner(content: Component): Component = over(BANNER, BANNER_ADVANCE, content, TextWidth.of(content))

    fun divider(): Component = glyph(DIVIDER)

    private const val ROW = ''
    private const val ROW_VIEWER = ''
    const val ROW_ADVANCE = 241
    private const val ROW_PADDING = 5

    /** A website-style row bar: [left] from the left edge, [right] against the right edge. */
    fun row(left: Component, leftWidth: Int, right: Component, rightWidth: Int, viewer: Boolean): Component {
        val gap = ROW_ADVANCE - 2 * ROW_PADDING - leftWidth - rightWidth
        return Component.text()
            .append(glyph(if (viewer) ROW_VIEWER else ROW)).append(shift(-(ROW_ADVANCE - ROW_PADDING)))
            .append(left).append(shift(gap)).append(right).append(shift(ROW_PADDING))
            .build()
    }

    /** Button looks: the standard crimson, gold for the view on screen, dimmed when there is nowhere to go. */
    enum class Button { NORMAL, SELECTED, DISABLED }

    private const val BUTTON = ''
    private const val BUTTON_SELECTED = ''
    private const val BUTTON_DISABLED = ''
    private const val BUTTON_WIDE = ''
    const val BUTTON_ADVANCE = 116
    const val BUTTON_WIDE_ADVANCE = 240

    /** A button label drawn on button art that fills the dialog button's label area. */
    fun button(label: String, look: Button, wide: Boolean = false): Component {
        val color: TextColor = when (look) {
            Button.NORMAL -> MenuStyle.CREAM
            Button.SELECTED -> TextColor.color(0x7A110C)
            Button.DISABLED -> MenuStyle.MUTED
        }
        val glyph = when {
            wide -> BUTTON_WIDE
            look == Button.SELECTED -> BUTTON_SELECTED
            look == Button.DISABLED -> BUTTON_DISABLED
            else -> BUTTON
        }
        // Dark text on the gold button loses its drop shadow, which otherwise smears into the gold.
        val text = Component.text(label, color).let {
            if (look == Button.SELECTED) it.shadowColor(net.kyori.adventure.text.format.ShadowColor.none()) else it
        }
        return over(glyph, if (wide) BUTTON_WIDE_ADVANCE else BUTTON_ADVANCE, text, TextWidth.of(label))
    }

    /** Where a line sits in the panel: the heading carries the top cap, the last line the part behind the buttons. */
    enum class Slice(val char: Char) { TOP(''), MIDDLE(''), BOTTOM('') }
    const val PANEL_ADVANCE = 257

    /**
     * [content] on its slice of the background panel. Each dialog line draws the slice from just above it to the next
     * line, so the slices meet into one panel; the bottom slice reaches down behind the buttons, which the client draws
     * on top. The slice is centred under [content] and takes no width, so the line still centres on [content] alone.
     */
    fun onPanel(content: Component, contentWidth: Int, slice: Slice): Component {
        val before = (contentWidth - PANEL_ADVANCE) / 2
        return Component.text().append(shift(before)).append(glyph(slice.char)).append(shift(-PANEL_ADVANCE - before)).append(content).build()
    }

    /** Width of an inline player head object: an 8 px face and the 1 px gap after it. */
    const val HEAD_ADVANCE = 9

    /** The website's rank box: gold, silver and bronze for the podium, crimson with a gold rim for the rest. */
    fun rank(rank: Int): Component {
        val badge = when (rank) { 1 -> BADGE_GOLD; 2 -> BADGE_SILVER; 3 -> BADGE_BRONZE; else -> BADGE }
        val color: TextColor = if (rank <= 3) TextColor.color(0x3A0A07) else MenuStyle.GOLD
        val number = Component.text(rank.toString(), color)
            .let { if (rank <= 3) it.shadowColor(net.kyori.adventure.text.format.ShadowColor.none()) else it }
        return over(badge, BADGE_ADVANCE, number, TextWidth.of(number))
    }
}

/** Advances of the default Minecraft font, enough to centre short labels over art. */
object TextWidth {
    private val narrow = mapOf('!' to 2, ',' to 2, '.' to 2, ':' to 2, ';' to 2, '|' to 2, '\'' to 2, 'i' to 2,
        'l' to 3, '`' to 3, ' ' to 4, 'I' to 4, 't' to 4, '[' to 4, ']' to 4,
        'f' to 5, 'k' to 5, '(' to 5, ')' to 5, '{' to 5, '}' to 5, '<' to 5, '>' to 5, '"' to 5, '*' to 5, '@' to 7, '~' to 7)

    fun of(text: String, bold: Boolean = false): Int = text.sumOf { (narrow[it] ?: 6) + if (bold && it != ' ') 1 else 0 }

    fun of(component: Component): Int {
        val own = (component as? net.kyori.adventure.text.TextComponent)?.content().orEmpty()
        val bold = component.style().decoration(net.kyori.adventure.text.format.TextDecoration.BOLD) == net.kyori.adventure.text.format.TextDecoration.State.TRUE
        return of(own, bold) + component.children().sumOf(::of)
    }
}
