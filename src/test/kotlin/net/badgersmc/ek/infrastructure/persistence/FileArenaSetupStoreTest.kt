package net.badgersmc.ek.infrastructure.persistence

import net.badgersmc.ek.application.*
import net.badgersmc.ek.config.*
import org.bukkit.configuration.file.YamlConfiguration
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class FileArenaSetupStoreTest {
    @TempDir lateinit var directory: File
    private fun file() = File(directory, "config.yml").apply { writeText("arenas:\n  hill:\n    rewards: [bank 500]\n    custom-key: retained\n    reward-family: custom\n    schedule: ['12:00']\nnotifications:\n  capture-regions: [spawn, warzone, market]\n") }
    @Test fun `edit survives disk reload and preserves unknown rules rewards schedules and audience`() {
        val file = file(); val store = FileArenaSetupStore(file); val snapshot = store.read()
        store.write("hill", snapshot.arenas.getValue("hill").copy(radius = 8.0, keepInventory = true), snapshot.revision)
        assertEquals(8.0, FileArenaSetupStore(file).read().arenas.getValue("hill").radius)
        val yaml = YamlConfiguration.loadConfiguration(file)
        assertEquals("retained", yaml.getString("arenas.hill.custom-key"))
        assertEquals(listOf("bank 500"), yaml.getStringList("arenas.hill.rewards"))
        assertEquals(listOf("12:00"), yaml.getStringList("arenas.hill.schedule"))
        assertEquals("custom", yaml.getString("arenas.hill.reward-family"))
        assertEquals(listOf("spawn", "warzone", "market"), yaml.getStringList("notifications.capture-regions"))
    }
    @Test fun `failed atomic replacement preserves original and removes temporary file`() {
        val file = file(); val before = file.readText()
        val store = FileArenaSetupStore(file) { _, _ -> throw java.io.IOException("disk failure") }; val snapshot = store.read()
        assertEquals(SetupIssue.IO, assertThrows(SetupException::class.java) { store.write("hill", ArenaConfig(), snapshot.revision) }.issue)
        assertEquals(before, file.readText()); assertEquals(listOf("config.yml"), directory.list()!!.toList())
    }
    @Test fun `external edit refuses overwrite`() {
        val file = file(); val store = FileArenaSetupStore(file); val snapshot = store.read()
        file.appendText("external: true\n")
        assertEquals(SetupIssue.STALE, assertThrows(SetupException::class.java) { store.write("hill", ArenaConfig(), snapshot.revision) }.issue)
        assertTrue(file.readText().contains("external: true"))
    }
    @Test fun `new arena cannot inherit paid family defaults`() {
        val file = file(); val store = FileArenaSetupStore(file); val snapshot = store.read()
        store.write("new", ArenaConfig(enabled = false, rewardFamily = "setup_unique"), snapshot.revision)
        val yaml = YamlConfiguration.loadConfiguration(file)
        assertFalse(yaml.getBoolean("arenas.new.enabled"))
        assertEquals(0, yaml.getInt("rewards.setup_unique.guild-vault-money"))
        assertTrue(yaml.getStringList("arenas.new.rewards").isEmpty())
    }
    @Test fun `malformed YAML fails closed without replacing file`() {
        val file = file(); file.writeText("arenas: [broken")
        assertEquals(SetupIssue.IO, assertThrows(SetupException::class.java) { FileArenaSetupStore(file).read() }.issue)
        assertEquals("arenas: [broken", file.readText())
    }
}
