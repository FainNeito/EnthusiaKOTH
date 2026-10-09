package net.badgersmc.ek.application

import net.badgersmc.ek.domain.TeamId
import net.badgersmc.ek.domain.TeamMode
import java.time.Instant
import java.util.UUID

data class RewardProtectionConfig(
    val enabled: Boolean = false,
    val minimumOppositionSeconds: Int = 30,
    val minimumAccountAgeDays: Int = 7,
    val minimumPlaytimeMinutes: Int = 120,
    val maximumRecipientCommands: Int = 1,
    val repeatedOpponentWindowHours: Int = 24,
    val maximumRepeatedWins: Int = 2,
)

data class ProtectionRoster(val alliances: Map<UUID, Set<UUID>>, val memberships: Map<UUID, Set<UUID>>)
data class AccountEvidence(val firstSeen: Instant, val playedSeconds: Long)

/** Append-only decisions. Reserve must be durable and atomic across simultaneous events. */
interface RewardProtectionStore {
    fun reserve(eventId: UUID, arena: String, winner: Set<String>, opponents: Set<String>,
                now: Instant, config: RewardProtectionConfig, detail: String): Boolean
    fun reject(eventId: UUID, arena: String, now: Instant, reason: String)
}

/** Match identities are fixed; alliance edges only grow during a match. */
class ProtectedMatch(val config: RewardProtectionConfig, val mode: TeamMode, roster: ProtectionRoster?) {
    private val memberships = roster?.memberships?.mapValues { it.value.toSet() }.orEmpty()
    private val edges = roster?.alliances?.mapValues { it.value.toMutableSet() }?.toMutableMap() ?: mutableMapOf()
    private val observed = mutableSetOf<TeamId>()
    private val activity = mutableMapOf<Pair<TeamId, TeamId>, Int>()
    private val scoring = mutableMapOf<TeamId, Int>()
    private val transfers = mutableListOf<Pair<TeamId, TeamId>>()
    private var lastScoringController: TeamId? = null
    private val combat = mutableMapOf<Pair<TeamId, TeamId>, MutableSet<Long>>()
    private val soloIds = mutableMapOf<UUID, TeamId>()
    private val sides = mutableMapOf<UUID, Set<String>>()
    val ineligibleAccounts = mutableSetOf<UUID>()
    val invalidPlayers = mutableSetOf<UUID>()
    var available: Boolean = roster != null
        private set
    var allianceChanged: Boolean = false
        private set
    fun relationChanged() { allianceChanged = true }

    fun reconcile(graph: Map<UUID, Set<UUID>>?) {
        if (graph == null) { available = false; return }
        // Once unavailable, never resume this match with a partially reconstructed graph.
        graph.forEach { (guild, allies) ->
            allies.forEach { ally ->
                val prior = edges.getOrPut(guild) { mutableSetOf() }
                if (ally !in prior) { allianceChanged = true; sides.clear() }
                prior.add(ally)
                edges.getOrPut(ally) { mutableSetOf() }.add(guild)
            }
        }
    }

    fun team(player: UUID, currentGuilds: Set<UUID>): TeamId? {
        if (!available || player in invalidPlayers) return null
        val original = memberships[player].orEmpty()
        if (original != currentGuilds || original.size > 1) { invalidPlayers.add(player); return null }
        if (mode == TeamMode.SOLO) return soloIds.getOrPut(player) { TeamId(TeamMode.SOLO, player) }
        if (original.size != 1) return null
        return TeamId(TeamMode.GUILD, original.single())
    }

    fun accountEligible(evidence: AccountEvidence?, now: Instant): Boolean = evidence != null &&
        evidence.firstSeen > Instant.EPOCH && !evidence.firstSeen.isAfter(now) &&
        !evidence.firstSeen.plusSeconds(config.minimumAccountAgeDays.toLong() * 86400).isAfter(now) &&
        evidence.playedSeconds >= config.minimumPlaytimeMinutes.toLong() * 60

    fun side(team: TeamId): Set<String> {
        if (team.mode == TeamMode.SOLO) {
            val guild = memberships[team.id]?.singleOrNull() ?: return setOf(team.storageKey())
            val guildSide = side(TeamId(TeamMode.GUILD, guild))
            val accounts = memberships.filterValues { ids -> ids.any { TeamId(TeamMode.GUILD,it).storageKey() in guildSide } }.keys
            return guildSide + accounts.map { TeamId(TeamMode.SOLO,it).storageKey() }
        }
        sides[team.id]?.let { return it }
        val found = mutableSetOf(team.id)
        val pending = ArrayDeque<UUID>().apply { add(team.id) }
        while (pending.isNotEmpty()) {
            val id = pending.removeFirst()
            // Accept asymmetric edges conservatively, including indirect allies.
            val neighbours = edges[id].orEmpty() + edges.filterValues { id in it }.keys
            neighbours.filter { found.add(it) }.forEach(pending::addLast)
        }
        val result = found.map { TeamId(TeamMode.GUILD, it).storageKey() }.toSet()
        found.forEach { sides[it] = result }
        return result
    }

    fun sameSide(a: TeamId, b: TeamId): Boolean = side(a).any { it in side(b) }
    fun observe(teams: List<TeamId>, controller: TeamId?, earnedScore: Boolean) {
        observed.addAll(teams)
        if (earnedScore && controller != null) scoring.merge(controller, 1, Int::plus)
        if (earnedScore && controller != null) {
            lastScoringController?.takeIf { !sameSide(it, controller) }?.let { transfers.add(it to controller) }
            lastScoringController = controller
        }
        teams.forEach { a -> teams.filter { it != a }.forEach { b -> activity.merge(a to b, 1, Int::plus) } }
    }

    /** Called only for effective, uncancelled PvP in this active event's objective. */
    fun combat(attacker: TeamId, victim: TeamId, second: Long) {
        if (!available || sameSide(attacker, victim)) return
        val samples = combat.getOrPut(attacker to victim) { mutableSetOf() }
        if (samples.size < 3600) samples.add(second)
    }

    fun evidence(winner: TeamId): ContestEvidence {
        val opponents = observed.filter { !sameSide(winner, it) }
        // Reciprocal exchanges must occur within a five-second window; spam in one tick counts once.
        // Match the SAME independent side in each direction; two unrelated opponents cannot cooperate to fake an exchange.
        val reciprocal = combat.keys.filter { sameSide(it.first,winner) && !sameSide(it.second,winner) }
            .groupBy { side(it.second).sorted().joinToString(",") }.values.flatMap { pairs ->
                val forward=pairs.flatMap { combat[it].orEmpty() }.toSet()
                val opponent=pairs.first().second
                val backward=combat.filterKeys { sameSide(it.first,opponent) && sameSide(it.second,winner) }.values.flatten().toSet()
                forward.filter { a -> (-5L..5L).any { delta -> a+delta in backward } }
            }.toSet().size
        return ContestEvidence(transfers.count { sameSide(it.first,winner) || sameSide(it.second,winner) },
            opponents.maxOfOrNull { scoring[it] ?: 0 } ?: 0, reciprocal,
            opponents.maxOfOrNull { activity[winner to it] ?: 0 } ?: 0)
    }

    fun qualifyingOpponents(winner: TeamId): Set<String> {
        if (!available) return emptySet()
        return qualifyingGroups(winner).flatMap { side(it.first()) }.toSet()
    }

    private fun qualifyingGroups(winner: TeamId): Collection<List<TeamId>> {
        val opposition = observed.filter { !sameSide(winner, it) }
        return opposition.groupBy { side(it).sorted().joinToString(",") }.values
            .filter { group ->
                // One second per side, even when many allied guilds/accounts stand together.
                val scored = group.maxOfOrNull { scoring[it] ?: 0 } ?: 0
                val contested = group.maxOfOrNull { activity[winner to it] ?: 0 } ?: 0
                maxOf(scored, contested) >= config.minimumOppositionSeconds
            }
    }

    fun qualifyingSideCount(winner: TeamId): Int = if (available) 1 + qualifyingGroups(winner).size else 0
    fun opponentGroups(winner: TeamId): Set<String> = qualifyingGroups(winner).map { side(it.first()).sorted().joinToString(",") }.toSet()
    fun oppositionSeconds(winner: TeamId): Int = observed.filter { !sameSide(winner, it) }
        .maxOfOrNull { maxOf(scoring[it] ?: 0, activity[winner to it] ?: 0) } ?: 0

    fun sideCount(teams: Collection<TeamId>): Int = teams.map { side(it).sorted().joinToString(",") }.distinct().size
    fun audit(): String = "allianceChanged=$allianceChanged; invalidPlayers=${invalidPlayers.sorted()}; ineligibleAccounts=${ineligibleAccounts.sorted()}; scoring=$scoring; hillActivity=$activity"
}
