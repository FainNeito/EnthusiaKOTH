package net.badgersmc.ek.infrastructure.bukkit

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.TextComponent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class LeaderboardArtTest {
    private fun spaces(component: Component): Int {
        val own = (component as? TextComponent)?.content().orEmpty().sumOf { c ->
            when (c.code) {
                in 0xE010..0xE017 -> -(1 shl (c.code - 0xE010))
                in 0xE018..0xE01F -> 1 shl (c.code - 0xE018)
                else -> 0
            }
        }
        return own + component.children().sumOf(::spaces)
    }

    @Test fun `shifts are built from power-of-two spaces`() {
        listOf(-241, -128, -7, -1, 1, 13, 120, 300).forEach { assertEquals(it, spaces(LeaderboardArt.shift(it)), "shift $it") }
        assertEquals(Component.empty(), LeaderboardArt.shift(0))
    }

    @Test fun `text over art leaves the cursor exactly after the art`() {
        // Only the art's own advance remains: the text and both moves cancel out.
        listOf(1, 9, 10, 57, 999).forEach { rank ->
            val badge = LeaderboardArt.rank(rank)
            val number = TextWidth.of(rank.toString())
            assertEquals(-number, spaces(badge), "rank $rank")
        }
        val heading = Component.text("Lifetime  Players  Page 1 of 1")
        assertEquals(-TextWidth.of(heading), spaces(LeaderboardArt.banner(heading)))
    }

    @Test fun `rows and buttons end exactly after their art`() {
        // The left and right content plus every cursor move net out, so only the bar's own advance remains.
        assertEquals(-(12 + 6), spaces(LeaderboardArt.row(Component.text("ab"), 12, Component.text("c"), 6, viewer = false)))
        assertEquals(-TextWidth.of("Previous"), spaces(LeaderboardArt.button("Previous", LeaderboardArt.Button.DISABLED)))
        assertEquals(-TextWidth.of("Close"), spaces(LeaderboardArt.button("Close", LeaderboardArt.Button.NORMAL, wide = true)))
    }

    @Test fun `default font widths`() {
        assertEquals(6 * 3, TextWidth.of("abc"))
        assertEquals(2 + 3 + 4, TextWidth.of("ilt"))
        assertEquals(7 + 7 + 7 + 4, TextWidth.of("Abc ", bold = true))
    }
}
