package net.badgersmc.ek.infrastructure.persistence

import net.badgersmc.ek.application.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.io.TempDir
import org.sqlite.SQLiteDataSource
import java.io.File
import java.time.Instant
import java.util.UUID

class MatchIntegrityPersistenceTest {
    @TempDir lateinit var dir:File
    private val player=UUID.randomUUID()
    private val at=Instant.parse("2026-10-09T12:00:00Z")
    private fun source()=SQLiteDataSource().apply { url="jdbc:sqlite:${File(dir,"integrity.db")}" }
    private fun policy(i:IntegrityPolicy=IntegrityPolicy())=ProgressionPolicy(enabled=true,poolCents=101,integrity=i,
        challenges=listOf(ChallengeRule("test",1,1,1,1,120)))
    private fun match(winner:String="guild:a",enemy:String="guild:b",time:Instant=at)=VerifiedMatch(UUID.randomUUID(),"hill","capture","SCHEDULED",time,
        winner,setOf(enemy),listOf(MatchContribution(player,winner,180,true)),120,false,"private audit of other accounts",
        winnerSide=setOf(winner))
    private fun accept(ds:SQLiteDataSource,m:VerifiedMatch) {
        SqlRewardProtectionStore(ds).also { it.init() }.reserve(m.eventId,m.arena,m.winnerSide,m.opponents,m.at,RewardProtectionConfig(maximumRepeatedWins=0),"proof")
    }
    private fun complete(ds:SQLiteDataSource,s:SqlProgressionStore,m:VerifiedMatch) { accept(ds,m); s.complete(m) }

    @Test fun `held snapshots survive restart and approval awards once using frozen policy`() {
        val ds=source(); var cfg=policy(IntegrityPolicy(holdSuspicious=true))
        val store=SqlProgressionStore(ds,{cfg}); store.init(); val m=match(); complete(ds,store,m)
        assertTrue(store.claims(player).isEmpty()); assertEquals(0,store.progress(player)!!["test"])
        cfg=cfg.copy(poolCents=9000,minimumScoringSeconds=9000)
        val restart=SqlProgressionStore(ds,{cfg}); restart.init()
        assertEquals("HELD",restart.results(player).single().status)
        assertTrue(restart.reviewMatch(m.eventId.toString(),"staff",true,"reviewed actual contest"))
        assertFalse(store.reviewMatch(m.eventId.toString(),"staff",true,"duplicate"))
        assertEquals(101,restart.claims(player).single().amount); assertEquals(100,restart.progress(player)!!["test"])
        assertTrue(restart.report(m.eventId.toString()).any { "reviewed actual contest" in it })
    }
    @Test fun `rejection cannot later approve and contest rejection cannot become a hold`() {
        val ds=source(); val cfg=policy(IntegrityPolicy(holdSuspicious=true)); val store=SqlProgressionStore(ds,{cfg}); store.init()
        val m=match(); complete(ds,store,m)
        assertTrue(store.reviewMatch(m.eventId.toString(),"staff",false,"passive opponents"))
        assertFalse(store.reviewMatch(m.eventId.toString(),"staff",true,"override")); assertTrue(store.claims(player).isEmpty())
        val strict=SqlProgressionStore(ds,{cfg.copy(integrity=cfg.integrity.copy(requireContest=true))}); val m2=match()
        complete(ds,strict,m2); assertEquals("REJECTED",strict.results(player).first().status)
        assertFalse(strict.reviewMatch(m2.eventId.toString(),"staff",true,"override rejected"))
    }
    @Test fun `player and shared allied side budgets clip across arenas and survive restart`() {
        val ds=source(); val cfg=policy(IntegrityPolicy(playerDailyCents=150,sideDailyCents=120))
        val store=SqlProgressionStore(ds,{cfg}); store.init(); complete(ds,store,match())
        val restarted=SqlProgressionStore(ds,{cfg}); restarted.init()
        complete(ds,restarted,match("guild:ally","guild:c").copy(arena="other",winnerSide=setOf("guild:a","guild:ally")))
        assertEquals(120,restarted.claims(player).sumOf { it.amount })
        complete(ds,restarted,match("guild:ally","guild:d",at.plusSeconds(86400)).copy(winnerSide=setOf("guild:a","guild:ally")))
        assertEquals(221,restarted.claims(player).sumOf { it.amount })
    }
    @Test fun `item reservations count before choice and caps do not block challenge credit`() {
        val ds=source(); val cfg=policy(IntegrityPolicy(playerDailyUnits=1)).copy(packageUnits=2,packages=listOf(RewardPackage("box",101,"item",2)))
        val s=SqlProgressionStore(ds,{cfg}); s.init(); val m=match(); complete(ds,s,m); complete(ds,s,match())
        val choices=s.claims(player); choices.forEach { assertTrue(s.choose(it.id,player,"box")) }
        assertEquals(1,s.claims(player).count { it.kind=="LORE" }); assertEquals(100,s.progress(player)!!["test"])
    }
    @Test fun `stable geometry merges copies renames and historical challenge arena totals`() {
        val ds=source(); val cfg=policy().copy(challenges=listOf(ChallengeRule("test",2,1,2,1,120)))
        val s=SqlProgressionStore(ds,{cfg}); s.init(); assertEquals("hill",s.arenaIdentity("hill","world|bounds"))
        complete(ds,s,match()); assertEquals("hill",s.arenaIdentity("renamed","world|bounds"))
        complete(ds,s,match().copy(arena="renamed",arenaIdentity="hill"))
        assertEquals(50,s.progress(player)!!["test"])
        assertEquals("hill",s.arenaIdentity("renamed","new bounds")); assertEquals("hill",s.arenaIdentity("copy","new bounds"))
    }
    @Test fun `patterns match opposing sides not aliases of one allied winner`() {
        val ds=source(); val cfg=policy(); val s=SqlProgressionStore(ds,{cfg}); s.init()
        repeat(2) { complete(ds,s,match(enemy="guild:enemy$it").copy(winnerSide=setOf("guild:a","guild:ally"))) }
        val unrelated=match(enemy="guild:new").copy(winnerSide=setOf("guild:a","guild:ally")); complete(ds,s,unrelated)
        assertFalse(s.report(unrelated.eventId.toString()).first().contains("REPEATED_SIDES"))
        repeat(2) { complete(ds,s,match()) }
        val swapped=match("guild:b","guild:a"); complete(ds,s,swapped)
        assertTrue(s.report(swapped.eventId.toString()).first().contains("ALTERNATING_WINNERS"))
    }
    @Test fun `results expose only the requesting players records and safe explanations`() {
        val ds=source(); val s=SqlProgressionStore(ds,{policy()}); s.init(); complete(ds,s,match())
        assertTrue(s.results(UUID.randomUUID()).isEmpty()); assertFalse(s.results(player).single().detail.contains("private audit"))
    }
    @Test fun `admin and private tests do not seed public win trading patterns`() {
        val ds=source(); val s=SqlProgressionStore(ds,{policy()}); s.init()
        for (origin in listOf("ADMIN","PRIVATE_TEST")) complete(ds,s,match("guild:test","guild:enemy").copy(source=origin))
        val public=match(); complete(ds,s,public)
        assertFalse(s.report(public.eventId.toString()).first().contains("RECURRING_ACCOUNTS_DIFFERENT_TEAMS"))
    }
    @Test fun `snapshot codec round trips values and fails on corrupt data`() {
        val m=match(); val p=policy(); assertEquals(m to p,MatchSnapshotCodec.decode(MatchSnapshotCodec.encode(m,p)))
        assertThrows(Exception::class.java) { MatchSnapshotCodec.decode("corrupt") }
        assertThrows(Exception::class.java) { MatchSnapshotCodec.decode(MatchSnapshotCodec.encode(m,p)+"AAAA") }
    }
    @Test fun `approval failure rolls back status eligibility and claims`() {
        val ds=source(); val cfg=policy(IntegrityPolicy(holdSuspicious=true)); val s=SqlProgressionStore(ds,{cfg}); s.init()
        val m=match(); complete(ds,s,m)
        ds.connection.use { c -> c.createStatement().use { it.execute("CREATE TRIGGER fail_claim BEFORE INSERT ON koth_claims BEGIN SELECT RAISE(ABORT,'storage failure'); END") } }
        assertThrows(Exception::class.java) { s.reviewMatch(m.eventId.toString(),"staff",true,"review") }
        assertEquals("HELD",s.results(player).single().status); assertFalse(s.results(player).single().qualifying)
        assertTrue(s.claims(player).isEmpty()); assertEquals(0,s.progress(player)!!["test"])
        ds.connection.use { c -> c.createStatement().use { it.execute("DROP TRIGGER fail_claim") } }
        assertTrue(s.reviewMatch(m.eventId.toString(),"staff",true,"retry after repair"))
    }
    @Test fun `two independent stores can only approve a held match once`() {
        val ds=source(); val cfg=policy(IntegrityPolicy(holdSuspicious=true)); val a=SqlProgressionStore(ds,{cfg}); a.init()
        val b=SqlProgressionStore(ds,{cfg}); b.init(); val m=match(); complete(ds,a,m)
        val pool=java.util.concurrent.Executors.newFixedThreadPool(2)
        try {
            val gate=java.util.concurrent.CountDownLatch(1)
            val futures=listOf(a,b).map { store -> pool.submit<Boolean> { gate.await(); store.reviewMatch(m.eventId.toString(),"staff",true,"parallel review") } }
            gate.countDown(); assertEquals(1,futures.count { it.get(10,java.util.concurrent.TimeUnit.SECONDS) })
            assertEquals(101,a.claims(player).sumOf { it.amount })
        } finally { pool.shutdownNow() }
    }
}
