package net.badgersmc.ek.application

import net.badgersmc.ek.config.ArenaConfig
import net.badgersmc.ek.domain.ArenaName
import net.badgersmc.ek.infrastructure.persistence.FileArenaSetupStore
import org.bukkit.configuration.file.YamlConfiguration
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class ArenaNamePolicyTest {
    @TempDir lateinit var directory: Path
    private fun service() = ArenaSetupService(object: ArenaSetupStore {
        override fun read() = error("Unused")
        override fun write(id: String, arena: ArenaConfig, expectedRevision: String) = error("Unused")
    }, { false }, { true }, { _, _ -> true })

    @Test fun `missing blank and unicode readable names resolve predictably`() {
        assertEquals("capture", ArenaName.resolve("capture", null))
        assertEquals("capture", ArenaName.resolve("capture", "  "))
        assertEquals("Crête & Summit", ArenaName.resolve("capture", "  Crête & Summit  "))
        assertEquals(64, ArenaName.parse("A".repeat(64))!!.length)
    }
    @Test fun `invalid input leaves the existing draft intact`() {
        val draft = ArenaSetupDraft("capture", "revision", false, ArenaConfig(displayName = "Summit"), true)
        val before = draft.arena
        for (raw in listOf("A".repeat(65), "two\nlines", "name\t", "<red>Hill", "&cHill", "§cHill", "hidden\u200Bname")) {
            val error = assertThrows(SetupException::class.java) { service().setName(draft, raw) }
            assertEquals(SetupIssue.NAME, error.issue)
            assertEquals(before, draft.arena)
        }
    }
    @Test fun `changing and clearing names leaves identity rules and rewards intact`() {
        val draft = ArenaSetupDraft("capture", "revision", false, ArenaConfig(rewards = listOf("reward {KOTH}"), schedule = listOf("18:00")), true)
        val before = draft.arena
        service().setName(draft, " Crimson Summit ")
        assertEquals(before.copy(displayName = "Crimson Summit"), draft.arena)
        assertEquals("capture", draft.id)
        service().setName(draft, null)
        assertEquals(before, draft.arena)
    }
    @Test fun `atomic name save and clear preserve YAML identity and unrelated keys`() {
        val file = directory.resolve("config.yml").toFile()
        file.writeText("""arenas:
  capture:
    display-name: Summit
    schedule: ['18:00']
    rewards: ['reward {KOTH}']
    custom-key: untouched
  score:
    display-name: Summit
""")
        val store = FileArenaSetupStore(file)
        val original = store.read()
        store.write("capture", original.arenas.getValue("capture").copy(displayName = "Crimson Summit"), original.revision)
        val renamed = store.read()
        assertEquals(setOf("capture", "score"), renamed.arenas.keys)
        assertEquals("Crimson Summit", renamed.arenas.getValue("capture").displayName)
        assertEquals("Summit", renamed.arenas.getValue("score").displayName)
        assertEquals(listOf("18:00"), renamed.arenas.getValue("capture").schedule)
        assertEquals(listOf("reward {KOTH}"), renamed.arenas.getValue("capture").rewards)
        store.write("capture", renamed.arenas.getValue("capture").copy(displayName = null), renamed.revision)
        val yaml = YamlConfiguration.loadConfiguration(file)
        assertFalse(yaml.contains("arenas.capture.display-name"))
        assertEquals("untouched", yaml.getString("arenas.capture.custom-key"))
        assertEquals("capture", ArenaName.resolve("capture", store.read().arenas.getValue("capture").displayName))
    }
    @Test fun `invalid configured names fail explicitly rather than allowing formatting`() {
        val yaml = YamlConfiguration().apply { set("arenas.capture.display-name", "<click:run_command:/op>Summit") }
        assertThrows(IllegalArgumentException::class.java) { net.badgersmc.ek.infrastructure.bukkit.ArenaConfigLoader.load(yaml) }
    }
}
