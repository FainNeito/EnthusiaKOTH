package net.badgersmc.ek.infrastructure.bukkit

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.badgersmc.ek.domain.KothArena
import org.bukkit.command.Command
import org.bukkit.entity.Player
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class KothCommandTabCompleteTest {
    private val progression = mockk<ProgressionController>().also {
        every { it.heldMatchIds() } returns listOf("00c6af0e-7e7e-4d70-bbd9-0b0aa445a830")
        every { it.recentMatchIds() } returns listOf("4e895631-5622-45ac-b54a-7a306f0606df")
        every { it.reviewClaimIds() } returns listOf("claim-1")
    }
    private val command = KothCommand(mockk(relaxed = true), { mockk(relaxed = true) }, mockk(relaxed = true), mockk(relaxed = true),
        mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true),
        { mapOf("moving" to mockk<KothArena>(), "capture" to mockk<KothArena>()) }, {}, {}, { _, _, _ -> "" }, { _, _ -> "" }, { _, _ -> "" },
        { listOf("koth_capture", "spawn") }, progression = progression)
    private val bukkit = mockk<Command>()

    private fun player(admin: Boolean = false, starter: Boolean = false) = mockk<Player>(relaxed = true).also {
        every { it.hasPermission(any<String>()) } returns false
        every { it.hasPermission("enthusiakoth.admin") } returns admin
        every { it.hasPermission("enthusiakoth.start.basic") } returns starter
        every { it.world.name } returns "world"
    }
    private fun complete(sender: Player, vararg args: String) = command.onTabComplete(sender, bukkit, "ekoth", arrayOf(*args))

    @Test fun `players are offered only the commands they can use, one spelling each`() {
        val options = complete(player(), "")
        assertTrue(options.containsAll(listOf("gui", "schedule", "top", "stats", "notifications", "help")))
        assertFalse(options.any { it in setOf("setup", "start", "stop", "cancel", "editor", "test", "messages", "reviewmatch") })
        assertTrue(complete(player(starter = true), "st").containsAll(listOf("start", "stats")))
        assertTrue("setup" in complete(player(admin = true), "se"))
    }

    @Test fun `each argument suggests what comes next`() {
        assertEquals(listOf("capture", "moving"), complete(player(starter = true), "start", ""))
        // A basic starter is not offered the advanced tier they cannot use.
        assertEquals(listOf("solo", "guild", "basic"), complete(player(starter = true), "start", "capture", ""))
        assertEquals(listOf("basic"), complete(player(starter = true), "start", "capture", "solo", ""))
        assertEquals(listOf("solo", "guild", "basic", "advanced"), complete(player(admin = true), "start", "capture", ""))
        assertEquals(listOf("solo", "guild"), complete(player(starter = true), "start", "capture", "basic", ""))
        assertEquals(listOf("daily", "weekly", "season", "lifetime"), complete(player(), "top", ""))
        assertEquals(listOf("capture", "moving", "cancel"), complete(player(admin = true), "setup", ""))
        assertEquals(listOf("spawn"), complete(player(admin = true), "arena", "region", "capture", "sp"))
    }

    @Test fun `reply answers the prompt that opened last`() {
        val setup = mockk<ArenaSetupController>(relaxed = true)
        val settings = mockk<StaffSettingsController>(relaxed = true)
        val routed = KothCommand(mockk(relaxed = true), { mockk(relaxed = true) }, mockk(relaxed = true), mockk(relaxed = true),
            mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true),
            { emptyMap() }, {}, {}, { _, _, _ -> "" }, { _, _ -> "" }, { _, _ -> "" }, { emptyList() }, setup = setup, settings = settings)
        val staff = player(admin = true)
        every { setup.promptOpenedAt(any()) } returns 1L
        every { settings.promptOpenedAt(any()) } returns 2L
        every { settings.answer(staff, "summit") } returns true
        routed.onCommand(staff, bukkit, "ekoth", arrayOf("reply", "summit"))
        verify(exactly = 1) { settings.answer(staff, "summit") }
        verify(exactly = 0) { setup.answer(any(), any()) }
    }

    @Test fun `review commands complete held matches and their decisions for staff only`() {
        val admin = player(admin = true)
        assertEquals(listOf("00c6af0e-7e7e-4d70-bbd9-0b0aa445a830"), complete(admin, "reviewmatch", ""))
        assertEquals(listOf("approve", "reject"), complete(admin, "reviewmatch", "00c6af0e-7e7e-4d70-bbd9-0b0aa445a830", ""))
        assertEquals(listOf("claim-1"), complete(admin, "reconcile", ""))
        assertEquals(listOf("paid", "retry"), complete(admin, "reconcile", "claim-1", ""))
        assertTrue(complete(player(), "reviewmatch", "").isEmpty())
        assertTrue(complete(player(), "reviewmatch", "x", "").isEmpty())
    }
}
