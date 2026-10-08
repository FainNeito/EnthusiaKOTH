package net.badgersmc.ek.infrastructure.persistence

import net.badgersmc.ek.application.*
import net.badgersmc.ek.config.ArenaConfig
import net.badgersmc.ek.infrastructure.bukkit.ArenaConfigLoader
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** Preserve unknown keys; commit one complete YAML replacement, never a partial save. */
class FileArenaSetupStore(
    private val file: File,
    private val replace: (File, File) -> Unit = { temporary, target ->
        Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    },
) : ArenaSetupStore {
    private fun revision(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun yaml(bytes: ByteArray) = YamlConfiguration().apply { loadFromString(bytes.toString(Charsets.UTF_8)) }

    override fun read(): ArenaSetupSnapshot = try {
        val bytes = file.readBytes()
        ArenaSetupSnapshot(revision(bytes), ArenaConfigLoader.load(yaml(bytes)))
    } catch (error: Exception) { throw SetupException(SetupIssue.IO).apply { initCause(error) } }

    override fun write(id: String, arena: ArenaConfig, expectedRevision: String) {
        var temporary: File? = null
        try {
            val bytes = file.readBytes()
            if (revision(bytes) != expectedRevision) throw SetupException(SetupIssue.STALE)
            val config = yaml(bytes)
            val creating = !config.contains("arenas.$id")
            val root = "arenas.$id"
            fun put(key: String, value: Any?) = config.set("$root.$key", value)
            put("enabled", arena.enabled); put("family", arena.family); put("world", arena.world)
            put("worldguard-region", arena.worldGuardRegion ?: "")
            put("center.x", arena.center.x); put("center.y", arena.center.y); put("center.z", arena.center.z)
            put("protected-region.corner-1.x", arena.protectedRegion.corner1.x)
            put("protected-region.corner-1.y", arena.protectedRegion.corner1.y)
            put("protected-region.corner-1.z", arena.protectedRegion.corner1.z)
            put("protected-region.corner-2.x", arena.protectedRegion.corner2.x)
            put("protected-region.corner-2.y", arena.protectedRegion.corner2.y)
            put("protected-region.corner-2.z", arena.protectedRegion.corner2.z)
            put("radius", arena.radius); put("duration-seconds", arena.durationSeconds)
            put("capture-seconds", arena.captureSeconds); put("leave-behavior", arena.leaveBehavior)
            put("keep-inventory", arena.keepInventory); put("keep-experience", arena.keepExperience)
            if (creating) {
                put("reward-family", arena.rewardFamily)
                put("schedule", emptyList<String>()); put("rewards", emptyList<String>())
                put("chanced-rewards", emptyMap<String, String>())
                config.set("rewards.${arena.rewardFamily}.solo-vault-money", 0)
                config.set("rewards.${arena.rewardFamily}.guild-vault-money", 0)
            }
            temporary = Files.createTempFile(file.parentFile.toPath(), ".arena-setup-", ".tmp").toFile()
            temporary.writeText(config.saveToString(), Charsets.UTF_8)
            // Recheck after serialization to reject external edits while preparing the replacement.
            if (revision(file.readBytes()) != expectedRevision) throw SetupException(SetupIssue.STALE)
            replace(temporary, file)
        } catch (error: SetupException) { throw error
        } catch (error: Exception) { throw SetupException(SetupIssue.IO).apply { initCause(error) }
        } finally { temporary?.delete() }
    }
}
