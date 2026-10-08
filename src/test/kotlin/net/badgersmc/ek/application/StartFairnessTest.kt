package net.badgersmc.ek.application

import io.mockk.*
import net.badgersmc.ek.config.EnthusiaKothConfig
import net.badgersmc.ek.config.FairnessConfig
import net.badgersmc.ek.config.ManualStartConfig
import net.badgersmc.ek.domain.KothArena
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class StartFairnessTest {
    private val now = Instant.parse("2026-10-08T16:00:00Z")
    private val player = UUID.randomUUID()
    private val arena = mockk<KothArena>(relaxed = true)
    private val economy = mockk<PlayerEconomy>(relaxed = true).also {
        every { it.isAvailable() } returns true
        every { it.balance(any()) } returns 100.0
        every { it.withdraw(any(), any()) } returns true
        every { it.deposit(any(), any()) } returns true
    }
    private fun service(store: StartCooldownStore = InMemoryStartCooldownStore(), teams: Int = 2, success: Boolean = true, at: Instant = now, conflict: Boolean = false) = StartService(
        config = { EnthusiaKothConfig(manualStart = ManualStartConfig(basicCost = 5.0), fairness = FairnessConfig(starterCooldownSeconds = 60, minimumOnlineTeams = 2)) },
        pluginReady = { true }, hasConflictingEvent = { conflict }, economy = economy,
        starter = EventStarter { _, _, _, _ -> success }, logError = { _, _ -> },
        clock = Clock.fixed(at, ZoneOffset.UTC), cooldowns = store, onlineTeamCount = { teams },
    )
    private fun request(source: StartSource = StartSource.PLAYER_COMMAND) = StartRequest(
        StartActor(player, canStartBasic = true, canUseFlare = true), arena, source,
    )
    @Test fun `cooldown spans GUI and flare and allows exact expiry`() {
        val store = InMemoryStartCooldownStore()
        val first = service(store)
        assertInstanceOf(StartResult.Started::class.java, first.start(request(StartSource.GUI)))
        assertEquals(StartFailure.STARTER_COOLDOWN, (first.start(request(StartSource.FLARE)) as StartResult.Rejected).failure)
        assertInstanceOf(StartResult.Started::class.java, service(store, at = now.plusSeconds(60)).start(request()))
        verify(exactly = 2) { economy.withdraw(player, 5.0) }
    }
    @Test fun `insufficient teams rejects before payment and failed starts release cooldown`() {
        val store = InMemoryStartCooldownStore()
        assertEquals(StartFailure.INSUFFICIENT_TEAMS, (service(store, teams = 1).start(request()) as StartResult.Rejected).failure)
        verify(exactly = 0) { economy.withdraw(any(), any()) }
        assertEquals(StartFailure.START_FAILED, (service(store, success = false).start(request()) as StartResult.Rejected).failure)
        assertNull(store.until(player))
        verify(exactly = 1) { economy.deposit(player, 5.0) }
        assertInstanceOf(StartResult.Started::class.java, service(store).start(request()))
    }
    @Test fun `durable reservation failure rejects before charging`() {
        val store = mockk<StartCooldownStore>()
        every { store.until(player) } returns null
        every { store.set(any(), any()) } returns false
        assertEquals(StartFailure.COOLDOWN_STATE_FAILED, (service(store).start(request()) as StartResult.Rejected).failure)
        verify(exactly = 0) { economy.withdraw(any(), any()) }
    }
    @Test fun `administrative start bypasses player gates`() {
        assertInstanceOf(StartResult.Started::class.java, service(teams = 0).start(
            StartRequest(StartActor(player, isAdmin = true), arena, StartSource.ADMIN_COMMAND)))
        verify(exactly = 0) { economy.withdraw(any(), any()) }
    }
    @Test fun `busy event does not touch cooldown state even when releasing it would fail`() {
        val store = mockk<StartCooldownStore>(relaxed = true)
        every { store.until(player) } returns null
        every { store.set(player, any()) } returnsMany listOf(true, false)
        val result = service(store, conflict = true).start(request()) as StartResult.Rejected
        assertEquals(StartFailure.ALREADY_ACTIVE, result.failure)
        verify { store wasNot Called }
        verify(exactly = 0) { economy.withdraw(any(), any()) }
    }
    @Test fun `permission rejection does not touch cooldown state`() {
        val store = mockk<StartCooldownStore>(relaxed = true)
        every { store.set(any(), any()) } returns true
        listOf(StartSource.PLAYER_COMMAND, StartSource.GUI, StartSource.FLARE).forEach { source ->
            val denied = request(source).copy(actor = StartActor(player))
            assertEquals(StartFailure.NO_PERMISSION, (service(store).start(denied) as StartResult.Rejected).failure)
        }
        verify { store wasNot Called }
        verify(exactly = 0) { economy.withdraw(any(), any()) }
    }
    @Test fun `throwing cooldown persistence is controlled without payment`() {
        val store = mockk<StartCooldownStore>()
        every { store.until(player) } returns null
        every { store.set(any(), any()) } throws IllegalStateException("storage failed")
        assertEquals(StartFailure.COOLDOWN_STATE_FAILED, (service(store).start(request()) as StartResult.Rejected).failure)
        verify(exactly = 0) { economy.withdraw(any(), any()) }
    }
}
