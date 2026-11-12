package net.badgersmc.ek.infrastructure.persistence

import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.UUID
import net.badgersmc.ek.application.*
import net.badgersmc.ek.domain.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.sqlite.SQLiteDataSource

class CrownActivityStoreTest {
    @Test fun `unknown and future evidence prevent recovery after expiry`() {
        val now=start.plus(Duration.ofDays(9))
        val known=listOf(event("a",1),event("b",2))
        val base=CrownActivitySnapshot(tenure,"test",start,now,known,false)
        assertEquals(CrownActivityDecision.INACTIVITY_REVIEW,CrownInactivityAssessment.assess(policy,base,now,true))
        val unknown=CrownOpportunity("unknown",start.plus(Duration.ofDays(3)),CrownOpportunityStatus.UNKNOWN,false)
        assertEquals(CrownActivityDecision.HOLD_FOR_REVIEW,CrownInactivityAssessment.assess(policy,base.copy(opportunities=known+unknown),now,true))
        assertEquals(CrownActivityDecision.HOLD_FOR_REVIEW,CrownInactivityAssessment.assess(policy,base.copy(opportunities=known+event("future",10)),now,true))
        assertEquals(CrownActivityDecision.HOLD_FOR_REVIEW,CrownInactivityAssessment.assess(policy,base,now,false))
    }
    @Test fun `too few opportunities extend grace until a later qualifying event`() {
        val ledger=store();ledger.observe(tenure,"test",start);ledger.record(tenure,event("early",1))
        val later=start.plus(Duration.ofDays(9));ledger.observe(tenure,"test",later)
        assertEquals(CrownActivityDecision.INSUFFICIENT_OPPORTUNITIES,CrownInactivityAssessment.assess(policy,ledger.read(tenure)!!,later,true))
        ledger.record(tenure,event("late",9))
        assertEquals(CrownActivityDecision.INACTIVITY_REVIEW,CrownInactivityAssessment.assess(policy,ledger.read(tenure)!!,later,true))
    }
    @TempDir lateinit var directory: Path
    private val start=Instant.parse("2026-11-05T00:00:00Z")
    private val tenure=CrownTenure(UUID.randomUUID(),UUID.randomUUID(),1)
    private val policy=CrownInactivityPolicy(true,"test",Duration.ofDays(7),2)
    private fun store()=SqlCrownActivityStore(SQLiteDataSource().apply { url="jdbc:sqlite:${directory.resolve("activity.db")}" }).also{it.init()}
    private fun event(id:String,day:Long,participated:Boolean=false)=CrownOpportunity(id,start.plus(Duration.ofDays(day)),CrownOpportunityStatus.VERIFIED_ELIGIBLE,participated)
    @Test fun `restart retains observation window and duplicate callbacks do not inflate opportunities`() {
        val ledger=store();ledger.observe(tenure,"test",start)
        assertTrue(ledger.record(tenure,event("a",1)));assertTrue(ledger.record(tenure,event("a",1)))
        val restarted=store();val now=start.plus(Duration.ofDays(8));restarted.observe(tenure,"test",now)
        val snapshot=restarted.read(tenure)!!
        assertEquals(start,snapshot.observedFrom);assertEquals(1,snapshot.opportunities.size)
        assertEquals(CrownActivityDecision.INSUFFICIENT_OPPORTUNITIES,CrownInactivityAssessment.assess(policy,snapshot,now,true))
        restarted.record(tenure,event("b",2))
        assertEquals(CrownActivityDecision.INACTIVITY_REVIEW,CrownInactivityAssessment.assess(policy,restarted.read(tenure)!!,now,true))
    }
    @Test fun `meaningful participation renews deadline but logging in cannot`() {
        val ledger=store();ledger.observe(tenure,"test",start);ledger.record(tenure,event("a",6,true))
        val now=start.plus(Duration.ofDays(8));ledger.observe(tenure,"test",now)
        assertEquals(CrownActivityDecision.WINDOW_OPEN,CrownInactivityAssessment.assess(policy,ledger.read(tenure)!!,now,true))
        assertEquals(start,ledger.read(tenure)!!.observedFrom)
    }
    @Test fun `changed evidence clocks holder and policy all hold for review`() {
        for(kind in listOf("event","clock","holder","policy")) {
            val t=tenure.copy(instance=UUID.randomUUID());val ledger=store();ledger.observe(t,"test",start)
            when(kind){
                "event"->{ledger.record(t,event("a",1));assertFalse(ledger.record(t,event("a",1,true)))}
                "clock"->ledger.observe(t,"test",start.minusSeconds(1))
                "holder"->ledger.observe(t.copy(holder=UUID.randomUUID()),"test",start)
                "policy"->ledger.observe(t,"changed",start)
            }
            assertEquals(CrownActivityDecision.HOLD_FOR_REVIEW,CrownInactivityAssessment.assess(policy,ledger.read(t)!!,start.plus(Duration.ofDays(9)),true),kind)
        }
    }
    @Test fun `unobserved or wrong holder cannot add evidence and missing coverage cannot confiscate`() {
        val ledger=store();assertFalse(ledger.record(tenure,event("a",1)))
        ledger.observe(tenure,"test",start)
        assertFalse(ledger.record(tenure.copy(holder=UUID.randomUUID()),event("a",1)))
        assertTrue(ledger.read(tenure)!!.opportunities.isEmpty())
        assertEquals(CrownActivityDecision.HOLD_FOR_REVIEW,CrownInactivityAssessment.assess(policy,ledger.read(tenure)!!,start,false))
        assertEquals(CrownActivityDecision.WINDOW_OPEN,CrownInactivityAssessment.assess(CrownInactivityPolicy(),ledger.read(tenure)!!,start,false))
    }
}
