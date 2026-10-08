package net.badgersmc.ek.infrastructure.bukkit

import io.mockk.*
import org.bukkit.NamespacedKey
import org.bukkit.entity.Player
import org.bukkit.persistence.PersistentDataContainer
import org.bukkit.persistence.PersistentDataType
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PlayerNotificationPreferencesTest {
    @Test fun `opt out is stored on player data and survives adapter recreation`() {
        val key = NamespacedKey("enthusiakoth", "notifications-disabled")
        val data = mockk<PersistentDataContainer>()
        val player = mockk<Player>()
        var saved: Byte? = null
        every { player.persistentDataContainer } returns data
        every { data.get(key, PersistentDataType.BYTE) } answers { saved }
        every { data.set(key, PersistentDataType.BYTE, any()) } answers { saved = thirdArg(); Unit }
        every { data.remove(key) } answers { saved = null; Unit }
        val first = PlayerNotificationPreferences(key)
        assertTrue(first.enabled(player))
        assertFalse(first.toggle(player))
        val restart = PlayerNotificationPreferences(key)
        assertFalse(restart.enabled(player))
        assertTrue(restart.toggle(player))
        assertNull(saved)
    }
}
