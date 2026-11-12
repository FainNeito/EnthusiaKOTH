package net.badgersmc.ek.infrastructure.bukkit

import io.mockk.every
import io.mockk.mockk
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CrownTabCompleteTest {
    private val finals = mapOf(
        "binding" to mapOf("arena:capture:0:2026-11-12:20:38:1794533880000" to "test-v1"),
        "helmet-loss" to mapOf("arena:capture:0:2026-11-13:20:38:1794620280000" to "quest-v2"),
    )
    private val command = KothCommand(mockk(relaxed = true), { mockk(relaxed = true) }, mockk(relaxed = true), mockk(relaxed = true),
        mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true),
        { emptyMap() }, {}, {}, { _, _, _ -> "" }, { _, _ -> "" }, { _, _ -> "" }, { emptyList() },
        openCrownFinals = { finals[it].orEmpty() })
    private val player = mockk<Player>(relaxed = true).also { every { it.hasPermission(any<String>()) } returns true }
    private fun complete(vararg args: String) = command.onTabComplete(player, mockk<Command>(), "ekoth", arrayOf(*args))

    @Test fun `crown completes the accept command, an open final and its revision`() {
        assertTrue("crown" in complete("cr"))
        assertEquals(listOf("accept-binding", "accept-helmet-loss"), complete("crown", ""))
        assertEquals(listOf("arena:capture:0:2026-11-12:20:38:1794533880000"), complete("crown", "accept-binding", ""))
        assertEquals(listOf("test-v1"), complete("crown", "accept-binding", "arena:capture:0:2026-11-12:20:38:1794533880000", ""))
        assertEquals(listOf("quest-v2"), complete("crown", "accept-helmet-loss", "arena:capture:0:2026-11-13:20:38:1794620280000", ""))
        assertTrue(complete("crown", "accept-binding", "unknown-final", "").isEmpty())
    }

    @Test fun `the console is not offered crown acceptance`() {
        assertNull(command.crownTabOptions(mockk<CommandSender>(relaxed = true), arrayOf("crown", "")))
    }
}
