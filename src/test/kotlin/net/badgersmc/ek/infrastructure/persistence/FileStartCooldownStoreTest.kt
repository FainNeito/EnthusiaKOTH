package net.badgersmc.ek.infrastructure.persistence

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.Instant
import java.util.UUID

class FileStartCooldownStoreTest {
    @TempDir lateinit var temp: File
    @Test fun `cooldown survives restart and released reservation stays released`() {
        val file = File(temp, "cooldowns.dat")
        val id = UUID.randomUUID()
        val time = Instant.parse("2026-10-08T16:00:00Z")
        assertTrue(FileStartCooldownStore(file) { _, _ -> }.set(id, time))
        val restart = FileStartCooldownStore(file) { _, _ -> }
        assertEquals(time, restart.until(id))
        assertTrue(restart.set(id, null))
        assertNull(FileStartCooldownStore(file) { _, _ -> }.until(id))
    }
    @Test fun `corrupt state fails closed without rewriting evidence`() {
        val file = File(temp, "cooldowns.dat").apply { writeText("corrupt") }
        val store = FileStartCooldownStore(file) { _, _ -> }
        assertThrows(IllegalStateException::class.java) { store.until(UUID.randomUUID()) }
        assertFalse(store.set(UUID.randomUUID(), Instant.now()))
        assertEquals("corrupt", file.readText())
    }
    @Test fun `failed write does not mutate in-memory cooldown`() {
        val blocker = File(temp, "not-a-directory").apply { writeText("block") }
        val store = FileStartCooldownStore(File(blocker, "cooldowns.dat")) { _, _ -> }
        val player = UUID.randomUUID()
        assertFalse(store.set(player, Instant.now()))
        assertNull(store.until(player))
    }
}
