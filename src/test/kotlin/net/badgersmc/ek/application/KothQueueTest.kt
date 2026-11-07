package net.badgersmc.ek.application

import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import net.badgersmc.ek.config.DiscordConfig
import net.badgersmc.ek.config.DisplayConfig
import net.badgersmc.ek.config.EnthusiaKothConfig
import net.badgersmc.ek.config.LockConfig
import net.badgersmc.ek.config.PrivateTestingConfig
import net.badgersmc.ek.config.ScheduleConfig
import net.badgersmc.ek.domain.CaptureZone
import net.badgersmc.ek.domain.EventKind
import net.badgersmc.ek.domain.KothArena
import net.badgersmc.ek.domain.LockState
import net.badgersmc.ek.domain.PrivateTestAccess
import net.badgersmc.ek.domain.TeamMode
import net.badgersmc.ek.infrastructure.discord.DiscordWebhookService
import net.badgersmc.ek.infrastructure.display.ZoneBorderService
import net.badgersmc.ek.infrastructure.lumaguilds.LumaGuildsAdapter
import net.badgersmc.ek.infrastructure.persistence.EventQueueStore
import net.badgersmc.ek.infrastructure.persistence.FileOperationalStateStore
import net.badgersmc.ek.infrastructure.persistence.InMemoryEventQueueStore
import net.badgersmc.ek.infrastructure.persistence.ScheduleClaimStatus
import net.badgersmc.ek.infrastructure.persistence.SqlStatsRepository
import net.badgersmc.nexus.i18n.LangService
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class KothQueueTest {
    @Test fun `committed cancellation replays after failure before binding retirement`() {
        val ds=org.sqlite.SQLiteDataSource().apply { url="jdbc:sqlite:${temp.resolve("discard-replay.db")}" }
        val events=net.badgersmc.ek.infrastructure.persistence.SqlCrownEventStore(ds).also{it.init()}
        val recovery=net.badgersmc.ek.infrastructure.persistence.SqlCrownRecoveryEvents(ds).also{it.init()}
        ds.connection.use { c -> c.createStatement().use { it.execute("CREATE TABLE koth_matches(id TEXT PRIMARY KEY,winner TEXT)") } }
        val binding=net.badgersmc.ek.infrastructure.persistence.RecoveryEventBinding("slot",UUID.randomUUID(),
            CrownTenure(UUID.randomUUID(),UUID.randomUUID(),2))
        events.plan(net.badgersmc.ek.infrastructure.persistence.CrownOccurrence("slot","missing",now,
            net.badgersmc.ek.domain.CrownEventPurpose.RECOVERY_CROWN_FINAL,"test"))
        assertTrue(recovery.assign(binding))
        val schedule=temp.resolve("replay-schedule.dat").toFile();val queue=temp.resolve("replay-queue.dat").toFile()
        fun fileStore()=FileOperationalStateStore(schedule,queue,{ _, _ -> })
        val store=fileStore();store.save(listOf(QueuedEvent("missing",EventKind.SCHEDULED,now,occurrenceId="slot")))
        val first=service(store,LockState.UNLOCKED,emptyMap(),discard={ entry ->
            assertTrue(events.acknowledgeDiscard(entry.occurrenceId!!,entry.arenaId,entry.scheduledAt,entry.activationId))
            error("injected failure after cancellation commit")
        })
        first.processQueue()
        assertEquals(net.badgersmc.ek.infrastructure.persistence.CrownOccurrenceState.CANCELLED,events.read("slot")!!.state)
        assertEquals(listOf(binding),recovery.active());assertEquals(QueuedEventState.DISCARDED,fileStore().load().single().state)
        val restarted=service(fileStore(),LockState.UNLOCKED,emptyMap(),discard={ entry ->
            events.acknowledgeDiscard(entry.occurrenceId!!,entry.arenaId,entry.scheduledAt,entry.activationId).also { if(it)recovery.retireEmpty() }
        })
        restarted.processQueue();assertTrue(recovery.active().isEmpty());assertTrue(fileStore().load().isEmpty())
    }
    @Test fun `clearing scheduled queue durably marks every occurrence before acknowledgement`() {
        val rows=listOf(QueuedEvent("a",EventKind.SCHEDULED,now,occurrenceId="a"),
            QueuedEvent("b",EventKind.SCHEDULED,now,occurrenceId="b"))
        val store=InMemoryEventQueueStore(rows);val observed=mutableListOf<String>()
        val first=service(store,LockState.UNLOCKED,emptyMap(),discard={ entry ->
            assertTrue(store.load().all { it.state==QueuedEventState.DISCARDED })
            observed.add(entry.occurrenceId!!);entry.occurrenceId!="b"
        })
        assertFalse(first.clearQueue());assertEquals(listOf("a","b"),observed)
        assertEquals(listOf("b"),store.load().map { it.occurrenceId })
        val restarted=service(store,LockState.UNLOCKED,emptyMap(),discard={true})
        restarted.processQueue();assertTrue(store.load().isEmpty())
    }
    @Test fun `failed clear marker persistence keeps scheduled queue without acknowledgements`() {
        val rows=listOf(QueuedEvent("a",EventKind.SCHEDULED,now,occurrenceId="a"));var acknowledgements=0
        val store=FailingQueueStore(rows,failWhen={it.singleOrNull()?.state==QueuedEventState.DISCARDED})
        val service=service(store,LockState.UNLOCKED,emptyMap(),discard={acknowledgements++;true})
        assertFalse(service.clearQueue());assertEquals(rows,store.load());assertEquals(0,acknowledgements)
        assertTrue(service.clearQueue());assertEquals(1,acknowledgements);assertTrue(store.load().isEmpty())
    }
    @Test fun `file backed discarded marker survives restart and cannot be cleared before acknowledgement`() {
        val schedule=temp.resolve("discard-schedule.dat").toFile();val queue=temp.resolve("discard-queue.dat").toFile()
        fun fileStore()=FileOperationalStateStore(schedule,queue,{ _, _ -> })
        val store=fileStore();store.save(listOf(QueuedEvent("missing",EventKind.SCHEDULED,now,occurrenceId="slot")))
        val first=service(store,LockState.UNLOCKED,emptyMap(),discard={false});first.processQueue()
        assertFalse(first.clearQueue())
        val reloaded=fileStore();assertEquals(QueuedEventState.DISCARDED,reloaded.load().single().state)
        val restarted=service(reloaded,LockState.UNLOCKED,emptyMap(),discard={true});restarted.processQueue()
        assertTrue(fileStore().load().isEmpty())
    }
    @Test fun `discard marker survives failed acknowledgement and replays after restart`() {
        val store=InMemoryEventQueueStore(listOf(QueuedEvent("missing",EventKind.SCHEDULED,now,occurrenceId="slot")))
        val first=service(store,LockState.UNLOCKED,emptyMap(),discard={false})
        first.processQueue()
        assertEquals(QueuedEventState.DISCARDED,store.load().single().state)
        val restarted=service(store,LockState.UNLOCKED,emptyMap(),discard={true})
        restarted.processQueue();assertTrue(store.load().isEmpty())
    }
    @Test fun `failed discard marker write never acknowledges cancellation`() {
        var acknowledgements=0
        val store=FailingQueueStore(listOf(QueuedEvent("missing",EventKind.SCHEDULED,now,occurrenceId="slot")),
            failWhen={it.singleOrNull()?.state==QueuedEventState.DISCARDED})
        val first=service(store,LockState.UNLOCKED,emptyMap(),discard={acknowledgements++;true})
        first.processQueue();assertEquals(0,acknowledgements);assertEquals(QueuedEventState.READY,store.load().single().state)
        first.processQueue();assertEquals(1,acknowledgements);assertTrue(store.load().isEmpty())
    }
    private val observer = mockk<org.bukkit.entity.Player>(relaxed = true)
    @TempDir
    lateinit var temp: Path

    private val now = Instant.parse("2026-08-06T12:00:00Z")

    @BeforeEach
    fun mockBukkit() {
        mockkStatic(Bukkit::class)
        every { Bukkit.getOnlinePlayers() } returns mutableListOf(observer)
        every { Bukkit.getConsoleSender() } returns mockk(relaxed = true)
        every { Bukkit.broadcast(any<Component>()) } returns 0
        every { Bukkit.getWorld(any<String>()) } returns null
        every { Bukkit.getPlayer(any<UUID>()) } returns null
    }

    @AfterEach
    fun cleanupBukkit() {
        unmockkStatic(Bukkit::class)
    }

    @Test
    fun `queued event is removed only after startup succeeds`() {
        val arena = arena("capture")
        val store = InMemoryEventQueueStore(listOf(QueuedEvent(arena.id, EventKind.SCHEDULED, now)))
        val service = service(store, LockState.UNLOCKED, mapOf(arena.id to arena))

        service.processQueue()

        assertNotNull(service.activeEvent)
        assertTrue(service.queuedEvents().isEmpty())
        assertTrue(store.load().isEmpty())
    }

    @Test
    fun `reload cancellation never advances the queued event`() {
        val activeArena = arena("active")
        val queuedArena = arena("queued")
        val store = InMemoryEventQueueStore(listOf(QueuedEvent(queuedArena.id, EventKind.SCHEDULED, now)))
        val service = service(
            store,
            LockState.UNLOCKED,
            mapOf(activeArena.id to activeArena, queuedArena.id to queuedArena),
        )
        assertTrue(service.startEvent(activeArena))

        service.shutdown(CancellationReason.RELOAD)
        service.processQueue()

        assertNull(service.activeEvent)
        assertEquals(listOf("queued"), service.queuedEvents().map { it.arenaId })
        assertEquals(listOf("queued"), store.load().map { it.arenaId })
    }

    @Test
    fun `failed ready to activating write prevents activation and restart starts once`() {
        val arena = arena("capture")
        val store = FailingQueueStore(
            initial = listOf(QueuedEvent(arena.id, EventKind.SCHEDULED, now)),
            failWhen = { queue -> queue.singleOrNull()?.state == QueuedEventState.ACTIVATING },
        )
        val first = service(store, LockState.UNLOCKED, mapOf(arena.id to arena))

        first.processQueue()

        assertNull(first.activeEvent)
        assertEquals(QueuedEventState.READY, store.load().single().state)
        verify(exactly = 0) { observer.sendMessage(any<Component>()) }

        val restarted = service(store, LockState.UNLOCKED, mapOf(arena.id to arena))
        restarted.processQueue()
        val startedId = restarted.activeEvent?.id

        assertNotNull(startedId)
        assertTrue(store.load().isEmpty())
        restarted.processQueue()
        assertEquals(startedId, restarted.activeEvent?.id)
    }

    @Test
    fun `hard crash with activating row resumes the same activation id exactly once`() {
        val arena = arena("capture")
        val activationId = UUID.randomUUID()
        val store = InMemoryEventQueueStore(
            listOf(
                QueuedEvent(
                    arenaId = arena.id,
                    startSource = EventKind.SCHEDULED,
                    scheduledAt = now,
                    state = QueuedEventState.ACTIVATING,
                    activationId = activationId,
                ),
            ),
        )

        val restarted = service(store, LockState.UNLOCKED, mapOf(arena.id to arena))
        restarted.processQueue()

        assertEquals(activationId, restarted.activeEvent?.id)
        assertTrue(store.load().isEmpty())
        restarted.processQueue()
        assertEquals(activationId, restarted.activeEvent?.id)
        verify(exactly = 1) { observer.sendMessage(any<Component>()) }
    }

    @Test
    fun `failed completed transition keeps activating durable and restart resumes same id`() {
        val arena = arena("capture")
        val store = FailingQueueStore(
            initial = listOf(QueuedEvent(arena.id, EventKind.SCHEDULED, now)),
            failWhen = { queue -> queue.singleOrNull()?.state == QueuedEventState.COMPLETED },
        )
        val first = service(store, LockState.UNLOCKED, mapOf(arena.id to arena))

        first.processQueue()
        val activationId = first.activeEvent?.id

        assertNotNull(activationId)
        assertEquals(QueuedEventState.ACTIVATING, store.load().single().state)
        assertEquals(activationId, store.load().single().activationId)

        val restarted = service(store, LockState.UNLOCKED, mapOf(arena.id to arena))
        restarted.processQueue()

        assertEquals(activationId, restarted.activeEvent?.id)
        assertTrue(store.load().isEmpty())
    }

    @Test
    fun `completed marker surviving failed compaction never replays after restart`() {
        val arena = arena("capture")
        val store = FailingQueueStore(
            initial = listOf(QueuedEvent(arena.id, EventKind.SCHEDULED, now)),
            failWhen = { queue -> queue.isEmpty() },
        )
        val first = service(store, LockState.UNLOCKED, mapOf(arena.id to arena))

        first.processQueue()

        assertNotNull(first.activeEvent)
        assertEquals(QueuedEventState.COMPLETED, store.load().single().state)

        val restarted = service(store, LockState.UNLOCKED, mapOf(arena.id to arena))
        restarted.processQueue()

        assertNull(restarted.activeEvent)
        assertTrue(store.load().isEmpty())
    }

    @Test
    fun `activation rejection and failed retry persistence remain recoverable after crash`() {
        val arena = arena("capture")
        val store = FailingQueueStore(
            initial = listOf(QueuedEvent(arena.id, EventKind.SCHEDULED, now)),
            failWhen = { queue ->
                queue.singleOrNull()?.let { it.state == QueuedEventState.READY && it.attempts == 1 } == true
            },
        )
        var configReads = 0
        val first = service(
            store,
            LockState.UNLOCKED,
            mapOf(arena.id to arena),
            lockProvider = {
                if (configReads++ == 0) LockState.UNLOCKED else LockState.ALL_LOCKED
            },
        )

        first.processQueue()

        assertNull(first.activeEvent)
        val durable = store.load().single()
        assertEquals(QueuedEventState.ACTIVATING, durable.state)
        assertNotNull(durable.activationId)

        val restarted = service(store, LockState.UNLOCKED, mapOf(arena.id to arena))
        restarted.processQueue()

        assertEquals(durable.activationId, restarted.activeEvent?.id)
        assertTrue(store.load().isEmpty())
    }

    @Test
    fun `scheduled queue row cannot activate until its durable occurrence claim is committed`() {
        val arena = arena("capture")
        val occurrenceId = "arena:capture:0:2026-08-06:12:00"
        val store = FileOperationalStateStore(
            scheduleFile = temp.resolve("schedule-claim-gate.dat").toFile(),
            queueFile = temp.resolve("queue-claim-gate.dat").toFile(),
            logger = { _, _ -> },
        )
        assertTrue(store.claim("start:$occurrenceId", now))
        store.save(
            listOf(
                QueuedEvent(
                    arenaId = arena.id,
                    startSource = EventKind.SCHEDULED,
                    scheduledAt = now,
                    occurrenceId = occurrenceId,
                ),
            ),
        )
        val service = service(store, LockState.UNLOCKED, mapOf(arena.id to arena))

        service.processQueue()

        assertNull(service.activeEvent)
        assertEquals(QueuedEventState.READY, store.load().single().state)

        assertTrue(store.commit("start:$occurrenceId"))
        service.processQueue()

        assertNotNull(service.activeEvent)
        assertTrue(store.load().isEmpty())
    }

    @Test
    fun `scheduled queue row remains authoritative if an old claim is already missing`() {
        val arena = arena("capture")
        val occurrenceId = "arena:capture:legacy-pruned"
        val store = FileOperationalStateStore(
            scheduleFile = temp.resolve("schedule-missing-claim.dat").toFile(),
            queueFile = temp.resolve("queue-missing-claim.dat").toFile(),
            logger = { _, _ -> },
        )
        store.save(
            listOf(
                QueuedEvent(
                    arenaId = arena.id,
                    startSource = EventKind.SCHEDULED,
                    scheduledAt = now,
                    occurrenceId = occurrenceId,
                ),
            ),
        )

        val service = service(store, LockState.UNLOCKED, mapOf(arena.id to arena))
        service.processQueue()

        assertNotNull(service.activeEvent)
        assertTrue(store.load().isEmpty())
    }

    @Test
    fun `scheduled lock beyond claim retention starts once and does not block next event`() {
        val alpha = arena("alpha")
        val beta = arena("beta")
        val alphaOccurrence = "arena:alpha:0:old"
        val betaOccurrence = "arena:beta:0:old"
        val store = FileOperationalStateStore(
            scheduleFile = temp.resolve("schedule-long-lock.dat").toFile(),
            queueFile = temp.resolve("queue-long-lock.dat").toFile(),
            logger = { _, _ -> },
        )
        assertTrue(store.claim("start:$alphaOccurrence", now, alpha.id, TeamMode.SOLO))
        assertTrue(store.commit("start:$alphaOccurrence"))
        assertTrue(store.claim("start:$betaOccurrence", now.plusSeconds(1), beta.id, TeamMode.SOLO))
        assertTrue(store.commit("start:$betaOccurrence"))
        store.save(
            listOf(
                QueuedEvent(alpha.id, EventKind.SCHEDULED, now, occurrenceId = alphaOccurrence),
                QueuedEvent(beta.id, EventKind.SCHEDULED, now.plusSeconds(1), occurrenceId = betaOccurrence),
            ),
        )

        val lockedAt = now.plus(Duration.ofDays(4))
        val lockedKoth = service(
            store,
            LockState.ALL_LOCKED,
            mapOf(alpha.id to alpha, beta.id to beta),
            clock = Clock.fixed(lockedAt, ZoneOffset.UTC),
        )
        val scheduler = ScheduleService(
            cfgLoader = {
                EnthusiaKothConfig(
                    schedule = ScheduleConfig(enabled = true, zone = ZoneOffset.UTC),
                )
            },
            kothService = lockedKoth,
            arenas = { mapOf(alpha.id to alpha, beta.id to beta) },
            stateStore = store,
            clock = Clock.fixed(lockedAt, ZoneOffset.UTC),
            warningSink = { _, _ -> },
            logger = {},
        )

        scheduler.tickAt(lockedAt)

        assertEquals(ScheduleClaimStatus.COMMITTED, store.claimStatus("start:$alphaOccurrence"))
        assertEquals(ScheduleClaimStatus.COMMITTED, store.claimStatus("start:$betaOccurrence"))
        assertEquals(listOf("alpha", "beta"), store.load().map { it.arenaId })

        val unlockedAt = lockedAt.plusSeconds(11)
        val unlocked = service(
            store,
            LockState.UNLOCKED,
            mapOf(alpha.id to alpha, beta.id to beta),
            clock = Clock.fixed(unlockedAt, ZoneOffset.UTC),
        )
        unlocked.processQueue()
        val alphaActivation = unlocked.activeEvent?.id

        assertNotNull(alphaActivation)
        assertEquals("alpha", unlocked.activeEvent?.arena?.id)
        unlocked.processQueue()
        assertEquals(alphaActivation, unlocked.activeEvent?.id)

        assertTrue(unlocked.forceEnd(announce = false))
        assertEquals("beta", unlocked.activeEvent?.arena?.id)
        verify(exactly = 2) { observer.sendMessage(any<Component>()) }
    }

    @Test
    fun `temporary lock failure leaves event queued with retry state`() {
        val arena = arena("capture")
        val store = InMemoryEventQueueStore(listOf(QueuedEvent(arena.id, EventKind.SCHEDULED, now)))
        val service = service(store, LockState.ALL_LOCKED, mapOf(arena.id to arena))

        service.processQueue()

        assertEquals(1, service.queuedEvents().size)
        assertEquals(1, service.nextQueued()?.attempts)
        assertEquals(now.plusSeconds(10), service.nextQueued()?.nextAttemptAt)
        assertEquals(1, store.load().size)
    }

    @Test
    fun `temporary lock beyond old sixty retry ceiling still starts exactly once after unlock`() {
        val arena = arena("capture")
        val store = InMemoryEventQueueStore(
            listOf(QueuedEvent(arena.id, EventKind.SCHEDULED, now, teamMode = TeamMode.GUILD)),
        )

        repeat(65) { attempt ->
            val attemptTime = now.plusSeconds(attempt * 10L)
            service(
                store,
                LockState.ALL_LOCKED,
                mapOf(arena.id to arena),
                clock = Clock.fixed(attemptTime, ZoneOffset.UTC),
            ).processQueue()
        }

        assertEquals(65, store.load().single().attempts)
        assertEquals(TeamMode.GUILD, store.load().single().teamMode)

        val unlocked = service(
            store,
            LockState.UNLOCKED,
            mapOf(arena.id to arena),
            clock = Clock.fixed(now.plusSeconds(650), ZoneOffset.UTC),
        )
        unlocked.processQueue()
        val startedId = unlocked.activeEvent?.id

        assertNotNull(startedId)
        assertEquals(TeamMode.GUILD, unlocked.activeEvent?.teamMode)
        assertTrue(unlocked.queuedEvents().isEmpty())
        assertTrue(store.load().isEmpty())

        unlocked.processQueue()
        assertEquals(startedId, unlocked.activeEvent?.id)
    }

    @Test
    fun `private tests preserve mode access and quick versus production timing`() {
        val arena = arena("capture").copy(durationSeconds = 90, captureSeconds = 30)
        val cfg = EnthusiaKothConfig(
            privateTesting = PrivateTestingConfig(
                lobbySeconds = 0,
                quickMatchDurationSeconds = 12,
                quickCaptureSeconds = 4,
            ),
        )
        val production = service(InMemoryEventQueueStore(), LockState.UNLOCKED, mapOf(arena.id to arena))
        assertTrue(
            production.startPrivateTest(
                arena,
                UUID.randomUUID(),
                cfg,
                TeamMode.GUILD,
                PrivateTestAccess.OWNER_ONLY,
                quickTiming = false,
            ),
        )
        assertEquals(90, production.activeEvent?.arena?.durationSeconds)
        assertEquals(30, production.activeEvent?.arena?.captureSeconds)
        assertEquals(TeamMode.GUILD, production.activeEvent?.teamMode)
        assertEquals(PrivateTestAccess.OWNER_ONLY, production.activeEvent?.privateTestAccess)

        val quick = service(InMemoryEventQueueStore(), LockState.UNLOCKED, mapOf(arena.id to arena))
        assertTrue(
            quick.startPrivateTest(
                arena,
                UUID.randomUUID(),
                cfg,
                TeamMode.SOLO,
                PrivateTestAccess.PERMISSION_JOIN,
                quickTiming = true,
            ),
        )
        assertEquals(12, quick.activeEvent?.arena?.durationSeconds)
        assertEquals(4, quick.activeEvent?.arena?.captureSeconds)
        assertEquals(PrivateTestAccess.PERMISSION_JOIN, quick.activeEvent?.privateTestAccess)
    }

    @Test
    fun `conquest private testing remains rejected by legacy contract`() {
        val arena = arena("conquest", family = "conquest")
        val cfg = EnthusiaKothConfig(privateTesting = PrivateTestingConfig(lobbySeconds = 0))
        val service = service(InMemoryEventQueueStore(), LockState.UNLOCKED, mapOf(arena.id to arena))

        assertFalse(KothService.supportsPrivateTesting(arena))
        assertFalse(
            service.startPrivateTest(
                arena,
                UUID.randomUUID(),
                cfg,
                TeamMode.GUILD,
                PrivateTestAccess.OWNER_ONLY,
                quickTiming = true,
            ),
        )
        assertNull(service.activeEvent)
    }

    @Test
    fun `permanently invalid arena is logged and deliberately removed`() {
        val logs = mutableListOf<String>()
        val store = InMemoryEventQueueStore(listOf(QueuedEvent("removed", EventKind.SCHEDULED, now)))
        val service = service(store, LockState.UNLOCKED, emptyMap(), logs)

        service.processQueue()

        assertTrue(service.queuedEvents().isEmpty())
        assertTrue(logs.single().contains("permanently invalid"))
    }

    @Test
    fun `queue preserves insertion order and lock changes do not erase it`() {
        val alpha = arena("alpha")
        val beta = arena("beta")
        val store = InMemoryEventQueueStore()
        val service = service(store, LockState.ALL_LOCKED, mapOf(alpha.id to alpha, beta.id to beta))

        service.queueStart(alpha, EventKind.SCHEDULED, now)
        service.queueStart(beta, EventKind.SCHEDULED, now.plusSeconds(1))

        assertEquals(listOf("alpha", "beta"), service.queuedEvents().map { it.arenaId })
        assertEquals(listOf("alpha", "beta"), store.load().map { it.arenaId })
    }

    @Test
    fun `queue write failure rejects enqueue and leaves no volatile ghost entry`() {
        val arena = arena("capture")
        val store = object : EventQueueStore {
            override fun load(): List<QueuedEvent> = emptyList()
            override fun save(queue: List<QueuedEvent>) { error("disk full") }
        }
        val service = service(store, LockState.ALL_LOCKED, mapOf(arena.id to arena))

        assertFalse(service.queueStart(arena, EventKind.SCHEDULED, now))
        assertTrue(service.queuedEvents().isEmpty())
    }

    @Test fun `terminal Crown slot cannot start or block the following slot`() {
        val first = arena("closed")
        val second = arena("open")
        val queue = InMemoryEventQueueStore()
        val service = service(queue, LockState.UNLOCKED, mapOf(first.id to first, second.id to second),
            bind = { queued, _ -> queued.occurrenceId != "closed-slot" })
        service.queueStart(first, EventKind.SCHEDULED, now, occurrenceId = "closed-slot")
        service.queueStart(second, EventKind.SCHEDULED, now, occurrenceId = "open-slot")
        service.processQueue()
        assertEquals(second.id, service.activeEvent?.arena?.id)
        assertTrue(queue.load().isEmpty())
    }
    private class MissedFixture(temp: Path, name: String) {
        val ds=org.sqlite.SQLiteDataSource().apply { url="jdbc:sqlite:${temp.resolve("$name.db")}" }
        val events=net.badgersmc.ek.infrastructure.persistence.SqlCrownEventStore(ds).also{it.init()}
        val recovery=net.badgersmc.ek.infrastructure.persistence.SqlCrownRecoveryEvents(ds).also{it.init()}
        val schedule=temp.resolve("$name-schedule.dat").toFile();val queue=temp.resolve("$name-queue.dat").toFile()
        val store=FileOperationalStateStore(schedule,queue,{ _, _ -> })
        init { ds.connection.use { c -> c.createStatement().use { it.execute("CREATE TABLE koth_matches(id TEXT PRIMARY KEY,winner TEXT)") } } }
        fun plan(id:String,arena:String,at:Instant):net.badgersmc.ek.infrastructure.persistence.CrownOccurrence {
            val slot=net.badgersmc.ek.infrastructure.persistence.CrownOccurrence(id,arena,at,
                net.badgersmc.ek.domain.CrownEventPurpose.RECOVERY_CROWN_FINAL,"test")
            events.plan(slot)
            assertTrue(recovery.assign(net.badgersmc.ek.infrastructure.persistence.RecoveryEventBinding(id,UUID.randomUUID(),
                CrownTenure(UUID.randomUUID(),UUID.randomUUID(),2))))
            return slot
        }
        fun gate(service:KothService,slot:net.badgersmc.ek.infrastructure.persistence.CrownOccurrence,at:Instant)=
            service.withQueueEvidence { live ->
                val decision=MissedOccurrencePolicy.assess(slot.occurrenceId,slot.scheduledAt,at,store.verifiedSnapshot(),live)
                decision to (decision==MissedOccurrenceDecision.MISSED && events.cancelMissed(slot))
            }
    }

    @Test fun `never queued Crown slot cancels only on verified evidence and a late handoff is rejected`() {
        val f=MissedFixture(temp,"missed");val hill=arena("hill");val slot=f.plan("slot",hill.id,now)
        val later=now.plus(ScheduleService.MAX_CATCH_UP).plusSeconds(120)
        val service=service(f.store,LockState.UNLOCKED,mapOf(hill.id to hill),clock=Clock.fixed(later,ZoneOffset.UTC),
            bind={ q, match -> f.events.bindScheduledMatch(q.occurrenceId!!,q.arenaId,q.scheduledAt,true,match) },
            discard={ q -> f.events.acknowledgeDiscard(q.occurrenceId!!,q.arenaId,q.scheduledAt,q.activationId).also { if(it)f.recovery.retireEmpty() } })
        f.store.setLastEvaluation(later)
        // No queue file has ever been written: unknown evidence, not proof of absence.
        assertEquals(MissedOccurrenceDecision.HOLD to false,f.gate(service,slot,later))
        f.store.save(emptyList())
        assertEquals(MissedOccurrenceDecision.MISSED to true,f.gate(service,slot,later))
        f.recovery.retireEmpty();assertTrue(f.recovery.active().isEmpty())
        // A handoff arriving after cancellation (e.g. after clock regression) loses the same CAS.
        assertTrue(service.queueStart(hill,EventKind.SCHEDULED,now,occurrenceId="slot"));service.processQueue()
        assertNull(service.activeEvent);assertTrue(f.store.load().isEmpty())
        assertEquals(net.badgersmc.ek.infrastructure.persistence.CrownOccurrenceState.CANCELLED,f.events.read("slot")!!.state)
    }

    @Test fun `delayed queued start and pending claim keep a Crown slot bound`() {
        val f=MissedFixture(temp,"delayed");val hill=arena("hill");val slot=f.plan("slot",hill.id,now)
        val later=now.plus(Duration.ofHours(2))
        val service=service(f.store,LockState.ALL_LOCKED,mapOf(hill.id to hill),clock=Clock.fixed(later,ZoneOffset.UTC))
        f.store.setLastEvaluation(later)
        assertTrue(service.queueStart(hill,EventKind.SCHEDULED,now,occurrenceId="slot"));service.processQueue()
        assertEquals(MissedOccurrenceDecision.SCHEDULED to false,f.gate(service,slot,later))
        val other=f.plan("other",hill.id,now.plusSeconds(60))
        assertTrue(f.store.claim("start:other",other.scheduledAt,hill.id,TeamMode.SOLO))
        assertEquals(MissedOccurrenceDecision.SCHEDULED to false,f.gate(service,other,later))
        assertTrue(f.store.commit("start:other"))
        assertEquals(MissedOccurrenceDecision.HOLD to false,f.gate(service,other,later))
        assertEquals(2,f.recovery.active().size)
        assertEquals(net.badgersmc.ek.infrastructure.persistence.CrownOccurrenceState.PLANNED,f.events.read("slot")!!.state)
    }

    @Test fun `started Crown slot wins the race against missed cancellation`() {
        val f=MissedFixture(temp,"race");val slot=f.plan("slot","hill",now)
        val match=UUID.randomUUID()
        assertTrue(f.events.bindScheduledMatch("slot","hill",now,true,match))
        assertFalse(f.events.cancelMissed(slot))
        assertFalse(f.events.cancelMissed(slot.copy(policyRevision="other")))
        val started=f.events.read("slot")!!
        assertEquals(net.badgersmc.ek.infrastructure.persistence.CrownOccurrenceState.STARTED,started.state);assertEquals(match,started.matchId)
        f.recovery.retireEmpty();assertEquals(1,f.recovery.active().size)
    }

    @Test fun `unsaved queue change holds missed evaluation`() {
        val f=MissedFixture(temp,"dirty");val hill=arena("hill");val slot=f.plan("slot",hill.id,now)
        val later=now.plus(Duration.ofHours(2))
        val failing=object : EventQueueStore by f.store {
            var fail=false
            override fun save(queue: List<QueuedEvent>) { if(fail)error("injected queue write failure");f.store.save(queue) }
        }
        val service=service(failing,LockState.ALL_LOCKED,mapOf(hill.id to hill),clock=Clock.fixed(later,ZoneOffset.UTC))
        f.store.setLastEvaluation(later);f.store.save(emptyList())
        assertTrue(service.queueStart(hill,EventKind.SCHEDULED,now,occurrenceId="queued"))
        failing.fail=true;service.processQueue()
        assertEquals(MissedOccurrenceDecision.HOLD to false,f.gate(service,slot,later))
    }

    private fun service(
        store: EventQueueStore,
        lock: LockState,
        arenaMap: Map<String, KothArena>,
        logs: MutableList<String> = mutableListOf(),
        clock: Clock = Clock.fixed(now, ZoneOffset.UTC),
        lockProvider: () -> LockState = { lock },
        bind: (QueuedEvent, UUID) -> Boolean = { _, _ -> true },
        discard: (QueuedEvent) -> Boolean = { true },
    ): KothService {
        val lang = mockk<LangService>(relaxed = true)
        every { lang.msg(any(), *anyVararg()) } returns Component.empty()
        return KothService(
            cfgLoader = {
                EnthusiaKothConfig(
                    locks = LockConfig(lockProvider()),
                    display = DisplayConfig(zoneBorder = false),
                    discord = DiscordConfig(enabled = false),
                )
            },
            stats = mockk<SqlStatsRepository>(relaxed = true),
            economy = mockk<PlayerEconomy>(relaxed = true),
            guilds = mockk<LumaGuildsAdapter>(relaxed = true),
            displayService = mockk<DisplayService>(relaxed = true),
            fireworkService = mockk<FireworkCelebrationService>(relaxed = true),
            discordWebhook = mockk<DiscordWebhookService>(relaxed = true),
            zoneBorderService = mockk<ZoneBorderService>(relaxed = true),
            lang = lang,
            bindScheduledMatch = bind,
            acknowledgeDiscarded = discard,
            arenaResolver = arenaMap::get,
            queueStore = store,
            clock = clock,
            logger = { message, _ -> logs += message },
        )
    }

    private fun arena(id: String, family: String = "capture") = KothArena(
        id = id,
        family = family,
        zone = mockk<CaptureZone>(relaxed = true),
        durationSeconds = 60,
        captureSeconds = 10,
    )

    private class FailingQueueStore(
        initial: List<QueuedEvent>,
        private val failWhen: (List<QueuedEvent>) -> Boolean,
    ) : EventQueueStore {
        private var persisted = initial.toList()
        private var failed = false

        override fun load(): List<QueuedEvent> = persisted.toList()

        override fun save(queue: List<QueuedEvent>) {
            if (!failed && failWhen(queue)) {
                failed = true
                error("injected queue persistence failure")
            }
            persisted = queue.toList()
        }
    }
}
