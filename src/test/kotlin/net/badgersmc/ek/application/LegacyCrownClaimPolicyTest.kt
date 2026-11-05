package net.badgersmc.ek.application

import java.util.UUID
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class LegacyCrownClaimPolicyTest {
    @Test fun `only historical Crown relic claims are blocked by this guard`() {
        val player=UUID.randomUUID()
        fun claim(id: String, kind: String = "LORE") = ProgressionClaim(id,player,kind,"definition",1,"PENDING","")
        assertTrue(LegacyCrownClaimPolicy.isLegacyRelicClaim(claim("challenge:sovereign_crown:$player:relic")))
        assertFalse(LegacyCrownClaimPolicy.isLegacyRelicClaim(claim("challenge:sovereign_crown:$player:tag", "TAG")))
        assertFalse(LegacyCrownClaimPolicy.isLegacyRelicClaim(claim("challenge:sovereign_blade:$player:relic")))
        assertFalse(LegacyCrownClaimPolicy.isLegacyRelicClaim(claim("event:123:item")))
    }
}
