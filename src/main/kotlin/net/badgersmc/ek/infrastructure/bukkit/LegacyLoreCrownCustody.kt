package net.badgersmc.ek.infrastructure.bukkit

import net.badgersmc.ek.application.CrownCustodyCapabilityPort
import net.badgersmc.ek.application.CustodyCapability
import net.enthusia.loreitems.api.v1.LoreItemsServiceV1

/** V1 queue acceptance cannot prove equipped custody, exclusion or safe recovery. */
class LegacyLoreCrownCustody(@Suppress("unused") private val provider: LoreItemsServiceV1) : CrownCustodyCapabilityPort {
    override fun inspect(): CustodyCapability = CustodyCapability.UNSUPPORTED
}
