package net.badgersmc.ek.infrastructure.persistence

import java.time.Instant
import java.util.UUID
import javax.sql.DataSource
import net.badgersmc.ek.application.CrownRecoveryHoldPort
import net.badgersmc.ek.application.RecoveryRequest
import net.badgersmc.ek.application.RecoveryReservation

enum class RecoveryHoldResult { CREATED, ALREADY_HELD, CONFLICT }
data class CrownRecoveryHold(val operationId: UUID, val instanceId: UUID, val holderId: UUID,
    val generation: Long, val requestedAt: Instant) {
    init { require(generation >= 0) }
}

/** Durable conservative boundary: this store cannot move, release or reissue the Crown. */
class SqlCrownRecoveryHold(private val source: DataSource) : CrownRecoveryHoldPort {
    fun init() = source.connection.use { c -> c.createStatement().use { s ->
        s.execute("""CREATE TABLE IF NOT EXISTS koth_crown_recovery_hold (
            crown_key TEXT PRIMARY KEY CHECK(crown_key='sovereign_crown'),
            operation_id TEXT NOT NULL UNIQUE, instance_id TEXT NOT NULL, holder_id TEXT NOT NULL,
            generation INTEGER NOT NULL CHECK(generation>=0), requested_at TEXT NOT NULL
        )""".trimIndent())
        s.execute("""CREATE TABLE IF NOT EXISTS koth_crown_prior_recovery_holds (
            operation_id TEXT PRIMARY KEY, instance_id TEXT NOT NULL, holder_id TEXT NOT NULL,
            generation INTEGER NOT NULL, requested_at TEXT NOT NULL)""")
        val columns = s.executeQuery("PRAGMA table_info(koth_crown_recovery_hold)").use { r ->
            buildSet { while (r.next()) add(r.getString("name")) }
        }
        check(columns.containsAll(setOf("crown_key", "operation_id", "instance_id", "holder_id", "generation", "requested_at"))) {
            "Legacy Crown recovery hold requires explicit reconciliation"
        }
    } }

    /** Only a newer authoritative provider generation can retire an obsolete local hold. */
    fun observeSuccessor(tenure:net.badgersmc.ek.application.CrownTenure)=source.connection.use { c ->
        c.autoCommit=false
        try {
            c.prepareStatement("""INSERT OR IGNORE INTO koth_crown_prior_recovery_holds
                SELECT operation_id,instance_id,holder_id,generation,requested_at FROM koth_crown_recovery_hold
                WHERE instance_id=? AND generation<?""").use { s ->
                s.setString(1,tenure.instance.toString());s.setLong(2,tenure.generation);s.executeUpdate()
            }
            c.prepareStatement("DELETE FROM koth_crown_recovery_hold WHERE instance_id=? AND generation<? AND operation_id IN (SELECT operation_id FROM koth_crown_prior_recovery_holds)").use { s ->
                s.setString(1,tenure.instance.toString());s.setLong(2,tenure.generation);s.executeUpdate()
            }
            c.commit()
        } finally {c.rollback()}
    }

    fun hold(request: CrownRecoveryHold): RecoveryHoldResult = source.connection.use { c ->
        val inserted = c.prepareStatement("INSERT OR IGNORE INTO koth_crown_recovery_hold (crown_key,operation_id,instance_id,holder_id,generation,requested_at) VALUES('sovereign_crown',?,?,?,?,?)").use { s ->
            s.setString(1, request.operationId.toString()); s.setString(2, request.instanceId.toString())
            s.setString(3, request.holderId.toString()); s.setLong(4, request.generation)
            s.setString(5, request.requestedAt.toString()); s.executeUpdate() == 1
        }
        if (inserted) RecoveryHoldResult.CREATED else if (read() == request) {
            RecoveryHoldResult.ALREADY_HELD
        } else RecoveryHoldResult.CONFLICT
    }

    override fun reserve(request: RecoveryRequest): RecoveryReservation = when (
        hold(CrownRecoveryHold(request.operationId, request.instanceId, request.holderId, request.generation, request.requestedAt))
    ) {
        RecoveryHoldResult.CREATED -> RecoveryReservation.CREATED
        RecoveryHoldResult.ALREADY_HELD -> RecoveryReservation.ALREADY_HELD
        RecoveryHoldResult.CONFLICT -> RecoveryReservation.CONFLICT
    }

    fun read(): CrownRecoveryHold? = source.connection.use { c -> c.createStatement().use { s ->
        s.executeQuery("SELECT operation_id,instance_id,holder_id,generation,requested_at FROM koth_crown_recovery_hold WHERE crown_key='sovereign_crown'").use { r ->
            if (!r.next()) null else CrownRecoveryHold(UUID.fromString(r.getString(1)), UUID.fromString(r.getString(2)),
                UUID.fromString(r.getString(3)), r.getLong(4).also { check(!r.wasNull()) }, Instant.parse(r.getString(5)))
        }
    } }
}
