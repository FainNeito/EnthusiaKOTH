package net.badgersmc.ek.application

import net.badgersmc.ek.domain.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.time.Instant
import java.util.UUID

class RewardProtectionTest {
    private val a=TeamId(TeamMode.GUILD,UUID.randomUUID())
    private val b=TeamId(TeamMode.GUILD,UUID.randomUUID())
    private val c=TeamId(TeamMode.GUILD,UUID.randomUUID())
    private val player=UUID.randomUUID()
    private fun match(graph:Map<UUID,Set<UUID>> = mapOf(a.id to emptySet(),b.id to emptySet(),c.id to emptySet())) =
        ProtectedMatch(RewardProtectionConfig(enabled=true),TeamMode.GUILD,ProtectionRoster(graph,mapOf(player to setOf(a.id))))

    @Test fun `allied alt guilds cannot count as opponents or earn separate side credit`() {
        val m=match(mapOf(a.id to setOf(b.id),b.id to setOf(c.id),c.id to emptySet()))
        repeat(100) { m.observe(listOf(a,b,c),b,true) }
        assertEquals(1,m.sideCount(listOf(a,b,c))); assertTrue(m.qualifyingOpponents(a).isEmpty())
    }
    @Test fun `brief opponent entry fails but thirty hill seconds qualifies`() {
        val m=match(); repeat(29) { m.observe(listOf(a,b),null,false) }
        assertTrue(m.qualifyingOpponents(a).isEmpty()); m.observe(listOf(a,b),null,false)
        assertEquals(setOf(b.storageKey()),m.qualifyingOpponents(a))
    }
    @Test fun `uncontested opposing score can qualify without granting allied multipliers`() {
        val m=match(); repeat(30) { m.observe(listOf(b),b,true) }
        assertEquals(setOf(b.storageKey()),m.qualifyingOpponents(a))
    }
    @Test fun `removing alliance cannot split side while new alliance removes enemy eligibility`() {
        val m=match(mapOf(a.id to setOf(b.id),b.id to emptySet(),c.id to emptySet()))
        m.reconcile(mapOf(a.id to emptySet(),b.id to emptySet(),c.id to emptySet()))
        assertTrue(m.sameSide(a,b)); repeat(30) { m.observe(listOf(a,c),null,false) }
        assertFalse(m.qualifyingOpponents(a).isEmpty())
        m.reconcile(mapOf(a.id to setOf(c.id),b.id to emptySet(),c.id to emptySet()))
        assertTrue(m.qualifyingOpponents(a).isEmpty()); assertTrue(m.allianceChanged)
    }
    @Test fun `guild swapping never transfers score eligibility or recovers by swapping back`() {
        val m=match(); assertEquals(a,m.team(player,setOf(a.id)))
        assertNull(m.team(player,setOf(b.id))); assertNull(m.team(player,setOf(a.id)))
        assertNull(m.team(UUID.randomUUID(),setOf(a.id)))
    }
    @Test fun `unknown provider fails closed and known guildless solo is supported`() {
        val m=ProtectedMatch(RewardProtectionConfig(),TeamMode.GUILD,null)
        assertFalse(m.available); assertNull(m.team(player,setOf(a.id)))
        val solo=ProtectedMatch(RewardProtectionConfig(),TeamMode.SOLO,ProtectionRoster(emptyMap(),emptyMap()))
        assertEquals(TeamId(TeamMode.SOLO,player),solo.team(player,emptySet()))
    }
    @Test fun `allied guild accounts cannot bypass opposition by selecting solo mode`() {
        val other=UUID.randomUUID()
        val m=ProtectedMatch(RewardProtectionConfig(),TeamMode.SOLO,ProtectionRoster(mapOf(a.id to setOf(b.id),b.id to setOf(a.id)),mapOf(player to setOf(a.id),other to setOf(b.id))))
        val first=m.team(player,setOf(a.id))!!; val second=m.team(other,setOf(b.id))!!
        repeat(30) { m.observe(listOf(first,second),second,true) }
        assertEquals(1,m.sideCount(listOf(first,second))); assertTrue(m.qualifyingOpponents(first).isEmpty())
    }
    @Test fun `unknown graph after activation permanently withholds protected eligibility`() {
        val m=match(); m.reconcile(null); m.reconcile(mapOf(a.id to emptySet()))
        assertFalse(m.available); assertNull(m.team(player,setOf(a.id)))
    }
    @Test fun `age and playtime boundaries reject new and unknown accounts`() {
        val m=match(); val now=Instant.parse("2026-10-08T12:00:00Z")
        assertFalse(m.accountEligible(null,now)); assertFalse(m.accountEligible(AccountEvidence(Instant.EPOCH,100000),now))
        assertFalse(m.accountEligible(AccountEvidence(now.minusSeconds(604799),7200),now))
        assertFalse(m.accountEligible(AccountEvidence(now.minusSeconds(604800),7199),now))
        assertTrue(m.accountEligible(AccountEvidence(now.minusSeconds(604800),7200),now))
    }
}
