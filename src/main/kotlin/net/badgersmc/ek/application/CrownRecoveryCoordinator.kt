package net.badgersmc.ek.application

import java.time.Instant
import java.util.UUID

data class RecoveryRequest(val operationId: UUID, val instanceId: UUID, val holderId: UUID,
    val generation: Long, val requestedAt: Instant) {
    init { require(generation >= 0) }
}
enum class RecoveryReservation { CREATED, ALREADY_HELD, CONFLICT }
enum class CustodyCapability { UNSUPPORTED }
enum class RecoveryOutcome { RETAINED, HELD_RESERVED, HELD_UNSUPPORTED, HELD_PROVIDER_ERROR, CONFLICT }

interface CrownRecoveryHoldPort {
    /** Must commit the hold before returning. Failure must throw. */
    fun reserve(request: RecoveryRequest): RecoveryReservation
}

interface CrownCustodyCapabilityPort {
    /** Read-only capability assessment; it must not move, equip, queue or create items. */
    fun inspect(): CustodyCapability
    fun reserveReclamation(request: RecoveryRequest): java.util.concurrent.CompletionStage<RecoveryOutcome> =
        java.util.concurrent.CompletableFuture.completedFuture(RecoveryOutcome.HELD_UNSUPPORTED)
}

/** Records a single durable hold before checking a provider. No release path exists yet. */
class CrownRecoveryCoordinator(
    private val holds: CrownRecoveryHoldPort,
    private val custody: CrownCustodyCapabilityPort,
) {
    /** Inactivity is a prerequisite for reservation; the provider must still establish physical custody later. */
    fun requestReclamation(request: RecoveryRequest, evidence: net.badgersmc.ek.domain.CrownRecoveryEvidence):
        java.util.concurrent.CompletionStage<RecoveryOutcome> {
        if (!evidence.inactivityEstablished || !evidence.opportunitiesSufficient || !evidence.expiryReached) {
            return java.util.concurrent.CompletableFuture.completedFuture(RecoveryOutcome.RETAINED)
        }
        if (holds.reserve(request) == RecoveryReservation.CONFLICT) {
            return java.util.concurrent.CompletableFuture.completedFuture(RecoveryOutcome.CONFLICT)
        }
        return try {
            custody.reserveReclamation(request).exceptionally { RecoveryOutcome.HELD_PROVIDER_ERROR }
        } catch (_: Exception) {
            java.util.concurrent.CompletableFuture.completedFuture(RecoveryOutcome.HELD_PROVIDER_ERROR)
        }
    }

    fun request(request: RecoveryRequest): RecoveryOutcome {
        if (holds.reserve(request) == RecoveryReservation.CONFLICT) return RecoveryOutcome.CONFLICT
        return try {
            when (custody.inspect()) {
                CustodyCapability.UNSUPPORTED -> RecoveryOutcome.HELD_UNSUPPORTED
            }
        } catch (_: Exception) {
            RecoveryOutcome.HELD_PROVIDER_ERROR
        }
    }
}
