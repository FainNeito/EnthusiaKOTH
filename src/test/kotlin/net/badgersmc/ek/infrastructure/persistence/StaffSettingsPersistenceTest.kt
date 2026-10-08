package net.badgersmc.ek.infrastructure.persistence

import net.badgersmc.ek.application.*
import net.badgersmc.ek.infrastructure.bukkit.ArenaConfigLoader
import org.bukkit.configuration.file.YamlConfiguration
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.io.TempDir
import org.sqlite.SQLiteDataSource
import java.io.File
import java.time.Instant
import java.util.UUID

class StaffSettingsPersistenceTest {
    @TempDir lateinit var directory: File
    private fun file() = File(directory, "config.yml").apply { writeText("arenas:\n  hill:\n    family: capture\n    reward-family: shared\n    custom: untouched\n  other:\n    family: capture\n    reward-family: shared\nrewards:\n  shared:\n    guild-vault-money: 42\nnotifications:\n  capture-regions: [spawn, warzone, market]\n") }
    @Test fun `atomic Save preserves other arena shared money unknown keys and audience`() {
        val file = file(); val store = FileStaffSettingsStore(file); val snapshot = store.read()
        store.write(mapOf("arenas.hill.reward-family" to "editor_abcd", "rewards.editor_abcd.guild-vault-money" to 1.0, "arenas.hill.schedule" to listOf("18:00")), snapshot.revision)
        val yaml = YamlConfiguration.loadConfiguration(file)
        assertEquals(42.0, yaml.getDouble("rewards.shared.guild-vault-money"))
        assertEquals("shared", yaml.getString("arenas.other.reward-family")); assertEquals("untouched", yaml.getString("arenas.hill.custom"))
        assertEquals(listOf("spawn", "warzone", "market"), yaml.getStringList("notifications.capture-regions"))
        assertEquals(listOf("18:00"), ArenaConfigLoader.load(yaml).getValue("hill").schedule)
    }
    @Test fun `stale Save and failed rename preserve original bytes`() {
        val file = file(); val store = FileStaffSettingsStore(file); val snapshot = store.read()
        file.appendText("custom-root: kept\n"); val before = file.readText()
        assertThrows(IllegalStateException::class.java) { store.write(mapOf("schedule.enabled" to true), snapshot.revision) }
        assertEquals(before, file.readText())
        val bad = FileStaffSettingsStore(file) { _, _ -> throw java.io.IOException("disk") }
        assertThrows(java.io.IOException::class.java) { bad.write(mapOf("schedule.enabled" to true), bad.read().revision) }
        assertEquals(before, file.readText()); assertEquals(1, directory.listFiles()!!.size)
    }
    @Test fun `chance list roundtrips identical probabilities and decimal money`() {
        val file = file(); val store = FileStaffSettingsStore(file)
        val commands = listOf(mapOf("command" to "say a", "chance" to 25.0), mapOf("command" to "say b", "chance" to 25.0))
        store.write(mapOf("arenas.hill.chanced-rewards" to commands), store.read().revision)
        assertEquals(mapOf("say a" to 25.0, "say b" to 25.0), ArenaConfigLoader.load(YamlConfiguration.loadConfiguration(file)).getValue("hill").chancedRewards)
    }
    @Test fun `malformed YAML read never falls back to empty configuration`() {
        val file = file(); file.writeText("arenas: [invalid\n")
        assertThrows(Exception::class.java) { FileStaffSettingsStore(file).read() }
    }
    @Test fun `timestamped wins survive restart deduplicate and do not invent legacy timestamps`() {
        val ds = SQLiteDataSource().apply { url = "jdbc:sqlite:${File(directory, "stats.db")}" }
        val repo = SqlStatsRepository(ds); repo.init(); val id = UUID.randomUUID(); val at = Instant.parse("2026-10-08T12:00:00Z")
        repo.incrementWin("solo:old", "hill")
        repo.recordTimedWin(id, "guild:new", "hill", at); repo.recordTimedWin(id, "guild:new", "hill", at)
        val window = LeaderboardWindow(at, at.plusSeconds(1))
        assertEquals(mapOf("guild:new" to 1), repo.winsIn(window)); repo.shutdown()
        val restarted = SqlStatsRepository(ds); restarted.init()
        try {
            assertEquals(mapOf("guild:new" to 1), restarted.winsIn(window)); assertEquals(1, restarted.totalWins("solo:old"))
            assertTrue(restarted.winsIn(LeaderboardWindow(at.plusSeconds(1), at.plusSeconds(2))).isEmpty())
        } finally { restarted.shutdown() }
    }
}
