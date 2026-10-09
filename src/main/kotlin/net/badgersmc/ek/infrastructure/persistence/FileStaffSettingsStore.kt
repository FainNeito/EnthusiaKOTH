package net.badgersmc.ek.infrastructure.persistence

import net.badgersmc.ek.application.StaffSettingsSnapshot
import net.badgersmc.ek.application.StaffSettingsStore
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

class FileStaffSettingsStore(private val file: File,
    private val replace: (File, File) -> Unit = { a, b -> Files.move(a.toPath(), b.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); Unit },
) : StaffSettingsStore {
    private fun revision(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun yaml(bytes: ByteArray) = YamlConfiguration().apply { loadFromString(bytes.toString(Charsets.UTF_8)) }
    override fun read(): StaffSettingsSnapshot {
        val bytes = file.readBytes(); val config = yaml(bytes)
        return StaffSettingsSnapshot(revision(bytes), config.getValues(true).filterValues { it !is org.bukkit.configuration.ConfigurationSection })
    }
    override fun write(changes: Map<String, Any?>, revision: String) {
        val bytes = file.readBytes(); check(revision(bytes) == revision) { "stale" }
        val config = yaml(bytes); changes.forEach(config::set)
        val temporary = Files.createTempFile(file.parentFile.toPath(), ".staff-settings-", ".tmp").toFile()
        try {
            temporary.writeText(config.saveToString(), Charsets.UTF_8)
            check(revision(file.readBytes()) == revision) { "stale" }
            replace(temporary, file)
        } finally { temporary.delete() }
    }
}
