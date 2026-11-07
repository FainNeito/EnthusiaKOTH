package net.badgersmc.ek.infrastructure.persistence

import io.mockk.mockk
import io.mockk.verify
import java.io.File
import java.time.Instant
import java.util.UUID
import net.badgersmc.ek.application.*
import net.badgersmc.ek.infrastructure.bukkit.LegacyLoreCrownCustody
import net.enthusia.loreitems.api.v1.LoreItemsServiceV1
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.sqlite.SQLiteDataSource

class CrownRecoveryIntegrationTest {
    @Test fun `only newer same-instance custody archives an old recovery hold`() {
        val ds=source();val holds=SqlCrownRecoveryHold(ds).also{it.init()}
        val request=RecoveryRequest(java.util.UUID.randomUUID(),java.util.UUID.randomUUID(),java.util.UUID.randomUUID(),2,java.time.Instant.now())
        assertEquals(RecoveryReservation.CREATED,holds.reserve(request))
        holds.observeSuccessor(CrownTenure(java.util.UUID.randomUUID(),request.holderId,3));assertNotNull(holds.read())
        holds.observeSuccessor(CrownTenure(request.instanceId,request.holderId,2));assertNotNull(holds.read())
        holds.observeSuccessor(CrownTenure(request.instanceId,java.util.UUID.randomUUID(),3));assertNull(holds.read())
        ds.connection.use { c -> c.createStatement().use { s -> s.executeQuery("SELECT COUNT(*) FROM koth_crown_prior_recovery_holds").use { r ->r.next();assertEquals(1,r.getInt(1))} } }
    }
    @TempDir lateinit var directory: File
    private fun source() = SQLiteDataSource().apply { url = "jdbc:sqlite:${File(directory, "crown-recovery.db")}" }
    private val request = RecoveryRequest(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1, Instant.parse("2026-11-04T21:00:00Z"))

    @Test fun `native reservation remains held and reuses the same identity after restart`() {
        val ds = source()
        val store = SqlCrownRecoveryHold(ds).also { it.init() }
        val provider = mockk<net.enthusia.loreitems.api.v1.CrownReclamationV1>()
        val expected = net.enthusia.loreitems.api.v1.CrownReclamationV1.Result.RESERVED
        io.mockk.every { provider.reserve(request.operationId, request.instanceId, request.holderId, request.generation) } returns
            java.util.concurrent.CompletableFuture.completedFuture(expected)
        val native = net.badgersmc.ek.infrastructure.bukkit.NativeLoreCrownCustody(provider)
        val evidence = net.badgersmc.ek.domain.CrownRecoveryEvidence(true, true, true, false, false)
        assertEquals(RecoveryOutcome.HELD_RESERVED, CrownRecoveryCoordinator(store, native)
            .requestReclamation(request, evidence).toCompletableFuture().join())
        assertEquals(RecoveryOutcome.HELD_RESERVED, CrownRecoveryCoordinator(SqlCrownRecoveryHold(ds), native)
            .requestReclamation(request, evidence).toCompletableFuture().join())
        assertEquals(CustodyCapability.UNSUPPORTED, native.inspect())
        assertEquals(RecoveryOutcome.CONFLICT, CrownRecoveryCoordinator(store, native)
            .requestReclamation(request.copy(generation = 2), evidence).toCompletableFuture().join())
        verify(exactly = 2) { provider.reserve(request.operationId, request.instanceId, request.holderId, request.generation) }
    }

    @Test fun `insufficient opportunities never reserve either journal or provider`() {
        val holds = mockk<CrownRecoveryHoldPort>()
        val provider = mockk<CrownCustodyCapabilityPort>()
        val evidence = net.badgersmc.ek.domain.CrownRecoveryEvidence(true, false, true, false, false)
        assertEquals(RecoveryOutcome.RETAINED, CrownRecoveryCoordinator(holds, provider)
            .requestReclamation(request, evidence).toCompletableFuture().join())
        verify(exactly = 0) { holds.reserve(any()) }
        verify(exactly = 0) { provider.reserveReclamation(any()) }
    }

    @Test fun `legacy provider stays held across restart without queueing an item`() {
        val ds = source(); val store = SqlCrownRecoveryHold(ds); store.init()
        val provider = mockk<LoreItemsServiceV1>()
        val coordinator = CrownRecoveryCoordinator(store, LegacyLoreCrownCustody(provider))
        assertEquals(RecoveryOutcome.HELD_UNSUPPORTED, coordinator.request(request))
        val restarted = SqlCrownRecoveryHold(ds); restarted.init()
        assertEquals(RecoveryOutcome.HELD_UNSUPPORTED, CrownRecoveryCoordinator(restarted, LegacyLoreCrownCustody(provider)).request(request))
        assertEquals(RecoveryOutcome.CONFLICT, coordinator.request(request.copy(operationId = UUID.randomUUID())))
        verify(exactly = 0) { provider.queueDelivery(any(), any(), any()) }
        verify(exactly = 0) { provider.isDefinitionActive(any()) }
        assertNotNull(restarted.read())
    }

    @Test fun `failed durable hold prevents provider inspection`() {
        val holds = mockk<CrownRecoveryHoldPort>()
        val provider = mockk<CrownCustodyCapabilityPort>()
        io.mockk.every { holds.reserve(any()) } throws IllegalStateException("storage unavailable")
        assertThrows(IllegalStateException::class.java) { CrownRecoveryCoordinator(holds, provider).request(request) }
        verify(exactly = 0) { provider.inspect() }
    }

    @Test fun `provider error keeps the durable hold`() {
        val ds = source(); val store = SqlCrownRecoveryHold(ds); store.init()
        val provider = mockk<CrownCustodyCapabilityPort>()
        io.mockk.every { provider.inspect() } throws IllegalStateException("unavailable")
        assertEquals(RecoveryOutcome.HELD_PROVIDER_ERROR, CrownRecoveryCoordinator(store, provider).request(request))
        assertNotNull(SqlCrownRecoveryHold(ds).read())
    }
}
