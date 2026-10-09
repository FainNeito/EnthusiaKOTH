package net.badgersmc.ek.application

import java.time.Instant
import java.util.UUID

data class ChallengeRule(
    val id: String, val wins: Int, val opponents: Int, val arenas: Int, val days: Int,
    val seconds: Long, val tag: String = "", val loreDefinition: String = "", val exclusive: Boolean = false,
)
data class RewardPackage(val id: String, val moneyCents: Long, val loreDefinition: String = "", val units: Int = 0)
data class ProgressionPolicy(
    val enabled: Boolean = false,
    val poolCents: Long = 0,
    val packageUnits: Int = 0,
    val contributionPercent: Double = 10.0,
    val minimumScoringSeconds: Long = 120,
    val minimumOppositionSeconds: Int = 120,
    val packages: List<RewardPackage> = emptyList(),
    val challenges: List<ChallengeRule> = emptyList(),
    val guildXpCommand: String = "",
    val minimumRosterAgeSeconds: Long = 0,
    val integrity: IntegrityPolicy = IntegrityPolicy(),
) {
    fun validate() {
        integrity.validate()
        require(poolCents in 0..100_000_000 && packageUnits in 0..100)
        require(contributionPercent.isFinite() && contributionPercent in 10.0..100.0)
        require(minimumScoringSeconds >= 1 && minimumOppositionSeconds >= 1)
        require(minimumRosterAgeSeconds >= 0)
        require(challenges.size <= 32 && packages.size <= 8)
        require(challenges.map { it.id }.distinct().size == challenges.size)
        require(packages.map { it.id }.distinct().size == packages.size)
        (challenges.map { it.id } + packages.map { it.id }).forEach { require(it.matches(Regex("[a-z0-9_-]{1,64}"))) }
        challenges.forEach { require(it.wins > 0 && it.opponents > 0 && it.arenas > 0 && it.days > 0 && it.seconds > 0) }
        // Every choice consumes precisely the same configured event budget.
        packages.forEach { require(it.moneyCents == poolCents && it.units == packageUnits) }
        require(poolCents >= 0 && (packageUnits == 0 || (packages.isNotEmpty() && packages.all { it.loreDefinition.isNotBlank() })))
    }
}
data class MatchContribution(val player: UUID, val guild: String, val seconds: Long, val eligible: Boolean)
data class VerifiedMatch(
    val eventId: UUID, val arena: String, val family: String, val source: String,
    val at: Instant, val winner: String?, val opponents: Set<String>, val contributions: List<MatchContribution>,
    val oppositionSeconds: Int, val relationChanged: Boolean, val detail: String,
    val evidence: ContestEvidence = ContestEvidence(),
    val winnerSide: Set<String> = winner?.let { setOf(it) }.orEmpty(),
    val arenaIdentity: String = arena,
)
data class ChallengeTotals(val wins: Int, val opponents: Int, val arenas: Int, val days: Int, val seconds: Long) {
    fun progress(rule: ChallengeRule): Int = listOf(
        wins.toDouble() / rule.wins, opponents.toDouble() / rule.opponents,
        arenas.toDouble() / rule.arenas, days.toDouble() / rule.days, seconds.toDouble() / rule.seconds,
    ).min().times(100).toInt().coerceIn(0, 100)
}
data class ProgressionClaim(
    val id: String, val player: UUID, val kind: String, val value: String, val amount: Long,
    val status: String, val detail: String,
)
data class MatchReport(val id: String, val arena: String, val at: Instant, val winner: String?, val detail: String)

/** Integer split; account count never increases the configured pool. */
object RewardPool {
    fun split(total: Long, contributions: List<MatchContribution>): Map<UUID, Long> {
        require(total >= 0)
        val recipients = contributions.filter { it.eligible }.distinctBy { it.player }
            .sortedWith(compareByDescending<MatchContribution> { it.seconds }.thenBy { it.player.toString() })
        if (recipients.isEmpty()) return emptyMap()
        return recipients.mapIndexed { index, recipient ->
            recipient.player to total / recipients.size + if (index < total % recipients.size) 1 else 0
        }.toMap()
    }
}

interface MatchProgressionSink {
    fun complete(match: VerifiedMatch)
    fun complete(match: VerifiedMatch, frozenPolicy: ProgressionPolicy) = complete(match)
}
