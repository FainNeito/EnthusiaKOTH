package net.badgersmc.ek.domain

import java.util.UUID

/** Scoring time stays bound to the team represented when it was earned. */
class ScoringParticipation {
    private val seconds = mutableMapOf<TeamId, Long>()
    private val players = mutableMapOf<TeamId, MutableMap<UUID, Long>>()
    private val observed = mutableSetOf<TeamId>()
    fun record(team: TeamId, players: Set<UUID>) {
        if (players.isEmpty()) return
        observe(listOf(team))
        seconds.merge(team, 1L, Long::plus)
        val ledger = this.players.getOrPut(team) { mutableMapOf() }
        players.forEach { ledger.merge(it, 1L, Long::plus) }
    }
    fun eligible(team: TeamId, percent: Double): Set<UUID> {
        if (!percent.isFinite() || percent !in 0.0..100.0) return emptySet()
        val total = seconds[team]?.takeIf { it > 0 } ?: return emptySet()
        return players[team].orEmpty().filterValues { it > 0 && it.toDouble() * 100 / total >= percent }.keys.toSet()
    }
    fun teamCount(): Int = observed.size
    fun playerSeconds(team: TeamId, player: UUID): Long = players[team]?.get(player) ?: 0L
    fun observe(teams: Collection<TeamId>) { observed.addAll(teams) }
}
