package net.badgersmc.ek.infrastructure.bukkit

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.util.UUID
import net.badgersmc.ek.application.ProgressionClaim
import net.badgersmc.ek.infrastructure.persistence.SqlProgressionStore
import org.bukkit.plugin.java.JavaPlugin
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LegacyCrownClaimGuardTest {
    @Test fun `historical Crown claim is held before provider dispatch or state transition`() {
        val player=UUID.randomUUID(); val id="challenge:sovereign_crown:$player:relic"
        val store=mockk<SqlProgressionStore>(relaxed=true)
        every { store.claims(player) } returns listOf(ProgressionClaim(id,player,"LORE","crown",1,"PENDING",""))
        val claims=ProgressionClaims(mockk<JavaPlugin>(),store,mockk())
        assertTrue(claims.redeem(player,id).contains("held"))
        verify(exactly=0) { store.transition(any(),any(),any(),any(),any()) }
    }
}
