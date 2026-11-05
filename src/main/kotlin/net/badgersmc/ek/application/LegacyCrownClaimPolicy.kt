package net.badgersmc.ek.application

/** The historical relic claim must not bypass Crown-specific custody and fencing. */
object LegacyCrownClaimPolicy {
    const val CHALLENGE_ID = "sovereign_crown"

    fun isLegacyRelicClaim(claim: ProgressionClaim): Boolean = claim.kind == "LORE" &&
        claim.id.startsWith("challenge:$CHALLENGE_ID:") && claim.id.endsWith(":relic")
}
