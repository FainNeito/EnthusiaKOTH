package net.badgersmc.ek.infrastructure.persistence

import net.badgersmc.ek.application.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.io.TempDir
import org.sqlite.SQLiteDataSource
import java.io.File
import java.time.Instant
import java.util.UUID

class ProgressionPersistenceTest {
    @TempDir lateinit var directory:File
    private val at=Instant.parse("2026-10-08T12:00:00Z")
    private fun source()=SQLiteDataSource().apply { url="jdbc:sqlite:${File(directory,"progress.db")}" }
    private fun rule()=ChallengeRule("blade",1,1,1,1,120,"conqueror","signed_blade",true)
    private fun policy()=ProgressionPolicy(enabled=true,poolCents=101,challenges=listOf(rule()))
    private fun match(player:UUID,others:List<MatchContribution> = emptyList())=VerifiedMatch(UUID.randomUUID(),"hill","capture","SCHEDULED",at,
        "guild:${UUID.randomUUID()}",setOf("guild:enemy"),listOf(MatchContribution(player,"guild:win",180,true))+others,120,false,"activity proof")
    private fun accept(ds:SQLiteDataSource,m:VerifiedMatch) { SqlRewardProtectionStore(ds).also { it.init() }.reserve(m.eventId,m.arena,
        setOf(m.winner!!),m.opponents,m.at,RewardProtectionConfig(maximumRepeatedWins=0),"verified") }
    @Test fun `no authoritative acceptance yields no claims or challenge credit`() {
        val ds=source(); SqlRewardProtectionStore(ds).init(); val store=SqlProgressionStore(ds,::policy); store.init()
        val player=UUID.randomUUID(); store.complete(match(player))
        assertTrue(store.claims(player).isEmpty()); assertEquals(0,store.progress(player)!!["blade"])
    }
    @Test fun `exclusive ownership and all claims survive restart and duplicate completion`() {
        val ds=source(); val first=SqlProgressionStore(ds,::policy); first.init(); val p=UUID.randomUUID(); val m=match(p); accept(ds,m)
        first.complete(m); first.complete(m)
        assertEquals(3,first.claims(p).size); assertEquals(p,first.exclusiveOwner("blade"))
        val next=SqlProgressionStore(ds,::policy); next.init(); val other=UUID.randomUUID(); val later=match(other); accept(ds,later); next.complete(later)
        assertEquals(p,next.exclusiveOwner("blade")); assertEquals(0,next.claims(other).count { it.kind=="LORE" })
        assertEquals(100,next.progress(p)!!["blade"]); assertEquals(100,next.progress(other)!!["blade"])
    }
    @Test fun `one fixed pool is divided without online guild or account multiplication`() {
        val ds=source(); val store=SqlProgressionStore(ds,::policy); store.init(); val a=UUID.randomUUID(); val b=UUID.randomUUID(); val excluded=UUID.randomUUID()
        val m=match(a,listOf(MatchContribution(b,"guild:win",120,true),MatchContribution(excluded,"guild:win",500,false))); accept(ds,m); store.complete(m)
        assertEquals(101, (store.claims(a)+store.claims(b)).filter { it.kind=="MONEY" }.sumOf { it.amount })
        assertTrue(store.claims(excluded).isEmpty())
        assertEquals(51,store.claims(a).single { it.kind=="MONEY" }.amount)
    }
    @Test fun `admin private insufficient opposition and relation change never award`() {
        val ds=source(); val store=SqlProgressionStore(ds,::policy); store.init()
        val base=match(UUID.randomUUID())
        listOf(base.copy(source="ADMIN"),base.copy(source="PRIVATE_TEST"),base.copy(oppositionSeconds=119),base.copy(relationChanged=true)).forEach {
            val m=it.copy(eventId=UUID.randomUUID()); accept(ds,m); store.complete(m)
        }
        assertTrue(store.claims(base.contributions.single().player).isEmpty()); assertNull(store.exclusiveOwner("blade"))
    }
    @Test fun `package choice is owned frozen atomic and immutable`() {
        val ds=source(); var cfg=policy().copy(packageUnits=2,packages=listOf(RewardPackage("a",101,"parcel_a",2),RewardPackage("b",101,"parcel_b",2)))
        val store=SqlProgressionStore(ds,{cfg}); store.init(); val player=UUID.randomUUID(); val m=match(player); accept(ds,m); store.complete(m)
        val choice=store.claims(player).single { it.kind=="CHOICE" }
        cfg=policy()
        assertFalse(store.choose(choice.id,UUID.randomUUID(),"a")); assertFalse(store.choose(choice.id,player,"injected"))
        assertTrue(store.choose(choice.id,player,"b")); assertFalse(store.choose(choice.id,player,"a"))
        assertEquals(2,store.claims(player).count { it.value=="parcel_b" }); assertEquals(101,store.claims(player).single { it.kind=="MONEY" }.amount)
    }
    @Test fun `recovery reuses idempotent claim IDs but never replays uncertain money`() {
        val ds=source(); val store=SqlProgressionStore(ds,::policy); store.init(); val p=UUID.randomUUID(); val m=match(p); accept(ds,m); store.complete(m)
        val relic=store.claims(p).single { it.kind=="LORE" }; val money=store.claims(p).single { it.kind=="MONEY" }
        assertTrue(store.transition(relic.id,p,"PENDING","SENDING")); assertTrue(store.transition(money.id,p,"PENDING","REVIEW"))
        assertFalse(store.transition(money.id,p,"PENDING","REVIEW"))
        val restarted=SqlProgressionStore(ds,::policy); restarted.init(); restarted.recoverIdempotentClaims()
        assertEquals("PENDING",restarted.claims(p).single { it.id==relic.id }.status)
        assertEquals("REVIEW",restarted.claims(p).single { it.id==money.id }.status)
    }
    @Test fun `observed roster gate rejects unknown and recent membership history`() {
        val ds=source(); val cfg=policy().copy(minimumRosterAgeSeconds=3600); val store=SqlProgressionStore(ds,{cfg}); store.init()
        val unknown=UUID.randomUUID(); val recent=UUID.randomUUID(); val old=UUID.randomUUID()
        store.change(recent,UUID.randomUUID(),"JOIN",at.minusSeconds(3599)); store.change(old,UUID.randomUUID(),"JOIN",at.minusSeconds(3600))
        listOf(unknown,recent,old).forEach { val m=match(it); accept(ds,m); store.complete(m) }
        assertTrue(store.claims(unknown).isEmpty()); assertTrue(store.claims(recent).isEmpty()); assertFalse(store.claims(old).isEmpty())
    }
    @Test fun `repeated allied representatives count as a single opposing side across matches`() {
        val ds=source(); val cfg=policy().copy(challenges=listOf(rule().copy(opponents=2))); val store=SqlProgressionStore(ds,{cfg}); store.init()
        val p=UUID.randomUUID()
        listOf(setOf("guild:a,guild:b"),setOf("guild:b,guild:c")).forEach { sides -> val m=match(p).copy(opponents=sides); accept(ds,m); store.complete(m) }
        assertNull(store.exclusiveOwner("blade")); assertEquals(50,store.progress(p)!!["blade"])
        val m=match(p).copy(opponents=setOf("guild:unrelated")); accept(ds,m); store.complete(m); assertEquals(p,store.exclusiveOwner("blade"))
    }
    @Test fun `independent concurrent stores allocate only one lifetime owner`() {
        val ds=source(); val stores=listOf(SqlProgressionStore(ds,::policy),SqlProgressionStore(ds,::policy)); stores.forEach { it.init() }
        val matches=listOf(match(UUID.randomUUID()),match(UUID.randomUUID())); matches.forEach { accept(ds,it) }
        val executor=java.util.concurrent.Executors.newFixedThreadPool(2)
        try { stores.indices.map { i -> executor.submit { stores[i].complete(matches[i]) } }.forEach { it.get() } }
        finally { executor.shutdownNow() }
        assertEquals(1,matches.sumOf { stores[0].claims(it.contributions.single().player).count { c -> c.kind=="LORE" } })
    }
    @Test fun `disabled policy cannot award or expose active progress`() {
        val ds=source(); val store=SqlProgressionStore(ds,{ProgressionPolicy()}); store.init(); val m=match(UUID.randomUUID()); accept(ds,m); store.complete(m)
        assertTrue(store.history().isEmpty()); assertNull(store.progress(m.contributions.single().player))
    }
    @Test fun `blank exclusive templates cannot consume lifetime ownership`() {
        val ds=source(); val cfg=policy().copy(challenges=listOf(rule().copy(loreDefinition=""))); val store=SqlProgressionStore(ds,{cfg}); store.init()
        val p=UUID.randomUUID(); val m=match(p); accept(ds,m); store.complete(m)
        assertNull(store.exclusiveOwner("blade")); assertEquals(99,store.progress(p)!!["blade"])
    }
    @Test fun `staff reconciliation requires uncertain existing claim and cannot transfer ownership`() {
        val ds=source(); val store=SqlProgressionStore(ds,::policy); store.init(); val p=UUID.randomUUID(); val m=match(p); accept(ds,m); store.complete(m)
        val money=store.claims(p).single { it.kind=="MONEY" }; val relic=store.claims(p).single { it.kind=="LORE" }
        assertFalse(store.reconcile(money.id,"staff",false,"not reserved"))
        store.transition(money.id,p,"PENDING","REVIEW")
        assertThrows(IllegalArgumentException::class.java) { store.reconcile(money.id,"staff",false,"") }
        assertTrue(store.reconcile(money.id,"staff",false,"Provider confirms unpaid")); assertFalse(store.reconcile(relic.id,"staff",false,"cannot create relic"))
        assertEquals(p,store.exclusiveOwner("blade"))
    }
}
