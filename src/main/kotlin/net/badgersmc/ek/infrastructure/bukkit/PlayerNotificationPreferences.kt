package net.badgersmc.ek.infrastructure.bukkit

import org.bukkit.NamespacedKey
import org.bukkit.entity.Player
import org.bukkit.persistence.PersistentDataType

class PlayerNotificationPreferences(private val key: NamespacedKey) {
    fun enabled(player: Player): Boolean = player.persistentDataContainer.get(key, PersistentDataType.BYTE) != 1.toByte()
    fun toggle(player: Player): Boolean {
        val enabled = !enabled(player)
        if (enabled) player.persistentDataContainer.remove(key)
        else player.persistentDataContainer.set(key, PersistentDataType.BYTE, 1.toByte())
        return enabled
    }
}
