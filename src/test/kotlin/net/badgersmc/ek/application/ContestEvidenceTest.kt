package net.badgersmc.ek.application

import net.badgersmc.ek.domain.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.util.UUID

class ContestEvidenceTest {
    private fun team()=TeamId(TeamMode.GUILD,UUID.randomUUID())
    private fun match()=ProtectedMatch(RewardProtectionConfig(),TeamMode.GUILD,ProtectionRoster(emptyMap(),emptyMap()))
    @Test fun `passive hill co-presence is not genuine contest evidence`() {
        val m=match(); val a=team(); val b=team(); repeat(120) { m.observe(listOf(a,b),null,false) }
        assertEquals(120,m.evidence(a).contestedSeconds); assertFalse(IntegrityPolicy().contested(m.evidence(a)))
    }
    @Test fun `opponent scoring and real control transfers qualify without kills`() {
        val m=match(); val a=team(); val b=team(); repeat(30) { m.observe(listOf(b),b,true) }; m.observe(listOf(a),a,true)
        assertEquals(1,m.evidence(a).controlChanges); assertEquals(30,m.evidence(a).opposingScoreSeconds)
        assertTrue(IntegrityPolicy().contested(m.evidence(a)))
    }
    @Test fun `reciprocal combat is same opponent deduplicated and excludes self allies`() {
        val m=match(); val a=team(); val b=team(); val c=team()
        repeat(100) { m.combat(a,b,1); m.combat(c,a,1); m.combat(a,a,1) }
        assertEquals(0,m.evidence(a).reciprocalCombatSeconds)
        m.combat(b,a,6); assertEquals(1,m.evidence(a).reciprocalCombatSeconds)
        m.reconcile(mapOf(a.id to setOf(b.id))); assertEquals(0,m.evidence(a).reciprocalCombatSeconds)
    }
    @Test fun `readiness inactive by default and active gate includes unknown provider evidence`() {
        val p=ProgressionPolicy(enabled=true)
        assertTrue(RewardedStartReadiness.issues(p,"hill",false,listOf("unknown")).isEmpty())
        assertEquals(3,RewardedStartReadiness.issues(p.copy(integrity=IntegrityPolicy(requireReadiness=true)),"hill",false,listOf("unknown")).size)
    }
}
