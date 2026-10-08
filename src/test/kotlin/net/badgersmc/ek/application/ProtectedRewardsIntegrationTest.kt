package net.badgersmc.ek.application

import io.mockk.*
import net.badgersmc.ek.config.*
import net.badgersmc.ek.domain.*
import net.badgersmc.ek.infrastructure.lumaguilds.LumaGuildsAdapter
import net.badgersmc.ek.infrastructure.persistence.*
import net.badgersmc.nexus.i18n.LangService
import net.kyori.adventure.text.Component
import org.bukkit.*
import org.bukkit.entity.Player
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import java.time.*
import java.util.UUID

class ProtectedRewardsIntegrationTest {
    private val world=mockk<World>()
    private val a=UUID.randomUUID(); private val b=UUID.randomUUID()
    private val p1=mockk<Player>(relaxed=true); private val p2=mockk<Player>(relaxed=true); private val enemy=mockk<Player>(relaxed=true)
    private val ids=listOf(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID())
    private val guilds=mockk<LumaGuildsAdapter>(relaxed=true)
    private val stats=mockk<SqlStatsRepository>(relaxed=true)
    private val store=mockk<RewardProtectionStore>(relaxed=true)
    private var now=Instant.parse("2026-10-08T12:00:00Z")
    private var hill:List<Player> = emptyList()
    private val clock=object:Clock() { override fun getZone()=ZoneOffset.UTC; override fun withZone(zone:ZoneId)=this; override fun instant()=now }
    @BeforeEach fun setup() {
        mockkStatic(Bukkit::class)
        every { world.name } returns "world"
        every { Bukkit.getOnlinePlayers() } answers { hill }
        every { Bukkit.getWorld(any<String>()) } returns null
        every { Bukkit.getConsoleSender() } returns mockk(relaxed=true)
        every { Bukkit.dispatchCommand(any(),any()) } returns true
        listOf(p1,p2,enemy).forEachIndexed { i,p ->
            every { p.uniqueId } returns ids[i]; every { p.name } returns "player$i"
            every { p.firstPlayed } returns now.minusSeconds(864000).toEpochMilli()
            every { p.getStatistic(Statistic.PLAY_ONE_MINUTE) } returns 144000
            every { p.isValid } returns true; every { p.isDead } returns false; every { p.gameMode } returns GameMode.SURVIVAL
            every { p.location } returns Location(world,5.0,80.0,5.0)
            every { Bukkit.getPlayer(ids[i]) } returns p
            every { guilds.playerGuildIds(ids[i]) } returns setOf(if(i<2) a else b)
        }
        every { guilds.protectionRoster() } returns ProtectionRoster(mapOf(a to emptySet(),b to emptySet()),mapOf(ids[0] to setOf(a),ids[1] to setOf(a),ids[2] to setOf(b)))
        every { guilds.allianceGraph() } returns mapOf(a to emptySet(),b to emptySet())
        every { guilds.guildName(any()) } returns "guild"
        every { store.reserve(any(),any(),any(),any(),any(),any(),any()) } returns true
    }
    @AfterEach fun cleanup()=unmockkAll()
    private fun game():KothService {
        val arena=KothArena("hill","score",CaptureZone("hill","world",Location(world,0.0,75.0,0.0),Location(world,10.0,85.0,10.0)),durationSeconds=120,captureSeconds=10,
            rewards=listOf("give {CONTRIBUTORS} diamond 1","give {ALL_ONLINE} gold_ingot 1"))
        val s=KothService(cfgLoader={ EnthusiaKothConfig(rewardProtection=RewardProtectionConfig(enabled=true),display=DisplayConfig(false),rewards=mapOf("score" to RewardConfig(guildVaultMoney=10.0))) },
            stats=stats,economy=mockk(relaxed=true),guilds=guilds,displayService=mockk(relaxed=true),fireworkService=mockk(relaxed=true),discordWebhook=mockk(relaxed=true),zoneBorderService=mockk(relaxed=true),
            lang=mockk<LangService>(relaxed=true).also { every { it.msg(any(),*anyVararg()) } returns Component.empty() },arenaResolver={arena},queueStore=InMemoryEventQueueStore(),clock=clock,logger={_,_->},protectionStore=store)
        assertTrue(s.startEvent(arena,teamMode=TeamMode.GUILD))
        hill=listOf(enemy); repeat(30) { s.tick() }
        hill=listOf(p1,p2); repeat(41) { s.tick() }
        now=now.plusSeconds(120); s.tick(); return s
    }
    @Test fun `multiple contributors and legacy all online commands share one event budget`() {
        game()
        verify(exactly=1) { Bukkit.dispatchCommand(any(),any()) }
        verify(exactly=1) { guilds.depositToVault(a,10.0,any()) }
        verify(exactly=1) { stats.incrementWin("guild:$a","hill") }
        verify(exactly=0) { guilds.depositToVault(b,any(),any()) }
    }
    @Test fun `allied alt opponent produces no payout or win credit`() {
        every { guilds.allianceGraph() } returns mapOf(a to setOf(b),b to setOf(a))
        game()
        verify(exactly=0) { Bukkit.dispatchCommand(any(),any()); guilds.depositToVault(any(),any(),any()); stats.incrementWin(any(),any()) }
        verify(exactly=1) { store.reject(any(),any(),any(),match { it.contains("INSUFFICIENT_OPPOSITION") }) }
    }
    @Test fun `failed durable reservation never issues rewards or statistics`() {
        every { store.reserve(any(),any(),any(),any(),any(),any(),any()) } throws java.sql.SQLException("disk")
        game()
        verify(exactly=0) { Bukkit.dispatchCommand(any(),any()); guilds.depositToVault(any(),any(),any()); stats.incrementWin(any(),any()) }
    }
}
