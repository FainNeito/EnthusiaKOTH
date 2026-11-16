package net.badgersmc.ek.infrastructure.bukkit

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LeaderboardPageTest {
    private val wins = (1..23).associate { "solo:p$it" to it } + mapOf("guild:g1" to 4, "guild:g2" to 9, "solo:zero" to 0)

    @Test fun `players are ranked by wins, ten to a page, with guilds kept apart`() {
        val first = LeaderboardPage.of(wins, LeaderboardSide.PLAYERS, 1, null) { it.substringAfter(':') }
        assertEquals(3, first.pages)
        assertEquals(listOf("p23", "p22"), first.rows.take(2).map { it.name })
        assertEquals(10, first.rows.size)
        val last = LeaderboardPage.of(wins, LeaderboardSide.PLAYERS, 9, null) { it }
        assertEquals(3, last.page)
        assertEquals(3, last.rows.size)
        assertTrue(last.rows.none { it.name.contains("zero") }, "players without wins are not ranked")
        val guilds = LeaderboardPage.of(wins, LeaderboardSide.GUILDS, 1, null) { it.substringAfter(':') }
        assertEquals(listOf("g2", "g1"), guilds.rows.map { it.name })
    }

    @Test fun `the viewer sees their own rank even off the page`() {
        val page = LeaderboardPage.of(wins, LeaderboardSide.PLAYERS, 1, "solo:p3") { it }
        assertEquals(21, page.viewer?.rank)
        assertTrue(page.rows.none { it.viewer })
        assertTrue(LeaderboardPage.of(wins, LeaderboardSide.PLAYERS, 3, "solo:p3") { it }.rows.single { it.viewer }.rank == 21)
        assertNull(LeaderboardPage.of(emptyMap(), LeaderboardSide.PLAYERS, 1, "solo:p3") { it }.viewer)
        assertEquals(1, LeaderboardPage.of(emptyMap(), LeaderboardSide.PLAYERS, 1, null) { it }.pages)
    }
}
