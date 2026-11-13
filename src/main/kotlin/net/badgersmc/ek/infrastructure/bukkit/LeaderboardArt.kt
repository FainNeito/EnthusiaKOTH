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

    /** The website's rank box: gold, silver and bronze for the podium, crimson with a gold rim for the rest. */
    fun rank(rank: Int): Component {
        val badge = when (rank) { 1 -> BADGE_GOLD; 2 -> BADGE_SILVER; 3 -> BADGE_BRONZE; else -> BADGE }
        val color: TextColor = if (rank <= 3) TextColor.color(0x3A0A07) else MenuStyle.GOLD
        val number = Component.text(rank.toString(), color)
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
