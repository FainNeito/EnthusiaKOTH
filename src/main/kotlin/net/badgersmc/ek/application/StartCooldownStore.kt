package net.badgersmc.ek.application

import java.time.Instant
import java.util.UUID

interface StartCooldownStore {
    fun until(player: UUID): Instant?
    fun set(player: UUID, until: Instant?): Boolean
}

class InMemoryStartCooldownStore : StartCooldownStore {
    private val values = mutableMapOf<UUID, Instant>()
    override fun until(player: UUID): Instant? = values[player]
    override fun set(player: UUID, until: Instant?): Boolean {
        if (until == null) values.remove(player) else values[player] = until
        return true
    }
}
