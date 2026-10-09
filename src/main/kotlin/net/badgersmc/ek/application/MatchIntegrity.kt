package net.badgersmc.ek.application

/** Independent evidence, not a claim that accounts represent different humans. */
data class ContestEvidence(val controlChanges: Int = 0, val opposingScoreSeconds: Int = 0,
    val reciprocalCombatSeconds: Int = 0, val contestedSeconds: Int = 0)

data class IntegrityPolicy(
    val requireContest: Boolean = false,
    val minimumControlChanges: Int = 1,
    val minimumOpposingScoreSeconds: Int = 30,
    val minimumReciprocalCombatSeconds: Int = 10,
    val holdSuspicious: Boolean = false,
    val patternWindowHours: Int = 24,
    val patternMinimumMatches: Int = 3,
    val playerDailyCents: Long = 0,
    val playerDailyUnits: Int = 0,
    val sideDailyCents: Long = 0,
    val sideDailyUnits: Int = 0,
    val requireReadiness: Boolean = false,
    val acceptedArenas: Set<String> = emptySet(),
) {
    fun validate() {
        require(minimumControlChanges > 0 && minimumOpposingScoreSeconds > 0 && minimumReciprocalCombatSeconds > 0)
        require(patternWindowHours in 1..720 && patternMinimumMatches in 2..100)
        require(playerDailyCents in 0..100_000_000 && sideDailyCents in 0..100_000_000)
        require(playerDailyUnits in 0..10000 && sideDailyUnits in 0..10000)
        require(acceptedArenas.size<=2048 && acceptedArenas.all { it.isNotBlank() && it.length<=128 })
    }
    fun contested(e: ContestEvidence): Boolean = e.controlChanges >= minimumControlChanges ||
        e.opposingScoreSeconds >= minimumOpposingScoreSeconds || e.reciprocalCombatSeconds >= minimumReciprocalCombatSeconds
}

data class MatchDecision(val id: String, val status: String, val flags: List<String>, val detail: String)
data class PlayerMatchResult(val eventId: String, val arena: String, val at: java.time.Instant,
    val scoringSeconds: Long, val qualifying: Boolean, val status: String, val detail: String)

/** No platform dependency; callers supply live evidence and fail closed on unknowns. */
object RewardedStartReadiness {
    fun issues(policy: ProgressionPolicy, arena: String, protection: Boolean, providers: List<String>): List<String> {
        if (!policy.enabled || !policy.integrity.requireReadiness) return emptyList()
        return buildList {
            if (!protection) add("Reward protection is disabled")
            if (arena !in policy.integrity.acceptedArenas) add("Arena $arena has no accepted region/rotation review")
            addAll(providers)
        }
    }
}
