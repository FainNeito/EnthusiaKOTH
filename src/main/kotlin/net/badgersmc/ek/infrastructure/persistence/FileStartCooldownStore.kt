package net.badgersmc.ek.infrastructure.persistence

import net.badgersmc.ek.application.StartCooldownStore
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.util.UUID

/** Malformed/unreadable state fails closed; it must not silently reset limits. */
class FileStartCooldownStore(private val file: File, private val logger: (String, Throwable) -> Unit) : StartCooldownStore {
    private val values = mutableMapOf<UUID, Instant>()
    private var healthy = true
    init {
        try {
            if (file.exists()) {
                val loaded = file.readLines().filter { it.isNotBlank() }.associate { line ->
                    val parts = line.split('|')
                    require(parts.size == 2)
                    UUID.fromString(parts[0]) to Instant.parse(parts[1])
                }
                values.putAll(loaded)
            }
        } catch (error: Exception) {
            healthy = false
            logger("Cannot read KOTH starter cooldowns; configured cooldown starts are blocked", error)
        }
    }
    @Synchronized override fun until(player: UUID): Instant? {
        check(healthy) { "KOTH cooldown state requires operator repair" }
        return values[player]
    }
    @Synchronized override fun set(player: UUID, until: Instant?): Boolean {
        if (!healthy) return false
        val next = values.toMutableMap()
        if (until == null) next.remove(player) else next[player] = until
        return try {
            file.parentFile?.mkdirs()
            val temp = File(file.parentFile, "${file.name}.tmp")
            temp.writeText(next.entries.joinToString("\n") { "${it.key}|${it.value}" })
            try {
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            values.clear()
            values.putAll(next)
            true
        } catch (error: Exception) {
            logger("Cannot persist KOTH starter cooldown; start reservation rejected", error)
            false
        }
    }
}
