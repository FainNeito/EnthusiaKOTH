package net.badgersmc.ek.infrastructure.persistence

import java.io.File
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executors
import net.badgersmc.ek.application.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.sqlite.SQLiteDataSource
import net.badgersmc.ek.domain.CrownEventPurpose

class CrownQuestCreditTest {
    @Test fun `discard acknowledgement rejects mismatched activation and is replayable`() {
        val ds=source();setup(ds);val events=SqlCrownEventStore(ds).also{it.init()}
        val recovery=SqlCrownRecoveryEvents(ds).also{it.init()};val match=UUID.randomUUID()
        val binding=RecoveryEventBinding("discard",UUID.randomUUID(),CrownTenure(UUID.randomUUID(),player,2))
        events.plan(CrownOccurrence(binding.occurrence,"hill",now,CrownEventPurpose.RECOVERY_CROWN_FINAL,rules.revision))
        assertTrue(recovery.assign(binding));assertTrue(events.start(binding.occurrence,match))
        assertFalse(events.acknowledgeDiscard(binding.occurrence,"hill",now,UUID.randomUUID()))
        recovery.retireEmpty();assertEquals(listOf(binding),recovery.active())
        assertTrue(events.acknowledgeDiscard(binding.occurrence,"hill",now,match))
        assertTrue(SqlCrownEventStore(ds).acknowledgeDiscard(binding.occurrence,"hill",now,match))
        recovery.retireEmpty();assertTrue(recovery.active().isEmpty())
    }
    @Test fun `planned delayed recovery remains bound across restart and admits only its original activation`() {
        val ds=source();setup(ds);val events=SqlCrownEventStore(ds).also{it.init()}
        val recovery=SqlCrownRecoveryEvents(ds).also{it.init()}
        val binding=RecoveryEventBinding("delayed",UUID.randomUUID(),CrownTenure(UUID.randomUUID(),player,2))
        events.plan(CrownOccurrence(binding.occurrence,"hill",now,CrownEventPurpose.RECOVERY_CROWN_FINAL,rules.revision))
        assertTrue(recovery.assign(binding))
        val restarted=SqlCrownRecoveryEvents(ds).also{it.init();it.retireEmpty()}
        assertEquals(listOf(binding),restarted.active())
        assertFalse(restarted.assign(binding.copy(occurrence="later")))
        val match=UUID.randomUUID();val resumedEvents=SqlCrownEventStore(ds).also{it.init()}
        assertTrue(resumedEvents.bindScheduledMatch(binding.occurrence,"hill",now,true,match))
        assertTrue(resumedEvents.bindScheduledMatch(binding.occurrence,"hill",now,true,match))
        assertFalse(resumedEvents.bindScheduledMatch(binding.occurrence,"hill",now,true,UUID.randomUUID()))
        assertFalse(resumedEvents.cancelPlanned(binding.occurrence))
        restarted.retireEmpty();assertEquals(listOf(binding),restarted.active())
    }
    @Test fun `malformed scheduled evidence cannot be silently excluded from observation`() {
        val ds=source();setup(ds);SqlCrownEventStore(ds).init()
        val recovery=SqlCrownRecoveryEvents(ds).also{it.init()};val ledger=SqlCrownActivityStore(ds).also{it.init()}
        val tenure=CrownTenure(UUID.randomUUID(),player,1);val from=now.minusSeconds(300)
        ledger.observe(tenure,rules.revision,from)
        ds.connection.use { c -> c.prepareStatement("INSERT INTO koth_crown_occurrences VALUES(?,?,?,?,?,?,?)").use { s ->
            s.setString(1,"malformed");s.setString(2,"hill");s.setString(3,"invalid-time")
            s.setString(4,"HOLDER_CHALLENGE");s.setString(5,rules.revision);s.setString(6,"CANCELLED");s.setString(7,null);s.executeUpdate()
        } }
        assertThrows(java.time.format.DateTimeParseException::class.java) { recovery.observe(tenure,rules.revision,from,now,60,ledger) }
        assertTrue(ledger.read(tenure)!!.opportunities.isEmpty())
    }
    @Test fun `started cancellation releases only its binding after restart`() {
        val ds=source();setup(ds);val events=SqlCrownEventStore(ds).also{it.init()}
        val recovery=SqlCrownRecoveryEvents(ds).also{it.init()};val match=UUID.randomUUID()
        val binding=RecoveryEventBinding("started-cancel",UUID.randomUUID(),CrownTenure(UUID.randomUUID(),player,2))
        events.plan(CrownOccurrence(binding.occurrence,"hill",now,CrownEventPurpose.RECOVERY_CROWN_FINAL,rules.revision))
        assertTrue(recovery.assign(binding));assertTrue(events.start(binding.occurrence,match))
        recovery.cancel(UUID.randomUUID());recovery.retireEmpty();assertEquals(listOf(binding),recovery.active())
        recovery.cancel(match)
        val restarted=SqlCrownRecoveryEvents(ds).also{it.init();it.retireEmpty()}
        assertTrue(restarted.active().isEmpty())
        assertTrue(restarted.assign(binding.copy(occurrence="later")))
    }
    @Test fun `completed event without winner releases its binding but missing result holds it`() {
        val ds=source();setup(ds);val events=SqlCrownEventStore(ds).also{it.init()}
        val recovery=SqlCrownRecoveryEvents(ds).also{it.init()};val match=UUID.randomUUID()
        val binding=RecoveryEventBinding("no-winner",UUID.randomUUID(),CrownTenure(UUID.randomUUID(),player,2))
        events.plan(CrownOccurrence(binding.occurrence,"hill",now,CrownEventPurpose.RECOVERY_CROWN_FINAL,rules.revision))
        assertTrue(recovery.assign(binding));assertTrue(events.start(binding.occurrence,match));recovery.finish(match,events)
        recovery.retireEmpty();assertEquals(listOf(binding),recovery.active())
        ds.connection.use { c -> c.prepareStatement("INSERT INTO koth_matches VALUES(?,?,?,?,?,?,?)").use { s ->
            s.setString(1,match.toString());s.setString(2,"hill");s.setString(3,"capture");s.setString(4,"SCHEDULED")
            s.setLong(5,now.toEpochMilli());s.setString(6,null);s.setString(7,"no winner");s.executeUpdate()
        } }
        val restarted=SqlCrownRecoveryEvents(ds).also{it.init();it.retireEmpty()}
        assertTrue(restarted.active().isEmpty());assertTrue(restarted.assign(binding.copy(occurrence="later")))
    }
    @Test fun `observation refuses more than one thousand current events`() {
        val ds=source();setup(ds);SqlCrownEventStore(ds).init()
        val recovery=SqlCrownRecoveryEvents(ds).also{it.init()};val ledger=SqlCrownActivityStore(ds).also{it.init()}
        val tenure=CrownTenure(UUID.randomUUID(),player,1);val from=now.minusSeconds(300)
        ledger.observe(tenure,rules.revision,from)
        ds.connection.use { c -> c.prepareStatement("INSERT INTO koth_crown_occurrences VALUES(?,?,?,?,?,?,?)").use { s ->
            repeat(1001) { i ->
                s.setString(1,"current-$i");s.setString(2,"hill");s.setString(3,now.toString())
                s.setString(4,"HOLDER_CHALLENGE");s.setString(5,rules.revision);s.setString(6,"CANCELLED");s.setString(7,null);s.addBatch()
            };s.executeBatch()
        } }
        assertFalse(recovery.observe(tenure,rules.revision,from,now,60,ledger))
        assertTrue(ledger.read(tenure)!!.opportunities.isEmpty())
    }
    @Test fun `old challenges do not exhaust the current observation bound`() {
        val ds=source();setup(ds);val events=SqlCrownEventStore(ds).also{it.init()}
        val recovery=SqlCrownRecoveryEvents(ds).also{it.init()};val ledger=SqlCrownActivityStore(ds).also{it.init()}
        val tenure=CrownTenure(UUID.randomUUID(),player,1);val from=now.minusSeconds(300)
        ledger.observe(tenure,rules.revision,from)
        ds.connection.use { c -> c.prepareStatement("INSERT INTO koth_crown_occurrences VALUES(?,?,?,?,?,?,?)").use { s ->
            repeat(1001) { i ->
                s.setString(1,"old-$i");s.setString(2,"hill");s.setString(3,now.minusSeconds(86400).toString())
                s.setString(4,"HOLDER_CHALLENGE");s.setString(5,rules.revision);s.setString(6,"CANCELLED");s.setString(7,null);s.addBatch()
            };s.executeBatch()
        } }
        assertTrue(recovery.observe(tenure,rules.revision,from,now,60,ledger))
        events.plan(CrownOccurrence("unresolved","hill",now,CrownEventPurpose.HOLDER_CHALLENGE,rules.revision))
        assertFalse(recovery.observe(tenure,rules.revision,from,now,60,ledger))
    }
    @Test fun `recovery final requires announcement consent policy and unchanged protected winner`() {
        val ds=source();setup(ds)
        val events=SqlCrownEventStore(ds).also{it.init()};val recovery=SqlCrownRecoveryEvents(ds).also{it.init()}
        val m=match();seed(ds,m)
        val binding=RecoveryEventBinding("recover",UUID.randomUUID(),CrownTenure(UUID.randomUUID(),UUID.randomUUID(),2))
        events.plan(CrownOccurrence("recover","hill",now,CrownEventPurpose.RECOVERY_CROWN_FINAL,rules.revision))
        assertTrue(recovery.assign(binding));assertFalse(recovery.assign(binding.copy(occurrence="other")))
        assertFalse(events.acceptHelmetSacrifice("recover",rules.revision,player))
        assertTrue(events.acceptRecoveryBinding("recover",rules.revision,player))
        assertTrue(events.bindScheduledMatch("recover","hill",now,true,m.eventId));recovery.finish(m.eventId,events)
        assertNull(recovery.winner(binding,rules.revision,null,false,60))
        recovery.announced("recover",rules.revision,now.minusSeconds(120))
        assertNull(recovery.winner(binding,rules.revision,null,null,60))
        assertNull(recovery.winner(binding,rules.revision,CrownQuestRequirements(true,2,240,2),true,60))
        val winner=recovery.winner(binding,rules.revision,null,false,60)
        assertEquals(RecoveryEventWinner(binding,m.eventId,player),winner)
        assertEquals(winner,SqlCrownRecoveryEvents(ds).winner(binding,rules.revision,null,false,60))
        ds.connection.use { c -> c.prepareStatement("UPDATE koth_match_decisions SET status='HELD' WHERE event=?").use { s ->
            s.setString(1,m.eventId.toString());s.executeUpdate()
        } }
        assertNull(recovery.winner(binding,rules.revision,null,false,60))
    }
    @Test fun `cancelled recovery slot retains custody eligibility for a later event`() {
        val ds=source();setup(ds);val events=SqlCrownEventStore(ds).also{it.init()};val recovery=SqlCrownRecoveryEvents(ds).also{it.init()}
        val binding=RecoveryEventBinding("cancel",UUID.randomUUID(),CrownTenure(UUID.randomUUID(),player,2))
        events.plan(CrownOccurrence("cancel","hill",now,CrownEventPurpose.RECOVERY_CROWN_FINAL,rules.revision))
        assertTrue(recovery.assign(binding));assertTrue(events.cancelPlanned("cancel"));recovery.retireEmpty()
        assertTrue(recovery.active().isEmpty());assertTrue(recovery.assign(binding.copy(occurrence="next")))
    }
    @Test fun `activity observations require announced completed protected special events and deduplicate`() {
        val ds=source();setup(ds);val events=SqlCrownEventStore(ds).also{it.init()};val recovery=SqlCrownRecoveryEvents(ds).also{it.init()}
        val ledger=SqlCrownActivityStore(ds).also{it.init()};val tenure=CrownTenure(UUID.randomUUID(),player,1)
        ledger.observe(tenure,rules.revision,now.minusSeconds(300))
        val m=match();seed(ds,m)
        events.plan(CrownOccurrence("activity","hill",now,CrownEventPurpose.HOLDER_CHALLENGE,rules.revision))
        events.bindScheduledMatch("activity","hill",now,true,m.eventId)
        assertFalse(recovery.observe(tenure,rules.revision,now.minusSeconds(300),now,60,ledger))
        recovery.announced("activity",rules.revision,now.minusSeconds(120));recovery.finish(m.eventId,events)
        repeat(2){assertTrue(recovery.observe(tenure,rules.revision,now.minusSeconds(300),now,60,ledger))}
        assertEquals(1,ledger.read(tenure)!!.opportunities.size);assertTrue(ledger.read(tenure)!!.opportunities.single().participationVerified)
        ds.connection.use { c -> c.createStatement().use {it.executeUpdate("UPDATE koth_match_players SET seconds=1")} }
        assertFalse(recovery.observe(tenure,rules.revision,now.minusSeconds(300),now,60,ledger))
    }
    @TempDir lateinit var directory: File
    private fun source() = SQLiteDataSource().apply { url = "jdbc:sqlite:${File(directory, "quest.db")}" }
    private val now = Instant.parse("2026-11-04T17:00:00Z")
    private val player = UUID.randomUUID()
    private val rules = CrownCreditRules(true, now.minusSeconds(1), "test-v1")
    private val policy = ProgressionPolicy(enabled = true, integrity = IntegrityPolicy(
        requireContest = true, holdSuspicious = true, requireReadiness = true, acceptedArenas = setOf("hill"),
    ))
    private fun match() = VerifiedMatch(UUID.randomUUID(), "hill", "capture", "SCHEDULED", now, "solo:$player",
        setOf("solo:${UUID.randomUUID()}"), listOf(MatchContribution(player, "solo:$player", 120, true)),
        120, false, "test", ContestEvidence(controlChanges = 1))

    private fun setup(ds: SQLiteDataSource): SqlCrownQuestCredits {
        SqlRewardProtectionStore(ds).init(); SqlProgressionStore(ds) { policy }.init()
        return SqlCrownQuestCredits(ds).also { it.init() }
    }

    private fun seed(ds: SQLiteDataSource, m: VerifiedMatch, p: ProgressionPolicy = policy, status: String = "APPROVED", accepted: Int = 1) {
        ds.connection.use { c ->
            c.prepareStatement("INSERT INTO koth_matches VALUES(?,?,?,?,?,?,?)").use { s ->
                s.setString(1,m.eventId.toString());s.setString(2,m.arena);s.setString(3,m.family);s.setString(4,m.source)
                s.setLong(5,m.at.toEpochMilli());s.setString(6,m.winner);s.setString(7,m.detail);s.executeUpdate()
            }
            c.prepareStatement("INSERT INTO koth_match_decisions VALUES(?,?,?,?,?)").use { s ->
                s.setString(1,m.eventId.toString());s.setString(2,status);s.setString(3,MatchSnapshotCodec.encode(m,p))
                s.setString(4,"");s.setString(5,m.arenaIdentity);s.executeUpdate()
            }
            c.prepareStatement("INSERT INTO koth_reward_audit VALUES(?,?,?,?,?)").use { s ->
                s.setString(1,m.eventId.toString());s.setString(2,m.arena);s.setLong(3,m.at.toEpochMilli());s.setInt(4,accepted);s.setString(5,"");s.executeUpdate()
            }
            c.prepareStatement("INSERT INTO koth_match_players VALUES(?,?,?,?,?)").use { s ->
                m.contributions.forEach { x -> s.setString(1,m.eventId.toString());s.setString(2,x.player.toString());s.setString(3,x.guild)
                    s.setLong(4,x.seconds);s.setInt(5,if(x.eligible) 1 else 0);s.executeUpdate() }
            }
        }
    }

    @Test fun `first award retries keep one winner and revoked evidence stops dispatch`() {
        val ds = source()
        val credits = setup(ds)
        val prior = match().copy(at = now.minusSeconds(60))
        seed(ds, prior)

        val events = SqlCrownEventStore(ds)
        events.init()
        events.processProtectedResult(prior.eventId, rules.copy(cutover = now.minusSeconds(120)),
            CrownQuestRequirements(true, 1, 120, 1), credits)
        fun final(id: String, purpose: CrownEventPurpose): UUID {
            val m = match()
            seed(ds, m)
            events.plan(CrownOccurrence(id, "hill", now, purpose, "event-v1"))
            if (purpose == CrownEventPurpose.FIRST_CROWN_FINAL) {
                assertFalse(events.acceptHelmetSacrifice(id, "other-revision", player))
                assertTrue(events.acceptHelmetSacrifice(id, "event-v1", UUID.randomUUID()))
                if (id != "no-consent") assertTrue(events.acceptHelmetSacrifice(id, "event-v1", player))
            }
            assertTrue(events.start(id, m.eventId))
            return m.eventId
        }
        val recovery = final("recovery", CrownEventPurpose.RECOVERY_CROWN_FINAL)
        assertTrue(events.finish("recovery", recovery, false))
        val ids = listOf("first", "second")
        val matches = ids.associateWith { final(it, CrownEventPurpose.FIRST_CROWN_FINAL) }
        val requirements = CrownQuestRequirements(true, 1, 120, 1)
        val unacknowledged = final("no-consent", CrownEventPurpose.FIRST_CROWN_FINAL)
        assertFalse(events.acceptHelmetSacrifice("no-consent", "event-v1", player))
        assertNull(events.finishFirstFinal("no-consent", unacknowledged, rules.revision, requirements))
        val store = SqlCrownFirstAward(ds)
        assertNull(events.finishFirstFinal("first", UUID.randomUUID(), rules.revision, requirements))
        assertNull(events.finishFirstFinal("first", matches.getValue("first"), rules.revision, requirements.copy(wins = 2)))
        assertNull(store.prepare("recovery", rules.revision, requirements))
        assertNull(store.prepare("first", rules.revision, requirements.copy(wins = 2)))
        assertNull(store.prepare("second", rules.revision, requirements.copy(enabled = false)))
        ds.connection.use { c -> c.createStatement().use { s ->
            s.executeUpdate("INSERT INTO koth_exclusive_owners VALUES('sovereign_crown','legacy','legacy',0)")
            assertNull(store.prepare("first", rules.revision, requirements))
            s.executeUpdate("DELETE FROM koth_exclusive_owners")
        } }
        val pool = Executors.newFixedThreadPool(2)
        val winner = try {
            val gate = java.util.concurrent.CountDownLatch(1)
            val calls = ids.map { id -> pool.submit<CrownFirstAwardIntent?> {
                gate.await()
                SqlCrownEventStore(ds).processProtectedResult(matches.getValue(id), rules, requirements, credits)
            } }
            gate.countDown()
            calls.mapNotNull { it.get() }.single()
        } finally { pool.shutdownNow() }
        assertEquals(matches[winner.occurrence], winner.match)
        assertEquals(player, winner.winner)
        val reopened = SqlCrownFirstAward(source())
        assertEquals(winner, reopened.prepare(winner.occurrence, rules.revision, requirements))
        ds.connection.use { c -> c.prepareStatement("UPDATE koth_match_decisions SET status='HELD' WHERE event=?").use {
            it.setString(1, prior.eventId.toString())
            it.executeUpdate()
        } }
        assertNull(reopened.prepare(winner.occurrence, rules.revision, requirements))
        ds.connection.use { c -> c.createStatement().use { s ->
            s.executeQuery("SELECT COUNT(*) FROM koth_crown_first_award").use { r -> assertTrue(r.next()); assertEquals(1, r.getInt(1)) }
        } }
    }
    @Test fun `eligible credit is durable idempotent and cannot be revised`() {
        val ds=source();val store=setup(ds);val m=match();seed(ds,m)
        assertEquals(CrownCreditResult.DISABLED,store.credit(m.eventId,rules.copy(enabled=false)))
        assertEquals(CrownCreditResult.CREDITED,store.credit(m.eventId,rules))
        val restarted=SqlCrownQuestCredits(ds);restarted.init()
        assertEquals(CrownCreditResult.ALREADY_CREDITED,restarted.credit(m.eventId,rules))
        assertEquals(CrownCreditResult.CONFLICT,restarted.credit(m.eventId,rules.copy(revision="test-v2")))
        assertEquals(CrownQuestTotals(1,120,1),restarted.totals(player,"test-v1"))
        assertEquals(CrownQuestTotals(0,0,0),restarted.totals(player,"test-v2"))
    }

    @Test fun `guild admin uncontested held rejected and old results cannot credit`() {
        val ds=source();val store=setup(ds)
        val cases=listOf(match().copy(winner="guild:${UUID.randomUUID()}"),match().copy(source="ADMIN"),
            match().copy(evidence=ContestEvidence()), match().copy(relationChanged=true),
            match().copy(opponents=emptySet()), match().copy(at=now.minusSeconds(2)))
        cases.forEach { seed(ds,it); assertEquals(CrownCreditResult.UNAVAILABLE_OR_UNQUALIFIED,store.credit(it.eventId,rules)) }
        listOf("HELD","REJECTED").forEach { status -> val m=match();seed(ds,m,status=status);assertEquals(CrownCreditResult.UNAVAILABLE_OR_UNQUALIFIED,store.credit(m.eventId,rules)) }
        val m=match();seed(ds,m,accepted=0);assertEquals(CrownCreditResult.UNAVAILABLE_OR_UNQUALIFIED,store.credit(m.eventId,rules))
        assertEquals(CrownQuestTotals(0,0,0),store.totals(player,"test-v1"))
    }

    @Test fun `disabled contest and readiness or unaccepted arena cannot generate credit`() {
        val ds=source();val store=setup(ds)
        listOf(policy.copy(integrity=policy.integrity.copy(requireContest=false)),
            policy.copy(integrity=policy.integrity.copy(requireReadiness=false)),
            policy.copy(integrity=policy.integrity.copy(holdSuspicious=false)),
            policy.copy(integrity=policy.integrity.copy(acceptedArenas=emptySet()))).forEach { p ->
            val m=match();seed(ds,m,p);assertEquals(CrownCreditResult.UNAVAILABLE_OR_UNQUALIFIED,store.credit(m.eventId,rules))
        }
    }

    @Test fun `revoked ledger removes previously recorded progress`() {
        val ds=source();val store=setup(ds);val m=match();seed(ds,m);assertEquals(CrownCreditResult.CREDITED,store.credit(m.eventId,rules))
        ds.connection.use { c -> c.prepareStatement("UPDATE koth_match_decisions SET status='REJECTED' WHERE event=?").use { s -> s.setString(1,m.eventId.toString());s.executeUpdate() } }
        assertEquals(CrownQuestTotals(0,0,0),store.totals(player,"test-v1"))
    }

    @Test fun `simultaneous callbacks cannot duplicate progress`() {
        val ds=source();val store=setup(ds);val m=match();seed(ds,m);val pool=Executors.newFixedThreadPool(2)
        try {
            val results=(1..2).map { pool.submit<CrownCreditResult> { SqlCrownQuestCredits(ds).credit(m.eventId,rules) } }.map { it.get() }
            assertEquals(1,results.count { it==CrownCreditResult.CREDITED });assertEquals(1,results.count { it==CrownCreditResult.ALREADY_CREDITED })
            assertEquals(CrownQuestTotals(1,120,1),store.totals(player,"test-v1"))
        } finally { pool.shutdownNow() }
    }

    @Test fun `restart reconciliation pages tied timestamps and revisits later approved matches`() {
        val ds=source();val store=setup(ds);val first=match();val second=match();val held=match()
        seed(ds,first);seed(ds,second);seed(ds,held,status="HELD")
        val page=store.reconcilePage(rules,limit=1)
        assertEquals(1,page.results.size);assertNotNull(page.next)
        val restarted=SqlCrownQuestCredits(ds);restarted.init()
        val next=restarted.reconcilePage(rules,page.next,limit=1)
        assertEquals(1,next.results.size);assertNull(next.next)
        assertEquals(setOf(first.eventId,second.eventId),page.results.keys+next.results.keys)
        assertEquals(CrownQuestTotals(2,240,1),restarted.totals(player,"test-v1"))
        ds.connection.use { c -> c.prepareStatement("UPDATE koth_match_decisions SET status='APPROVED' WHERE event=?").use { s -> s.setString(1,held.eventId.toString());s.executeUpdate() } }
        val rescan=restarted.reconcilePage(rules,limit=1)
        assertEquals(CrownCreditResult.CREDITED,rescan.results[held.eventId])
        assertEquals(CrownQuestTotals(3,360,1),restarted.totals(player,"test-v1"))
        assertTrue(restarted.reconcilePage(rules.copy(enabled=false)).results.isEmpty())
    }

    @Test fun `reconciliation surfaces revision conflicts rather than hiding them`() {
        val ds=source();val store=setup(ds);val m=match();seed(ds,m)
        assertEquals(CrownCreditResult.CREDITED,store.credit(m.eventId,rules))
        assertEquals(CrownCreditResult.CONFLICT,store.reconcilePage(rules.copy(revision="test-v2")).results[m.eventId])
    }

    @Test fun `final observation binds approved match winner and quest progress across restart`() {
        val ds = source(); val credits = setup(ds); val prior = match().copy(at = now.minusSeconds(60)); seed(ds, prior)
        val match = match(); seed(ds, match)
        val earlierRules = rules.copy(cutover = now.minusSeconds(120))
        assertEquals(CrownCreditResult.CREDITED, credits.credit(prior.eventId, earlierRules))
        assertEquals(CrownCreditResult.CREDITED, credits.credit(match.eventId, rules))
        val events = SqlCrownEventStore(ds); events.init()
        val occurrence = CrownOccurrence("final-1", "hill", now, CrownEventPurpose.FIRST_CROWN_FINAL, "event-v1")
        events.plan(occurrence); events.start(occurrence.occurrenceId, match.eventId)
        val reader = SqlCrownFinalObservation(ds)
        assertNull(reader.read(occurrence.occurrenceId, rules.revision))
        assertTrue(events.finish(occurrence.occurrenceId, match.eventId, false))
        assertEquals(CrownFinalObservation("final-1", match.eventId, player, CrownEventPurpose.FIRST_CROWN_FINAL,
            rules.revision, CrownQuestTotals(1, 120, 1)), SqlCrownFinalObservation(ds).read("final-1", rules.revision))
        var checks = 0
        val preflight = SqlCrownFinalPreflight(ds, object : CrownCustodyCapabilityPort {
            override fun inspect(): CustodyCapability { checks++; return CustodyCapability.UNSUPPORTED }
        })
        val requirements = CrownQuestRequirements(true, 1, 120, 1)
        assertEquals(CrownFinalPreflightResult.DISABLED, preflight.check("final-1", rules.revision, requirements.copy(enabled = false)))
        assertEquals(CrownFinalPreflightResult.QUEST_INCOMPLETE, preflight.check("final-1", rules.revision, requirements.copy(wins = 2)))
        assertEquals(0, checks)
        assertEquals(CrownFinalPreflightResult.PROVIDER_UNSUPPORTED, preflight.check("final-1", rules.revision, requirements))
        assertEquals(1, checks)
        val failing = SqlCrownFinalPreflight(ds, object : CrownCustodyCapabilityPort {
            override fun inspect(): CustodyCapability = error("provider failure")
        })
        assertEquals(CrownFinalPreflightResult.PROVIDER_ERROR, failing.check("final-1", rules.revision, requirements))
        ds.connection.use { c -> c.prepareStatement("UPDATE koth_match_decisions SET status='HELD' WHERE event=?").use { s ->
            s.setString(1, match.eventId.toString()); s.executeUpdate()
        } }
        assertNull(reader.read("final-1", rules.revision))
        assertEquals(CrownFinalPreflightResult.UNAVAILABLE, preflight.check("final-1", rules.revision, requirements))
        assertEquals(1, checks)
    }

    @Test fun `final observation rejects manual and early matches`() {
        val ds = source(); setup(ds); val events = SqlCrownEventStore(ds); events.init()
        (listOf("PLAYER_COMMAND", "GUI", "FLARE", "ADMIN").map { match().copy(source = it) } +
            match().copy(at = now.minusSeconds(1))).forEachIndexed { index, m ->
            seed(ds, m)
            val id = "unscheduled-$index"
            events.plan(CrownOccurrence(id, "hill", now, CrownEventPurpose.FIRST_CROWN_FINAL, "event-v1"))
            assertTrue(events.start(id, m.eventId)); assertTrue(events.finish(id, m.eventId, false))
            assertNull(SqlCrownFinalObservation(ds).read(id, rules.revision))
        }
    }

    @Test fun `final observation rejects mismatched snapshot and arena`() {
        val ds = source(); setup(ds); val match = match(); seed(ds, match)
        val events = SqlCrownEventStore(ds); events.init()
        events.plan(CrownOccurrence("final-2", "other", now, CrownEventPurpose.RECOVERY_CROWN_FINAL, "event-v1"))
        events.start("final-2", match.eventId); events.finish("final-2", match.eventId, false)
        assertNull(SqlCrownFinalObservation(ds).read("final-2", rules.revision))
        ds.connection.use { c -> c.prepareStatement("UPDATE koth_matches SET winner=? WHERE id=?").use { s ->
            s.setString(1, "solo:${UUID.randomUUID()}"); s.setString(2, match.eventId.toString()); s.executeUpdate()
        }; c.createStatement().use { it.executeUpdate("UPDATE koth_crown_occurrences SET arena_id='hill' WHERE occurrence_id='final-2'") } }
        assertNull(SqlCrownFinalObservation(ds).read("final-2", rules.revision))
    }

    @Test fun `final observation rejects approved but unprotected or uncontested results`() {
        val ds = source(); setup(ds); val events = SqlCrownEventStore(ds); events.init()
        val cases = listOf(
            match().copy(evidence = ContestEvidence()) to policy,
            match().copy(relationChanged = true) to policy,
            match().copy(opponents = emptySet()) to policy,
            match() to policy.copy(integrity = policy.integrity.copy(requireContest = false)),
            match() to policy.copy(integrity = policy.integrity.copy(requireReadiness = false)),
        )
        cases.forEachIndexed { i, (m, p) ->
            seed(ds, m, p)
            val id = "unsafe-$i"
            events.plan(CrownOccurrence(id, "hill", now, CrownEventPurpose.FIRST_CROWN_FINAL, "event-v1"))
            events.start(id, m.eventId); events.finish(id, m.eventId, false)
            assertNull(SqlCrownFinalObservation(ds).read(id, rules.revision))
        }
    }

    @Test fun `final observation rejects a changed winning contribution`() {
        val ds = source(); setup(ds); val m = match(); seed(ds, m)
        val events = SqlCrownEventStore(ds); events.init()
        events.plan(CrownOccurrence("final-changed", "hill", now, CrownEventPurpose.FIRST_CROWN_FINAL, "event-v1"))
        events.start("final-changed", m.eventId); events.finish("final-changed", m.eventId, false)
        ds.connection.use { c -> c.prepareStatement("UPDATE koth_match_players SET seconds=seconds+1 WHERE event=? AND player=?").use { s ->
            s.setString(1, m.eventId.toString()); s.setString(2, player.toString()); s.executeUpdate()
        } }
        assertNull(SqlCrownFinalObservation(ds).read("final-changed", rules.revision))
    }
}
