package net.badgersmc.ek.infrastructure.persistence

import java.io.File
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executors
import net.badgersmc.ek.domain.CrownEventPurpose
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.sqlite.SQLiteDataSource

class CrownPersistenceTest {
    @TempDir lateinit var directory: File
    private fun source() = SQLiteDataSource().apply { url = "jdbc:sqlite:${File(directory, "crown.db")}" }
    private val slot = CrownOccurrence("slot-1", "hill", Instant.parse("2026-11-08T17:00:00Z"), CrownEventPurpose.FIRST_CROWN_FINAL, "revision-1")

    @Test fun `occurrence persists across restart with immutable metadata and no terminal replay`() {
        val ds = source(); val first = SqlCrownEventStore(ds); first.init()
        assertEquals(CrownOccurrenceWrite.CREATED, first.plan(slot))
        assertEquals(CrownOccurrenceWrite.ALREADY_EXISTS, first.plan(slot))
        assertEquals(CrownOccurrenceWrite.CONFLICT, first.plan(slot.copy(arenaId = "other")))
        assertEquals(CrownOccurrenceWrite.CONFLICT, first.plan(slot.copy(policyRevision = "revision-2")))
        val match = UUID.randomUUID(); assertTrue(first.start(slot.occurrenceId, match))
        val restarted = SqlCrownEventStore(ds); restarted.init()
        assertEquals(slot.copy(state = CrownOccurrenceState.STARTED, matchId = match), restarted.read(slot.occurrenceId))
        assertEquals(CrownOccurrenceWrite.ALREADY_EXISTS, restarted.plan(slot))
        assertFalse(restarted.start(slot.occurrenceId, UUID.randomUUID()))
        assertFalse(restarted.finish(slot.occurrenceId, UUID.randomUUID(), false))
        assertTrue(restarted.finish(slot.occurrenceId, match, false))
        assertFalse(restarted.finish(slot.occurrenceId, match, true))
        assertFalse(restarted.start(slot.occurrenceId, match))
        assertEquals(CrownOccurrenceState.COMPLETED, restarted.read(slot.occurrenceId)!!.state)
    }

    @Test fun `scheduled final binds one matching solo activation across reopen`() {
        val events = SqlCrownEventStore(source()).also { it.init(); it.plan(slot) }
        val match = UUID.randomUUID()
        fun bind(store: SqlCrownEventStore, id: UUID = match) =
            store.bindScheduledMatch(slot.occurrenceId, slot.arenaId, slot.scheduledAt, true, id)
        assertFalse(events.bindScheduledMatch(slot.occurrenceId, slot.arenaId, slot.scheduledAt, false, match))
        assertFalse(events.bindScheduledMatch(slot.occurrenceId, "other", slot.scheduledAt, true, match))
        assertFalse(events.bindScheduledMatch(slot.occurrenceId, slot.arenaId, slot.scheduledAt.plusSeconds(1), true, match))
        assertTrue(bind(events))
        val restarted = SqlCrownEventStore(source())
        assertTrue(bind(restarted))
        assertFalse(bind(restarted, UUID.randomUUID()))
        assertTrue(restarted.finish(slot.occurrenceId, match, false))
        assertFalse(bind(restarted))
    }

    @Test fun `cancelled event cannot restart and a match belongs to at most one occurrence`() {
        val store = SqlCrownEventStore(source()); store.init()
        val second = slot.copy(occurrenceId = "slot-2"); val third = slot.copy(occurrenceId = "slot-3")
        listOf(slot, second, third).forEach { assertEquals(CrownOccurrenceWrite.CREATED, store.plan(it)) }
        val match = UUID.randomUUID(); assertTrue(store.start(slot.occurrenceId, match))
        assertFalse(store.start(second.occurrenceId, match))
        assertTrue(store.cancelPlanned(third.occurrenceId)); assertFalse(store.start(third.occurrenceId, UUID.randomUUID()))
        assertTrue(store.finish(slot.occurrenceId, match, true)); assertFalse(store.finish(slot.occurrenceId, match, false))
    }

    @Test fun `concurrent starts permit only one claimant`() {
        val ds = source(); val store = SqlCrownEventStore(ds); store.init(); store.plan(slot)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val results = (1..2).map { pool.submit<Boolean> { SqlCrownEventStore(ds).start(slot.occurrenceId, UUID.randomUUID()) } }.map { it.get() }
            assertEquals(1, results.count { it })
        } finally { pool.shutdownNow() }
    }

    @Test fun `restart reconciliation pages only open events and permits a full rescan`() {
        val ds = source(); val store = SqlCrownEventStore(ds); store.init()
        val entries = (1..5).map { slot.copy(occurrenceId = "slot-$it") }
        entries.forEach { store.plan(it) }
        val match = UUID.randomUUID(); assertTrue(store.start("slot-2", match))
        assertTrue(store.cancelPlanned("slot-3"))
        val restarted = SqlCrownEventStore(ds); restarted.init()
        val first = restarted.listOpenPage(null, 2)
        assertEquals(listOf("slot-1", "slot-2"), first.occurrences.map { it.occurrenceId })
        assertEquals(CrownOccurrenceState.STARTED, first.occurrences.last().state)
        assertEquals("slot-2", first.nextCursor)
        val last = restarted.listOpenPage(first.nextCursor, 2)
        assertEquals(listOf("slot-4", "slot-5"), last.occurrences.map { it.occurrenceId })
        assertNull(last.nextCursor)
        assertTrue(restarted.finish("slot-2", match, false))
        restarted.plan(slot.copy(occurrenceId = "slot-0"))
        assertEquals(listOf("slot-0", "slot-1"), restarted.listOpenPage(null, 2).occurrences.map { it.occurrenceId })
        assertThrows(IllegalArgumentException::class.java) { restarted.listOpenPage(null, 0) }
        assertThrows(IllegalArgumentException::class.java) { restarted.listOpenPage(null, 201) }
    }

    @Test fun `recovery hold survives restart and rejects changed operations and holders`() {
        val ds = source(); val store = SqlCrownRecoveryHold(ds); store.init()
        val hold = CrownRecoveryHold(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1, Instant.parse("2026-11-04T21:00:00Z"))
        assertEquals(RecoveryHoldResult.CREATED, store.hold(hold))
        val restarted = SqlCrownRecoveryHold(ds); restarted.init()
        assertEquals(hold, restarted.read()); assertEquals(RecoveryHoldResult.ALREADY_HELD, restarted.hold(hold))
        assertEquals(RecoveryHoldResult.CONFLICT, restarted.hold(hold.copy(operationId = UUID.randomUUID())))
        assertEquals(RecoveryHoldResult.CONFLICT, restarted.hold(hold.copy(holderId = UUID.randomUUID())))
        assertEquals(RecoveryHoldResult.CONFLICT, restarted.hold(hold.copy(instanceId = UUID.randomUUID())))
        assertEquals(RecoveryHoldResult.CONFLICT, restarted.hold(hold.copy(generation = 2)))
        assertEquals(hold, restarted.read())
    }

    @Test fun `concurrent recovery requests keep only one durable hold`() {
        val ds = source(); val store = SqlCrownRecoveryHold(ds); store.init()
        val pool = Executors.newFixedThreadPool(2)
        try {
            val requests = (1..2).map { CrownRecoveryHold(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1, Instant.parse("2026-11-04T21:00:00Z")) }
            val results = requests.map { req -> pool.submit<RecoveryHoldResult> { SqlCrownRecoveryHold(ds).hold(req) } }.map { it.get() }
            assertEquals(1, results.count { it == RecoveryHoldResult.CREATED })
            assertEquals(1, results.count { it == RecoveryHoldResult.CONFLICT })
            assertNotNull(store.read())
        } finally { pool.shutdownNow() }
    }

    @Test fun `legacy hold cannot be silently adopted without instance and generation`() {
        val ds = source()
        ds.connection.use { c -> c.createStatement().use { s ->
            s.execute("CREATE TABLE koth_crown_recovery_hold (crown_key TEXT PRIMARY KEY, operation_id TEXT, holder_id TEXT, requested_at TEXT)")
            s.execute("INSERT INTO koth_crown_recovery_hold VALUES ('sovereign_crown','old-operation','old-holder','2026-11-04T21:00:00Z')")
        } }
        assertThrows(IllegalStateException::class.java) { SqlCrownRecoveryHold(ds).init() }
        ds.connection.use { c -> c.createStatement().use { s ->
            s.executeQuery("SELECT operation_id FROM koth_crown_recovery_hold").use { r ->
                assertTrue(r.next()); assertEquals("old-operation", r.getString(1))
            }
        } }
        assertThrows(IllegalArgumentException::class.java) {
            CrownRecoveryHold(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), -1, Instant.EPOCH)
        }
    }
}
