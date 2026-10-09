package net.badgersmc.ek.infrastructure.bukkit

import io.mockk.*
import net.badgersmc.ek.application.KothService
import net.badgersmc.ek.config.EnthusiaKothConfig
import org.bukkit.entity.Player
import org.bukkit.entity.Projectile
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.junit.jupiter.api.Test

class ContestDamageListenerTest {
    @Test fun `cancelled zero negative or nonfinite damage cannot create contest evidence`() {
        val service=mockk<KothService>(relaxed=true)
        val listener=KothListeners({EnthusiaKothConfig()},service,mockk(),{emptyMap()},mockk(),mockk())
        for ((cancelled,damage) in listOf(true to 2.0,false to 0.0,false to -1.0,false to Double.NaN,false to Double.POSITIVE_INFINITY)) {
            val event=mockk<EntityDamageByEntityEvent>()
            every { event.isCancelled } returns cancelled; every { event.finalDamage } returns damage
            listener.onContestDamage(event)
        }
        verify(exactly=0) { service.recordCombat(any(),any()) }
    }
    @Test fun `effective projectile damage records its player shooter and victim`() {
        val service=mockk<KothService>(relaxed=true)
        val listener=KothListeners({EnthusiaKothConfig()},service,mockk(),{emptyMap()},mockk(),mockk())
        val shooter=mockk<Player>(); val victim=mockk<Player>(); val projectile=mockk<Projectile>()
        every { projectile.shooter } returns shooter
        val event=mockk<EntityDamageByEntityEvent>()
        every { event.isCancelled } returns false; every { event.finalDamage } returns 2.0
        every { event.entity } returns victim; every { event.damager } returns projectile
        listener.onContestDamage(event)
        verify(exactly=1) { service.recordCombat(shooter,victim) }
    }
}
