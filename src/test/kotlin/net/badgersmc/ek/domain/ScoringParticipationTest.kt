package net.badgersmc.ek.domain

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

class ScoringParticipationTest {
    private val guild = TeamId(TeamMode.GUILD, UUID.randomUUID())
    private val a = UUID.randomUUID()
    private val b = UUID.randomUUID()

    @Test fun `threshold counts scoring seconds rather than dividing by member count`() {
        val ledger = ScoringParticipation()
        repeat(9) { ledger.record(guild, setOf(a)) }
        ledger.record(guild, setOf(a, b))
        assertEquals(setOf(a, b), ledger.eligible(guild, 10.0))
        assertEquals(setOf(a), ledger.eligible(guild, 20.0))
    }

    @Test fun `zero threshold still requires contribution and guild changes do not transfer it`() {
        val ledger = ScoringParticipation()
        val other = TeamId(TeamMode.GUILD, UUID.randomUUID())
        ledger.record(guild, setOf(a))
        assertEquals(setOf(a), ledger.eligible(guild, 0.0))
        assertTrue(ledger.eligible(other, 0.0).isEmpty())
        assertTrue(ledger.eligible(guild, Double.NaN).isEmpty())
    }

    @Test fun `observed opposing teams survive score resets and contested ticks`() {
        val ledger = ScoringParticipation()
        val other = TeamId(TeamMode.GUILD, UUID.randomUUID())
        ledger.observe(listOf(guild, other, guild))
        assertEquals(2, ledger.teamCount())
        assertTrue(ledger.eligible(guild, 0.0).isEmpty())
    }
}
