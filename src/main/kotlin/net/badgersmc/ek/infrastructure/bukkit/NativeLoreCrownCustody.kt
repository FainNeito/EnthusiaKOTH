package net.badgersmc.ek.infrastructure.bukkit

import net.badgersmc.ek.application.CrownCustodyCapabilityPort
import net.badgersmc.ek.application.CustodyCapability
import net.badgersmc.ek.application.RecoveryOutcome
import net.badgersmc.ek.application.RecoveryRequest
import net.enthusia.loreitems.api.v1.CrownReclamationV1

/** A native reservation fences competing transfers, but does not establish event-ready physical custody. */
class NativeLoreCrownCustody(private val provider: CrownReclamationV1) : CrownCustodyCapabilityPort {
    override fun inspect() = CustodyCapability.UNSUPPORTED

    override fun reserveReclamation(request: RecoveryRequest) = provider.reserve(
        request.operationId, request.instanceId, request.holderId, request.generation,
    ).thenApply { result ->
        when (result) {
            CrownReclamationV1.Result.RESERVED, CrownReclamationV1.Result.ALREADY_RESERVED -> RecoveryOutcome.HELD_RESERVED
            CrownReclamationV1.Result.CONFLICT -> RecoveryOutcome.CONFLICT
            CrownReclamationV1.Result.UNAVAILABLE, null -> RecoveryOutcome.HELD_UNSUPPORTED
        }
    }
}
