package net.badgersmc.ek.application

import io.mockk.*
import net.badgersmc.ek.api.*
import net.badgersmc.ek.config.*
import net.badgersmc.ek.domain.*
import net.badgersmc.ek.infrastructure.persistence.*
import net.badgersmc.nexus.i18n.LangService
import net.kyori.adventure.text.Component
import org.bukkit.*
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import java.time.*
import java.util.UUID

class KothExpansionTest {
    private val world = mockk<World>()
    private val messages = mutableListOf<KothLifecycleEvent>()
    private val stats = mockk<SqlStatsRepository>(relaxed = true)
    private val display = mockk<DisplayService>(relaxed = true)
    private var now = Instant.parse("2026-10-08T16:00:00Z")
    private val clock = object : Clock() { override fun getZone() = ZoneOffset.UTC; override fun withZone(zone: ZoneId) = this; override fun instant() = now }
    private val guild = TeamId(TeamMode.GUILD, UUID.randomUUID())
    @BeforeEach fun setup() {
        mockkStatic(Bukkit::class); messages.clear()
        every { world.name } returns "world"
        every { Bukkit.getOnlinePlayers() } returns emptyList()
        every { Bukkit.getWorld(any<String>()) } returns null
        every { Bukkit.getPlayer(any<UUID>()) } returns null
        every { Bukkit.getConsoleSender() } returns mockk(relaxed = true)
    }
    @AfterEach fun cleanup() = unmockkAll()
    private fun arena(id: String, offset: Double = 0.0) = KothArena(id, "score",
        CaptureZone(id, "world", Location(world, offset, 75.0, 0.0), Location(world, offset + 10, 85.0, 10.0)),
        durationSeconds = 60, captureSeconds = 1)
    private fun service(capacity: Int = 2, sink: (KothLifecycleEvent) -> Unit = messages::add, queue: EventQueueStore = InMemoryEventQueueStore(), arenas: Map<String, KothArena> = emptyMap()) = KothService(
        cfgLoader = { EnthusiaKothConfig(maxConcurrentEvents = capacity, display = DisplayConfig(false)) },
        stats = stats, economy = mockk(relaxed = true), guilds = mockk(relaxed = true), displayService = display,
        fireworkService = mockk(relaxed = true), discordWebhook = mockk(relaxed = true), zoneBorderService = mockk(relaxed = true),
        lang = mockk<LangService>(relaxed = true).also { every { it.msg(any(), *anyVararg()) } returns Component.empty() },
        arenaResolver = { arenas[it] }, queueStore = queue, clock = clock, logger = { _, _ -> }, lifecycleSink = sink,
    )
    @Test fun `distinct nonoverlapping events start and stopping one preserves another`() {
        val s = service(); assertTrue(s.startEvent(arena("a"))); assertTrue(s.startEvent(arena("b", 50.0)))
        assertEquals(2, s.allEvents().size); assertTrue(s.forceEnd(announce = false, arenaId = "b"))
        assertEquals("a", s.allEvents().single().arena.id)
        verify(exactly = 1) { display.clear("b") }; verify(exactly = 0) { display.clear("a") }
    }
    @Test fun `default capacity still permits only one event`() {
        val s = service(1); assertTrue(s.startEvent(arena("a"))); assertFalse(s.startEvent(arena("b", 50.0)))
    }
    @Test fun `same arena and overlapping native boundaries reject starts`() {
        val s = service(); assertTrue(s.startEvent(arena("a")))
        assertFalse(s.startEvent(arena("a", 50.0))); assertFalse(s.startEvent(arena("b", 5.0)))
    }
    @Test fun `named region intersection fails closed in same world`() {
        assertTrue(EventConcurrency.overlaps(arena("a").copy(worldGuardRegion = "one"), arena("b", 50.0)))
        assertFalse(EventConcurrency.overlaps(arena("a"), arena("b").copy(zone = arena("b").zone.copy(worldName = "other"))))
    }
    @Test fun `private test remains exclusive in both directions`() {
        val s = service(); assertTrue(s.startPrivateTest(arena("p"), UUID.randomUUID(), EnthusiaKothConfig(), TeamMode.SOLO, PrivateTestAccess.OWNER_ONLY, false))
        assertFalse(s.startEvent(arena("a", 50.0)))
        s.forceEnd(announce = false); assertTrue(s.startEvent(arena("a")))
        assertFalse(s.startPrivateTest(arena("p", 50.0), UUID.randomUUID(), EnthusiaKothConfig(), TeamMode.SOLO, PrivateTestAccess.OWNER_ONLY, false))
    }
    @Test fun `score progress accumulates and contested seconds pause without reset`() {
        val event = KothEvent(UUID.randomUUID(), arena("a"), now, now.plusSeconds(60))
        KothService.applyMovingScore(event, listOf(guild)); KothService.applyMovingScore(event, emptyList())
        KothService.applyMovingScore(event, listOf(guild, TeamId(TeamMode.GUILD, UUID.randomUUID())))
        assertEquals(1.0, event.scores[guild]); assertNull(event.currentController)
        KothService.applyMovingScore(event, listOf(guild)); assertEquals(2.0, event.scores[guild])
    }
    @Test fun `score mode never finishes at capture threshold and uses unique leader at expiry`() {
        val s = service(); s.startEvent(arena("a")); s.activeEvent!!.scores[guild] = 5.0
        s.tick(); assertNotNull(s.activeEvent)
        now = now.plusSeconds(60); s.tick(); assertNull(s.activeEvent)
        assertEquals(guild.id, messages.last().snapshot.winner?.id)
        verify { stats.recordTimedWin(any(), guild.storageKey(), "a", now) }
    }
    @Test fun `ties and zero scores have no winner or timestamped win`() {
        val s = service(); s.startEvent(arena("a")); s.activeEvent!!.scores[guild] = 2.0
        s.activeEvent!!.scores[TeamId(TeamMode.GUILD, UUID.randomUUID())] = 2.0
        now = now.plusSeconds(60); s.tick(); assertNull(messages.last().snapshot.winner)
        verify(exactly = 0) { stats.recordTimedWin(any(), any(), any(), any()) }
    }
    @Test fun `observer exceptions cannot strand activation cancellation or completion`() {
        val s = service(sink = { throw IllegalStateException("observer") }); assertTrue(s.startEvent(arena("a")))
        assertTrue(s.forceEnd(announce = false)); assertTrue(s.startEvent(arena("a")))
        now = now.plusSeconds(60); s.tick(); assertTrue(s.allEvents().isEmpty())
    }
    @Test fun `completion observer cannot cancel or refund a completed event`() {
        lateinit var s: KothService
        var cancellation: Boolean? = null
        s = service(sink = { if (it.lifecycle == KothLifecycle.COMPLETED) cancellation = s.forceEnd(announce = false) })
        s.startEvent(arena("a")); now = now.plusSeconds(60); s.tick()
        assertEquals(false, cancellation); assertTrue(s.allEvents().isEmpty())
    }
    @Test fun `paid arena conflict rejects before any economy call`() {
        val economy = mockk<PlayerEconomy>(); val starter = mockk<EventStarter>()
        val s = StartService({ EnthusiaKothConfig() }, { true }, { false }, economy, starter, { _, _ -> }, arenaConflict = { true })
        val result = s.start(StartRequest(StartActor(UUID.randomUUID(), canStartBasic = true), arena("a"), StartSource.PLAYER_COMMAND))
        assertEquals(StartFailure.ALREADY_ACTIVE, (result as StartResult.Rejected).failure)
        verify { economy wasNot Called }; verify { starter wasNot Called }
    }
    @Test fun `shutdown cancels all events without starting queued work`() {
        val a = arena("a"); val b = arena("b", 50.0); val c = arena("c", 100.0)
        val s = service(arenas = mapOf("c" to c)); s.startEvent(a); s.startEvent(b); s.queueStart(c)
        s.shutdown(); assertTrue(s.allEvents().isEmpty()); assertEquals(1, s.queuedEvents().size)
    }
    @Test fun `queue fills available slots with stable occurrence ids`() {
        val a = arena("a"); val b = arena("b", 50.0); val s = service(arenas = mapOf("a" to a, "b" to b))
        s.queueStart(a, occurrenceId = "a:once"); s.queueStart(b, occurrenceId = "b:once"); s.processQueue()
        assertEquals(setOf("a", "b"), s.allEvents().map { it.arena.id }.toSet())
        assertTrue(s.queuedEvents().isEmpty()); assertEquals(2, messages.count { it.lifecycle == KothLifecycle.STARTED })
    }
    @Test fun `lifecycle snapshots keep immutable scores after cleanup`() {
        val s = service(); s.startEvent(arena("a")); s.activeEvent!!.scores[guild] = 3.0
        now = now.plusSeconds(60); s.tick()
        val snapshot = messages.last().snapshot
        assertEquals(3.0, snapshot.scores.values.single())
        assertThrows(UnsupportedOperationException::class.java) { (snapshot.scores as MutableMap).clear() }
    }
    @Test fun `start observer cancellation prevents orphaned announcement and visuals`() {
        lateinit var s: KothService
        val console = mockk<org.bukkit.command.ConsoleCommandSender>(relaxed = true)
        every { Bukkit.getConsoleSender() } returns console
        s = service(sink = { if (it.lifecycle == KothLifecycle.STARTED) s.forceEnd(announce = false) })
        assertTrue(s.startEvent(arena("a"))); assertTrue(s.allEvents().isEmpty())
        verify(exactly = 0) { console.sendMessage(any<Component>()) }
    }
    @Test fun `shutdown keeps pending refund flag when a later refund succeeds`() {
        val a = UUID.randomUUID(); val b = UUID.randomUUID()
        val economy = mockk<PlayerEconomy>(); every { economy.deposit(a, 1.0) } returns false; every { economy.deposit(b, 1.0) } returns true
        val s = KothService({ EnthusiaKothConfig(maxConcurrentEvents = 2, display = DisplayConfig(false)) }, stats, economy,
            mockk(relaxed = true), display, fireworkService = mockk(relaxed = true), discordWebhook = mockk(relaxed = true),
            zoneBorderService = mockk(relaxed = true), lang = mockk<LangService>(relaxed = true).also { every { it.msg(any(), *anyVararg()) } returns Component.empty() },
            arenaResolver = { null }, queueStore = InMemoryEventQueueStore(), clock = clock, logger = { _, _ -> })
        s.startEvent(arena("a"), paymentReceipt = PaymentReceipt(a, 1.0, StartSource.GUI))
        s.startEvent(arena("b", 50.0), paymentReceipt = PaymentReceipt(b, 1.0, StartSource.GUI))
        s.shutdown(); assertTrue(s.lastCancellationRefundPending); assertTrue(s.allEvents().isEmpty())
    }

}
