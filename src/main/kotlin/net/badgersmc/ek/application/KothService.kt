package net.badgersmc.ek.application

import net.badgersmc.ek.infrastructure.i18n.arenaComponent
import net.badgersmc.ek.infrastructure.i18n.arenaMsg
import net.badgersmc.ek.config.EnthusiaKothConfig
import net.badgersmc.ek.config.ProgressBarConfig
import net.badgersmc.ek.domain.CaptureLeaveBehavior
import net.badgersmc.ek.domain.CaptureZone
import net.badgersmc.ek.domain.EventKind
import net.badgersmc.ek.domain.EventState
import net.badgersmc.ek.domain.KothArena
import net.badgersmc.ek.domain.KothEvent
import net.badgersmc.ek.domain.PrivateTestAccess
import net.badgersmc.ek.domain.TeamId
import net.badgersmc.ek.domain.TeamMode
import net.badgersmc.ek.infrastructure.discord.DiscordWebhookService
import net.badgersmc.ek.infrastructure.display.ZoneBorderService
import net.badgersmc.ek.infrastructure.lumaguilds.LumaGuildsAdapter
import net.badgersmc.ek.infrastructure.persistence.EventQueueStore
import net.badgersmc.ek.infrastructure.persistence.ScheduleClaimStatus
import net.badgersmc.ek.infrastructure.persistence.ScheduleStateStore
import net.badgersmc.ek.infrastructure.persistence.SqlStatsRepository
import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.entity.Player
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ThreadLocalRandom
import kotlin.math.min

enum class QueuedEventState { READY, ACTIVATING, COMPLETED }

data class QueuedEvent(
    val arenaId: String,
    val startSource: EventKind,
    val scheduledAt: Instant,
    val attempts: Int = 0,
    val nextAttemptAt: Instant = scheduledAt,
    val teamMode: TeamMode = TeamMode.SOLO,
    val state: QueuedEventState = QueuedEventState.READY,
    val occurrenceId: String? = null,
    val activationId: UUID? = null,
)

internal data class CaptureControlStep(
    val left: TeamId? = null,
    val entered: TeamId? = null,
    val progressCurrent: Boolean = false,
)

enum class CancellationReason {
    ADMINISTRATIVE,
    PRIVATE_OWNER,
    RELOAD,
    PLUGIN_DISABLE,
    STARTUP_DELAY_CANCELLED,
    ACTIVATION_FAILURE,
}

class KothService(
    private val cfgLoader: () -> EnthusiaKothConfig,
    private val stats: SqlStatsRepository,
    private val economy: PlayerEconomy,
    private val guilds: LumaGuildsAdapter,
    private val displayService: DisplayService,
    private val objectiveMarkerService: ObjectiveMarkerService = ObjectiveMarkerService(),
    private val fireworkService: FireworkCelebrationService,
    private val discordWebhook: DiscordWebhookService,
    private val zoneBorderService: ZoneBorderService,
    private val lang: net.badgersmc.nexus.i18n.LangService,
    private val arenaResolver: (String) -> KothArena?,
    private val queueStore: EventQueueStore,
    private val clock: Clock,
    private val logger: (String, Throwable?) -> Unit,
    private val eventTerminated: (UUID) -> Unit = {},
    private val notificationsEnabled: (Player) -> Boolean = { true },
    private val toggleNotifications: (Player) -> Boolean = { true },
    private val lifecycleSink: (net.badgersmc.ek.api.KothLifecycleEvent) -> Unit = {},
    private val arenasOverlap: (KothArena, KothArena) -> Boolean = EventConcurrency::overlaps,
    private val protectionStore: RewardProtectionStore? = null,
    private val progressionSink: MatchProgressionSink? = null,
    private val progressionPolicy: () -> ProgressionPolicy = { ProgressionPolicy() },
    private val captureAudience: (KothEvent, Player) -> Boolean = { _, _ -> false },
    private val rewardedStartIssues: (KothArena) -> List<String> = { emptyList() },
    private val arenaIdentity: (KothArena) -> String = { it.id },
) {
    private val protectedMatches = mutableMapOf<UUID, ProtectedMatch>()
    private val recipientBudgets = mutableMapOf<UUID, Int>()
    private val progressionMatches = mutableMapOf<UUID, ProgressionPolicy>()
    private val matchArenaIdentities = mutableMapOf<UUID, String>()
    private val matchParticipants = mutableMapOf<UUID, MutableMap<UUID, String>>()

    private fun checkRewardedStart(arena: KothArena, kind: EventKind): Boolean {
        if (kind == EventKind.ADMIN) return true
        val issues = runCatching { rewardedStartIssues(arena) }.getOrElse { logger("KOTH readiness unavailable", it); return false }
        if (issues.isNotEmpty()) logger("KOTH '${arena.id}' start blocked: ${issues.joinToString("; ")}", null)
        return issues.isEmpty()
    }

    /** Monitor-only evidence; combat rules continue to come from MaceGuard's current rotation. */
    fun recordCombat(attacker: Player, victim: Player) {
        allEvents().filter { !it.isPrivateTest && it.state == EventState.ACTIVE }.forEach { event ->
            val match = protectedMatches[event.id] ?: return@forEach
            val players = if (event.arena.family.equals("moving", true)) playersNearMovingPoint(event) else playersInStaticCaptureZone(event)
            if (attacker !in players || victim !in players) return@forEach
            refreshProtection(match)
            val a = teamFor(attacker, event) ?: return@forEach
            val b = teamFor(victim, event) ?: return@forEach
            match.combat(a, b, clock.instant().epochSecond)
        }
    }

    fun membershipChanged(player: UUID) { protectedMatches.values.forEach { it.invalidPlayers.add(player) } }
    fun relationChanged() { protectedMatches.values.forEach { it.relationChanged() } }

    private fun initializeProtection(event: KothEvent) {
        progressionMatches.putIfAbsent(event.id, progressionPolicy().also { it.validate() })
        if (!event.isPrivateTest && progressionMatches[event.id]?.enabled == true)
            matchArenaIdentities.putIfAbsent(event.id, arenaIdentity(event.arena))
        val policy = cfgLoader().rewardProtection
        if (!policy.enabled || event.isPrivateTest || event.id in protectedMatches) return
        val mode = if (event.arena.ignoreFactions) TeamMode.SOLO else event.teamMode
        val roster = runCatching { guilds.protectionRoster() }
            .onFailure { logger("KOTH alliance roster unavailable; rewards disabled for ${event.id}", it) }.getOrNull()
        protectedMatches[event.id] = ProtectedMatch(policy, mode, roster)
    }

    private fun accountEvidence(player: Player): AccountEvidence? = runCatching {
        AccountEvidence(Instant.ofEpochMilli(player.firstPlayed), player.getStatistic(org.bukkit.Statistic.PLAY_ONE_MINUTE).toLong() / 20)
    }.getOrNull()

    private fun refreshProtection(match: ProtectedMatch) {
        match.reconcile(runCatching { guilds.allianceGraph() }.getOrNull())
    }

    private fun protectedWinner(event: KothEvent, candidate: TeamId?): TeamId? {
        val match = protectedMatches[event.id] ?: return candidate
        refreshProtection(match)
        // Validate online identities again before selecting recipients/settlement.
        Bukkit.getOnlinePlayers().forEach { teamFor(it, event) }
        val opponents = candidate?.let(match::qualifyingOpponents).orEmpty()
        val reason = when {
            !match.available -> "ALLIANCE_DATA_UNAVAILABLE"
            candidate == null -> "NO_WINNER"
            opponents.isEmpty() -> "INSUFFICIENT_OPPOSITION"
            match.qualifyingSideCount(candidate) < cfgLoader().fairness.minimumParticipatingTeams -> "INSUFFICIENT_SIDES"
            else -> null
        }
        return try {
            if (reason != null) {
                protectionStore?.reject(event.id, event.arena.id, clock.instant(), "$reason; ${match.audit()}")
                logger("KOTH ${event.id} reward withheld: $reason", null)
                null
            } else if (protectionStore?.reserve(event.id, event.arena.id, match.side(candidate!!), opponents,
                clock.instant(), match.config, "recipientCommandBudget=${match.config.maximumRecipientCommands}; ${match.audit()}") == true) candidate
            else { logger("KOTH ${event.id} reward withheld: repeated opponent, duplicate or unavailable audit store", null); null }
        } catch (error: Exception) {
            logger("KOTH ${event.id} reward history failed; no rewards or win credit issued", error); null
        }
    }
    fun notificationEnabled(player: Player): Boolean = notificationsEnabled(player)
    fun toggleNotification(player: Player): Boolean = toggleNotifications(player)
    companion object {
        private const val QUEUE_RETRY_SECONDS = 10L

        internal fun movingPointAt(
            elapsedSeconds: Double,
            squareSize: Double,
            speedBlocksPerSecond: Double,
            centerX: Double,
            centerZ: Double,
        ): Pair<Double, Double> {
            val size = squareSize.coerceAtLeast(0.1)
            val half = size / 2.0
            val perimeter = size * 4.0
            val distance = (elapsedSeconds.coerceAtLeast(0.0) * speedBlocksPerSecond) % perimeter
            return when {
                distance < size -> (centerX - half + distance) to (centerZ - half)
                distance < size * 2.0 -> (centerX + half) to (centerZ - half + distance - size)
                distance < size * 3.0 -> (centerX + half - (distance - size * 2.0)) to (centerZ + half)
                else -> (centerX - half) to (centerZ + half - (distance - size * 3.0))
            }
        }

        internal fun displayProgress(event: KothEvent, now: Instant): Float {
            if (event.arena.family.equals("conquest", ignoreCase = true) ||
                event.arena.family.equals("moving", ignoreCase = true) || event.arena.family.equals("score", true)
            ) {
                val duration = event.arena.durationSeconds.coerceAtLeast(1).toDouble()
                val remaining = Duration.between(now, event.endsAt).seconds.coerceAtLeast(0).toDouble()
                return (remaining / duration).coerceIn(0.0, 1.0).toFloat()
            }
            val controller = event.currentController ?: return 0.0f
            val target = event.arena.captureSeconds.coerceAtLeast(1).toDouble()
            return ((event.scores[controller] ?: 0.0) / target).coerceIn(0.0, 1.0).toFloat()
        }

        internal fun applyMovingScore(event: KothEvent, teamsInZone: List<TeamId>) {
            val controller = teamsInZone.singleOrNull()
            event.currentController = controller
            event.leaveAnnouncedFor = null
            if (controller != null) event.addScore(controller, 1.0)
        }

        internal fun isMovingCaptureEligible(
            zone: CaptureZone,
            playerX: Double,
            playerY: Double,
            playerZ: Double,
            objectiveX: Double,
            objectiveZ: Double,
        ): Boolean {
            if (playerY !in zone.minY..zone.maxY) return false
            val dx = playerX - objectiveX
            val dz = playerZ - objectiveZ
            return dx * dx + dz * dz <= zone.radiusSq
        }

        internal fun supportsPrivateTesting(arena: KothArena): Boolean =
            !arena.family.equals("conquest", ignoreCase = true)

        internal fun applyCaptureControl(event: KothEvent, teamsInZone: List<TeamId>): CaptureControlStep {
            val arena = event.arena
            val previous = event.currentController
            if (previous != null) {
                val contested = arena.contestWhenMultipleCappers && teamsInZone.size > 1
                if (previous in teamsInZone && !contested) {
                    return CaptureControlStep(progressCurrent = true)
                }

                when (arena.leaveBehavior) {
                    CaptureLeaveBehavior.RESET -> event.scores.remove(previous)
                    CaptureLeaveBehavior.DECAY -> {
                        val next = ((event.scores[previous] ?: 0.0) - arena.decayPerSecond).coerceAtLeast(0.0)
                        if (next <= 0.0) event.scores.remove(previous) else event.scores[previous] = next
                    }
                    CaptureLeaveBehavior.PAUSE -> Unit
                }
                event.currentController = null
            }

            val entered = teamsInZone.singleOrNull()
            if (entered != null) event.currentController = entered
            return CaptureControlStep(left = previous, entered = entered)
        }
    }

    private val events = linkedMapOf<UUID, KothEvent>()
    /** Compatibility primary view. Use allEvents/eventForArena for multiple events. */
    var activeEvent: KothEvent?
        get() = synchronized(this) { events.values.firstOrNull() }
        private set(value) { if (value != null) events[value.id] = value }
    @Synchronized fun allEvents(): List<KothEvent> = events.values.toList()
    fun eventForArena(id: String): KothEvent? = allEvents().firstOrNull { it.arena.id == id }
    fun conflicts(arena: KothArena): Boolean {
        val current = allEvents()
        return current.isNotEmpty() && EventConcurrency.conflicts(arena, current, cfgLoader().maxConcurrentEvents, arenasOverlap)
    }
    fun eventAt(location: org.bukkit.Location, playerId: UUID? = null): KothEvent? = allEvents().firstOrNull {
        (playerId == null || it.isParticipant(playerId)) && (it.arena.zone.contains(location) || it.arena.protectedRegion?.contains(location) == true)
    }
    fun privateEvent(playerId: UUID? = null): KothEvent? = allEvents().firstOrNull {
        it.isPrivateTest && (playerId == null || it.isParticipant(playerId))
    }
    private var processingQueue = false
    private fun lifecycle(event: KothEvent, kind: net.badgersmc.ek.api.KothLifecycle, winner: TeamId? = null, reason: String? = null) {
        fun snapshot(team: TeamId?) = team?.let { net.badgersmc.ek.api.KothTeamSnapshot(it.mode.name, it.id) }
        runCatching { lifecycleSink(net.badgersmc.ek.api.KothLifecycleEvent(kind, net.badgersmc.ek.api.KothLifecycleSnapshot(
            event.id, event.arena.id, event.arena.family, event.isPrivateTest, event.startsAt, event.endsAt,
            snapshot(event.currentController), snapshot(winner), java.util.Collections.unmodifiableMap(event.scores.mapKeys { snapshot(it.key)!! }), reason,
        ))) }.onFailure { logger("KOTH lifecycle observer failed for " + event.id, it) }
    }
    private val reminderCounters = mutableMapOf<String, Int>()
    private val eventQueue = queueStore.load().toMutableList()
    private var queueDirty = false
    private var suppressNextQueueProcess = false
    private val discordLastUpdates = mutableMapOf<UUID, Long>()
    @Volatile var lastCancellationRefundPending: Boolean = false
        private set

    fun tick() {
        if (queueDirty) persistQueue()
        val now = clock.instant()
        val cfg = cfgLoader()
        allEvents().forEach { event ->
            when (event.state) {
                EventState.STARTING -> if (!now.isBefore(event.startsAt)) {
                    try { activateEvent(event, cfg) } catch (error: Throwable) {
                        logger("KOTH delayed activation failed for " + event.arena.id, error)
                        cancelEvent(event, CancellationReason.ACTIVATION_FAILURE, announce = false)
                    }
                }
                EventState.ACTIVE -> tickActive(event, cfg)
                else -> Unit
            }
        }
        processQueue()
    }

    private fun activateEvent(event: KothEvent, cfg: EnthusiaKothConfig) {
        check(event.isPrivateTest || checkRewardedStart(event.arena, event.source)) { "Rewarded start readiness changed" }
        event.state = EventState.ACTIVE
        initializeProtection(event)
        lifecycle(event, net.badgersmc.ek.api.KothLifecycle.STARTED)
        if (events[event.id] !== event || event.state != EventState.ACTIVE) return
        discordLastUpdates.remove(event.id)
        sendEventMessage(
            event,
            lang.arenaMsg("koth.begin", "koth_name" to event.arena.name.arenaComponent(), "location" to locString(event.arena.zone)),
        )
        if (!event.isPrivateTest) {
            discordWebhook.sendStart(event.arena.name, locString(event.arena.zone))
            if (cfg.display.zoneBorder) zoneBorderService.show(event.arena.zone, event.id.toString())
        }
    }

    private fun activateQueuedEvent(event: KothEvent, cfg: EnthusiaKothConfig, recovered: Boolean) {
        check(checkRewardedStart(event.arena, event.source)) { "Rewarded queued start readiness changed" }
        event.state = EventState.ACTIVE
        initializeProtection(event)
        lifecycle(event, net.badgersmc.ek.api.KothLifecycle.STARTED)
        if (events[event.id] !== event || event.state != EventState.ACTIVE) return
        discordLastUpdates.remove(event.id)
        runActivationStep(event, if (recovered) "recovery start announcement" else "start announcement") {
            sendEventMessage(
                event,
                lang.arenaMsg("koth.begin", "koth_name" to event.arena.name.arenaComponent(), "location" to locString(event.arena.zone)),
            )
        }
        if (!recovered && !event.isPrivateTest) {
            runActivationStep(event, "Discord start notification") {
                discordWebhook.sendStart(event.arena.name, locString(event.arena.zone))
            }
        } else if (recovered) {
            logger(
                "Recovering durable KOTH activation ${event.id} for '${event.arena.id}' with a player-visible recovery announcement; " +
                    "Discord start delivery is not replayed",
                null,
            )
        }
        if (!event.isPrivateTest && cfg.display.zoneBorder) {
            runActivationStep(event, "zone border display") { zoneBorderService.show(event.arena.zone, event.id.toString()) }
        }
    }

    private inline fun runActivationStep(event: KothEvent, label: String, block: () -> Unit) {
        runCatching(block).onFailure { error ->
            logger("KOTH '${event.arena.id}' $label failed during durable activation; the event remains active", error)
        }
    }

    private fun tickActive(event: KothEvent, cfg: EnthusiaKothConfig) {
        val arena = event.arena
        val now = clock.instant()
        if (!now.isBefore(event.endsAt)) {
            finishEvent(event, resolveWinner(event))
            return
        }
        val playersInZone = if (arena.family.equals("moving", true)) playersNearMovingPoint(event) else playersInStaticCaptureZone(event)

        if (cfg.display.actionbar && cfg.progressBar.enabled && event.currentController != null) {
            val progress = progressBar(event, cfg.progressBar)
            playersInZone.filter { event.isPrivateTest || (notificationsEnabled(it) && captureAudience(event, it)) }.forEach { it.sendActionBar(progress) }
        }

        val reminder = cfg.reminders
        if (reminder.enabled) {
            val counter = reminderCounters.getOrPut(event.arena.id) { reminder.intervalSeconds }
            if (counter <= 0) {
                reminderCounters[event.arena.id] = reminder.intervalSeconds
                sendEventMessage(
                    event,
                    lang.arenaMsg(
                        "koth.reminder",
                        "koth_name" to event.arena.name.arenaComponent(),
                        "capper" to (capperName(event) ?: "None"),
                        "time_left" to formatTime(event.endsAt.epochSecond - now.epochSecond),
                    ),
                    captureOnly = true,
                )
            } else reminderCounters[event.arena.id] = counter - 1
        }

        protectedMatches[event.id]?.let(::refreshProtection)
        if (progressionMatches[event.id]?.enabled == true && !event.isPrivateTest) {
            val participants=matchParticipants.getOrPut(event.id) { mutableMapOf() }
            playersInZone.forEach { player -> participants.putIfAbsent(player.uniqueId,teamFor(player,event)?.storageKey() ?: "unknown") }
        }
        val teamsInZone = resolveTeams(playersInZone, event)
        event.scoringParticipation.observe(teamsInZone)
        val previousScores = event.scores.toMap()
        val previousController = event.currentController
        when {
            arena.family.equals("score", true) -> applyMovingScore(event, teamsInZone)
            arena.family.equals("moving", true) -> applyMovingScore(event, teamsInZone)
            arena.family.equals("conquest", true) -> tickConquest(event, arena, teamsInZone, playersInZone)
            else -> tickCaptureFamily(event, arena, teamsInZone)
        }
        if (previousController != event.currentController) lifecycle(event, net.badgersmc.ek.api.KothLifecycle.CONTROL_CHANGED)
        if (events[event.id] !== event || event.state != EventState.ACTIVE) return
        val controller = event.currentController
        protectedMatches[event.id]?.observe(teamsInZone, controller,
            controller != null && (event.scores[controller] ?: 0.0) > (previousScores[controller] ?: 0.0))
        if (controller != null && (event.scores[controller] ?: 0.0) > (previousScores[controller] ?: 0.0)) {
            event.scoringParticipation.record(controller, playersInZone.filter { teamFor(it, event) == controller }.map { it.uniqueId }.toSet())
        }
        if (!arena.family.equals("score", true) && !arena.family.equals("moving", true) && !arena.family.equals("conquest", true) &&
            controller != null && (event.scores[controller] ?: 0.0) >= arena.captureSeconds) {
            finishEvent(event, controller)
            return
        }

        val contested = teamsInZone.size > 1
        val timeLeft = formatTime(event.endsAt.epochSecond - now.epochSecond)
        val recipients = eventRecipients(event)
        objectiveMarkerService.show(
            event,
            recipients,
            showStaticObjective = event.isPrivateTest && cfg.privateTesting.showObjectiveParticles,
        )
        displayService.showKoth(
            arena.id,
            capperName(event),
            timeLeft,
            contested,
            displayProgress(event, now),
            recipients,
            !event.isPrivateTest,
            cfg.display,
            event,
            arena.name,
        )

        if (!event.isPrivateTest && cfg.discord.enabled && cfg.discord.liveUpdateSeconds > 0) {
            val last = discordLastUpdates[event.id]
            if (last == null || now.epochSecond - last >= cfg.discord.liveUpdateSeconds) {
                discordLastUpdates[event.id] = now.epochSecond
                discordWebhook.sendLiveUpdate(event.arena.name, event.currentController, contested, timeLeft)
            }
        }
    }

    private fun tickCaptureFamily(event: KothEvent, arena: KothArena, teamsInZone: List<TeamId>) {
        val step = applyCaptureControl(event, teamsInZone)
        step.left?.let { controller -> announceLeave(event, controller) }
        step.entered?.let { team ->
            event.leaveAnnouncedFor = null
            sendEventMessage(
                event,
                lang.arenaMsg(
                    "koth.enter",
                    "entered" to teamName(team),
                    "koth_name" to arena.name.arenaComponent(),
                    "captime" to formatTime(arena.captureSeconds.toLong()),
                ),
                captureOnly = true,
            )
        }
        if (step.progressCurrent) tickCaptureProgress(event, arena)
    }

    private fun announceLeave(event: KothEvent, controller: TeamId) {
        if (event.leaveAnnouncedFor == controller) return
        event.leaveAnnouncedFor = controller
        sendEventMessage(
            event,
            lang.arenaMsg(
                "koth.leave",
                "left" to teamName(controller),
                "koth_name" to event.arena.name.arenaComponent(),
                "time_left" to formatTime(event.arena.captureSeconds.toLong()),
            ),
            captureOnly = true,
        )
    }

    private fun tickConquest(event: KothEvent, arena: KothArena, teamsInZone: List<TeamId>, players: List<Player>) {
        if (teamsInZone.size != 1) {
            event.currentController = null
            return
        }
        val team = teamsInZone.first()
        event.currentController = team
        val capperCount = players.count { player -> teamFor(player, event) == team }
        val multiplier = arena.captureSpeedBonuses[capperCount] ?: 1.0
        event.addScore(team, multiplier)
    }

    private fun tickCaptureProgress(event: KothEvent, arena: KothArena) {
        val controller = event.currentController ?: return
        val next = min(arena.captureSeconds.toDouble(), (event.scores[controller] ?: 0.0) + 1.0)
        event.scores[controller] = next
        val secondsLeft = arena.captureSeconds - next.toInt()
        if (secondsLeft > 0 && secondsLeft % 30 == 0) {
            sendEventMessage(
                event,
                lang.arenaMsg(
                    "koth.capping",
                    "capping" to teamName(controller),
                    "koth_name" to arena.name.arenaComponent(),
                    "time_left" to formatTime(secondsLeft.toLong()),
                ),
                captureOnly = true,
            )
        }
    }

    private fun playersInStaticCaptureZone(event: KothEvent): List<Player> =
        Bukkit.getOnlinePlayers().filter { player ->
            player.isValid && !player.isDead && player.gameMode != GameMode.SPECTATOR &&
                event.isParticipant(player.uniqueId) && event.arena.zone.containsCircular(player.location)
        }

    private fun playersNearMovingPoint(event: KothEvent): List<Player> {
        val arena = event.arena
        val point = calculateMovingPoint(event)
        event.movingPoint = point
        if (Bukkit.getWorld(arena.zone.worldName) == null) return emptyList()
        val (x, _, z) = point
        return Bukkit.getOnlinePlayers().filter { player ->
            val location = player.location
            player.isValid && !player.isDead && player.gameMode != GameMode.SPECTATOR &&
                event.isParticipant(player.uniqueId) && player.world.name == arena.zone.worldName &&
                isMovingCaptureEligible(arena.zone, location.x, location.y, location.z, x, z)
        }
    }

    private fun calculateMovingPoint(event: KothEvent): Triple<Double, Double, Double> {
        val world = Bukkit.getWorld(event.arena.zone.worldName) ?: return Triple(0.0, 80.0, 0.0)
        val center = event.arena.zone.center(world)
        val elapsed = (clock.millis() - event.startsAt.toEpochMilli()).coerceAtLeast(0) / 1000.0
        val (x, z) = movingPointAt(
            elapsed,
            event.arena.movingSquareSize,
            event.arena.movingSpeedBlocksPerSecond,
            center.x,
            center.z,
        )
        return Triple(x, center.y, z)
    }

    private fun resolveWinner(event: KothEvent): TeamId? {
        val (winner, score) = event.scores.maxByOrNull { it.value } ?: return null
        if (score <= 0.0 || event.scores.count { it.value == score } > 1) return null
        return winner
    }

    private fun finishEvent(event: KothEvent, candidate: TeamId?) {
        if (events[event.id] !== event) return
        val winner = protectedWinner(event, candidate.takeIf { event.isPrivateTest || event.scoringParticipation.teamCount() >= cfgLoader().fairness.minimumParticipatingTeams })
        event.state = EventState.COMPLETED
        val wasContested = event.scores.size > 1
        val frozenProgression = progressionMatches[event.id]
        lifecycle(event, net.badgersmc.ek.api.KothLifecycle.COMPLETED, winner)
        try {
            frozenProgression?.takeIf { it.enabled }?.let { policy ->
                runCompletionStep(event, "verified progression") {
                    val protection = protectedMatches[event.id]
                    val qualified = winner?.let { event.scoringParticipation.eligible(it, policy.contributionPercent) }.orEmpty()
                    val scored = event.scoringParticipation.snapshot().flatMap { (team, ledger) -> ledger.map { (id, seconds) ->
                        MatchContribution(id, team.storageKey(), seconds, team == winner && id in qualified &&
                            protection != null && id !in protection.invalidPlayers && id !in protection.ineligibleAccounts)
                    } }
                    val scoredIds=scored.map { it.player }.toSet()
                    val contributions=scored + matchParticipants[event.id].orEmpty().filterKeys { it !in scoredIds }
                        .map { (id,team) -> MatchContribution(id,team,0,false) }
                    progressionSink?.complete(VerifiedMatch(event.id, event.arena.id, event.arena.family, event.source.name,
                        clock.instant(), winner?.storageKey(), winner?.let { protection?.opponentGroups(it) }.orEmpty(),
                        contributions, winner?.let { protection?.oppositionSeconds(it) } ?: 0,
                        protection?.allianceChanged ?: true, "protectionPolicy=${protection?.config}; ${protection?.audit() ?: "PROTECTION_REQUIRED"}",
                        winner?.let { protection?.evidence(it) } ?: ContestEvidence(),
                        winner?.let { protection?.side(it) }.orEmpty(), matchArenaIdentities[event.id] ?: event.arena.id), policy)
                    contributions.map { it.player }.distinct().forEach { id -> Bukkit.getPlayer(id)?.sendMessage("Your KOTH result is available in /ekoth results.") }
                }
            }
            event.paymentReceipt?.let { receipt ->
                runCompletionStep(event, "payment settlement") {
                    if (!receipt.settle()) {
                        logger(
                            "KOTH '${event.arena.id}' completed but its paid-start journal could not be marked SETTLED; manual reconciliation is required",
                            null,
                        )
                    }
                }
            }
            if (winner != null) {
                runCompletionStep(event, "winner announcement") {
                    sendEventMessage(
                        event,
                        lang.arenaMsg("koth.capture", "captured" to teamName(winner), "koth_name" to event.arena.name.arenaComponent()),
                    )
                }
                if (!event.isPrivateTest) {
                    runCompletionStep(event, "timestamped win") { stats.recordTimedWin(event.id, winner.storageKey(), event.arena.id, clock.instant()) }
                    runCompletionStep(event, "statistics update") {
                        stats.incrementWin(winner.storageKey(), event.arena.id)
                        stats.save()
                    }
                    runCompletionStep(event, "reward execution") {
                        if (frozenProgression?.enabled != true) executeRewards(event, winner)
                    }
                }
            } else {
                runCompletionStep(event, "no-winner announcement") {
                    sendEventMessage(event, lang.arenaMsg("koth.no_winner", "koth_name" to event.arena.name.arenaComponent()))
                }
            }
        } finally {
            cleanupEvent(event)
        }

        if (winner != null && !event.isPrivateTest) {
            runCompletionStep(event, "Discord capture notification") {
                discordWebhook.sendCapture(event.arena.name, winner, wasContested)
            }
            runCompletionStep(event, "firework celebration") {
                fireworkService.celebrate(event.arena.zone)
            }
        }
        processQueue()
    }

    private inline fun runCompletionStep(event: KothEvent, label: String, block: () -> Unit) {
        runCatching(block).onFailure { error ->
            logger("KOTH '${event.arena.id}' $label failed during completion", error)
        }
    }

    private fun cleanupEvent(event: KothEvent) {
        protectedMatches.remove(event.id)
        progressionMatches.remove(event.id)
        matchArenaIdentities.remove(event.id)
        matchParticipants.remove(event.id)
        recipientBudgets.remove(event.id)
        event.clearScores()
        event.currentController = null
        events.remove(event.id)
        discordLastUpdates.remove(event.id)
        reminderCounters.remove(event.arena.id)
        runCatching { eventTerminated(event.id) }
            .onFailure { logger("KOTH '${event.arena.id}' restriction cleanup failed", it) }
        runCatching { displayService.clear(event.arena.id) }
            .onFailure { logger("KOTH '${event.arena.id}' display cleanup failed", it) }
        runCatching { zoneBorderService.hide(event.id.toString()) }
            .onFailure { logger("KOTH '${event.arena.id}' zone-border cleanup failed", it) }
    }

    private fun executeRewards(event: KothEvent, winner: TeamId) {
        protectedMatches[event.id]?.let { recipientBudgets[event.id] = it.config.maximumRecipientCommands }
        val arena = event.arena
        val name = sanitizeName(teamName(winner))
        cfgLoader().rewards[(arena.rewardFamily ?: arena.family).lowercase()]?.let { reward ->
            if (winner.mode == TeamMode.GUILD && reward.guildVaultMoney > 0.0) {
                val deposited = runCatching { guilds.depositToVault(winner.id, reward.guildVaultMoney, "KOTH win reward") }
                    .onFailure { logger("Guild reward deposit threw for ${winner.id} amount ${reward.guildVaultMoney}", it) }
                    .getOrDefault(false)
                if (!deposited) logger("Guild reward deposit failed for ${winner.id} amount ${reward.guildVaultMoney}", null)
            } else if (winner.mode == TeamMode.SOLO && reward.soloVaultMoney > 0.0) {
                val deposited = runCatching { economy.deposit(winner.id, reward.soloVaultMoney) }
                    .onFailure { logger("Solo Vault reward deposit threw for ${winner.id} amount ${reward.soloVaultMoney}", it) }
                    .getOrDefault(false)
                if (!deposited) logger("Solo Vault reward deposit failed for ${winner.id} amount ${reward.soloVaultMoney}", null)
            }
        }
        arena.rewards.forEach { command ->
            runCatching { executeRewardCommand(command, event, name, winner) }
                .onFailure { logger("KOTH reward command failed for '${arena.id}'", it) }
        }
        arena.chancedRewards.forEach { (command, chance) ->
            if (ThreadLocalRandom.current().nextDouble() * 100.0 < chance) {
                runCatching { executeRewardCommand(command, event, name, winner) }
                    .onFailure { logger("KOTH chanced reward command failed for '${arena.id}'", it) }
            }
        }
    }

    private fun executeRewardCommand(command: String, event: KothEvent, name: String, winner: TeamId) {
        val guildId = winner.id.takeIf { winner.mode == TeamMode.GUILD }
        if (executeBankReward(command, guildId)) return
        val resolved = command.replace("{PLAYER}", name).replace("{FACTION}", name).replace("{KOTH}", event.arena.id)
        if (resolved.contains("{CONTRIBUTORS}")) {
            event.scoringParticipation.eligible(winner, cfgLoader().fairness.contributorMinimumPercent)
                .mapNotNull { Bukkit.getPlayer(it) }.filter { teamFor(it, event) == winner }
                .sortedWith(compareByDescending<Player> { event.scoringParticipation.playerSeconds(winner, it.uniqueId) }.thenBy { it.uniqueId.toString() })
                .filter { consumeRecipientBudget(event) }
                .forEach { player ->
                    val rewardCommand = resolved.replace("{CONTRIBUTORS}", player.name).replace("{ALL_ONLINE}", player.name)
                    runCatching {
                        if (!Bukkit.dispatchCommand(Bukkit.getConsoleSender(), rewardCommand)) {
                            logger("KOTH reward command was rejected: $rewardCommand", null)
                        }
                    }.onFailure { logger("KOTH contributor reward command failed for ${player.uniqueId}: $rewardCommand", it) }
                }
            return
        }
        if (resolved.contains("{ALL_ONLINE}") && guildId != null) {
            if (event.id in protectedMatches) {
                executeRewardCommand(command.replace("{ALL_ONLINE}", "{CONTRIBUTORS}"), event, name, winner)
                return
            }
            guilds.onlineMembers(guildId).forEach { member ->
                val command = resolved.replace("{ALL_ONLINE}", member.name)
                if (!Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command)) logger("KOTH reward command was rejected: $command", null)
            }
        } else if (resolved.contains("{ALL_ONLINE}")) {
            if (event.id in protectedMatches) {
                executeRewardCommand(command.replace("{ALL_ONLINE}", "{CONTRIBUTORS}"), event, name, winner)
                return
            }
            Bukkit.getOnlinePlayers().forEach { player ->
                val command = resolved.replace("{ALL_ONLINE}", player.name)
                if (!Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command)) logger("KOTH reward command was rejected: $command", null)
            }
        } else if (!Bukkit.dispatchCommand(Bukkit.getConsoleSender(), resolved)) {
            logger("KOTH reward command was rejected: $resolved", null)
        }
    }

    private fun executeBankReward(command: String, guildId: UUID?): Boolean {
        if (!command.startsWith("bank", ignoreCase = true)) return false
        if (guildId == null) return true
        val amount = command.split("\\s+".toRegex()).getOrNull(1)?.toDoubleOrNull() ?: return true
        val deposited = runCatching { guilds.depositToVault(guildId, amount, "KOTH guild reward") }
            .onFailure { logger("KOTH bank reward threw for $guildId amount $amount", it) }
            .getOrDefault(false)
        if (!deposited) logger("KOTH bank reward deposit failed for $guildId amount $amount", null)
        return true
    }

    @Synchronized
    fun startEvent(
        arena: KothArena,
        durationOverride: Int? = null,
        kind: EventKind = EventKind.PLAYER_COMMAND,
        delaySeconds: Int = 0,
        paymentReceipt: PaymentReceipt? = null,
        teamMode: TeamMode = TeamMode.SOLO,
    ): Boolean {
        if (conflicts(arena)) return false
        if (!checkRewardedStart(arena, kind)) return false
        val cfg = cfgLoader()
        if (!cfg.locks.state.allows(kind)) return false
        val now = clock.instant()
        val start = now.plusSeconds(delaySeconds.coerceAtLeast(0).toLong())
        val event = KothEvent(
            id = UUID.randomUUID(),
            arena = arena,
            startsAt = start,
            endsAt = start.plusSeconds((durationOverride ?: arena.durationSeconds).toLong()),
            state = if (delaySeconds > 0) EventState.STARTING else EventState.ACTIVE,
            teamMode = if (arena.ignoreFactions) TeamMode.SOLO else teamMode,
            paymentReceipt = paymentReceipt,
            source = kind,
        )
        activeEvent = event
        if (event.state == EventState.ACTIVE) {
            try {
                activateEvent(event, cfg)
            } catch (error: Throwable) {
                logger("KOTH '${arena.id}' failed during activation", error)
                cancelEvent(
                    event,
                    CancellationReason.ACTIVATION_FAILURE,
                    announce = false,
                    advanceQueue = false,
                )
                return false
            }
        }
        return true
    }

    @Synchronized
    fun queueStart(
        arena: KothArena,
        kind: EventKind = EventKind.SCHEDULED,
        scheduledAt: Instant = clock.instant(),
        teamMode: TeamMode = TeamMode.SOLO,
        occurrenceId: String? = null,
    ): Boolean {
        if (queueDirty && !persistQueue()) return false
        if (occurrenceId != null && eventQueue.any { it.occurrenceId == occurrenceId }) return true

        val queued = QueuedEvent(
            arenaId = arena.id,
            startSource = kind,
            scheduledAt = scheduledAt,
            teamMode = if (arena.ignoreFactions) TeamMode.SOLO else teamMode,
            occurrenceId = occurrenceId,
        )
        eventQueue += queued
        if (!persistQueue()) {
            eventQueue.remove(queued)
            queueDirty = false
            return false
        }
        return true
    }

    @Synchronized
    fun processQueue() {
        if (suppressNextQueueProcess) { suppressNextQueueProcess = false; return }
        if (processingQueue || (queueDirty && !persistQueue())) return
        processingQueue = true
        try {
            while (true) {
                val next = eventQueue.firstOrNull() ?: return
                val arena = arenaResolver(next.arenaId)
                if (next.state != QueuedEventState.COMPLETED && arena != null && conflicts(arena)) return
                val before = next
                when (next.state) {
                    QueuedEventState.COMPLETED -> if (!compactCompleted(next)) return
                    QueuedEventState.ACTIVATING -> resumeActivating(next)
                    QueuedEventState.READY -> processReady(next)
                }
                if (eventQueue.firstOrNull() == before || eventQueue.firstOrNull()?.arenaId == before.arenaId) return
            }
        } finally { processingQueue = false }
    }

    private fun processReady(next: QueuedEvent) {
        val now = clock.instant()
        if (now.isBefore(next.nextAttemptAt)) return
        if (!scheduledClaimAllowsActivation(next)) return

        val arena = arenaResolver(next.arenaId)
        if (arena == null) {
            removeInvalidQueued(next)
            return
        }
        if (!cfgLoader().locks.state.allows(next.startSource)) {
            eventQueue[0] = retryLater(next, now)
            if (!persistQueue()) {
                logger("Failed to persist retry timing for queued KOTH '${next.arenaId}'; the prior durable READY state is still recoverable", null)
            }
            return
        }

        val activating = next.copy(
            state = QueuedEventState.ACTIVATING,
            activationId = next.activationId ?: UUID.randomUUID(),
        )
        eventQueue[0] = activating
        if (!persistQueue()) {
            eventQueue[0] = next
            queueDirty = false
            logger("Deferred queued KOTH '${next.arenaId}' because READY -> ACTIVATING could not be persisted", null)
            return
        }

        if (!startDurableActivation(activating, arena, recovered = false)) {
            retryActivation(activating, now)
        }
    }

    private fun resumeActivating(next: QueuedEvent) {
        val now = clock.instant()
        if (!scheduledClaimAllowsActivation(next)) return
        val arena = arenaResolver(next.arenaId)
        if (arena == null) {
            removeInvalidQueued(next)
            return
        }
        if (!cfgLoader().locks.state.allows(next.startSource) || next.activationId == null) {
            retryActivation(next, now)
            return
        }
        if (!startDurableActivation(next, arena, recovered = true)) {
            retryActivation(next, now)
        }
    }

    private fun startDurableActivation(next: QueuedEvent, arena: KothArena, recovered: Boolean): Boolean {
        if (conflicts(arena)) return false
        if (!checkRewardedStart(arena, next.startSource)) return false
        val cfg = cfgLoader()
        if (!cfg.locks.state.allows(next.startSource)) return false
        val activationId = next.activationId ?: return false
        val now = clock.instant()
        val event = KothEvent(
            id = activationId,
            arena = arena,
            startsAt = now,
            endsAt = now.plusSeconds(arena.durationSeconds.toLong()),
            state = EventState.ACTIVE,
            teamMode = if (arena.ignoreFactions) TeamMode.SOLO else next.teamMode,
            source = next.startSource,
        )
        activeEvent = event
        activateQueuedEvent(event, cfg, recovered)

        val completed = next.copy(state = QueuedEventState.COMPLETED)
        eventQueue[0] = completed
        if (!persistQueue()) {
            logger(
                "KOTH '${next.arenaId}' activation ${event.id} is active but COMPLETED could not be persisted; " +
                    "the durable ACTIVATING record will resume the same activation id after a hard crash",
                null,
            )
            return true
        }
        compactCompleted(completed)
        return true
    }

    private fun retryActivation(next: QueuedEvent, now: Instant) {
        eventQueue[0] = retryLater(next, now)
        if (!persistQueue()) {
            logger(
                "Failed to persist ACTIVATING -> READY retry for KOTH '${next.arenaId}'; " +
                    "the durable ACTIVATING record remains recoverable and will retry the same occurrence after restart",
                null,
            )
        }
    }

    private fun compactCompleted(completed: QueuedEvent): Boolean {
        if (eventQueue.firstOrNull() != completed) return true
        eventQueue.removeAt(0)
        if (persistQueue()) return true
        eventQueue.add(0, completed)
        logger(
            "Could not compact completed queued KOTH '${completed.arenaId}'; its durable COMPLETED marker will prevent replay after restart",
            null,
        )
        return false
    }

    private fun removeInvalidQueued(next: QueuedEvent) {
        eventQueue.removeAt(0)
        if (!persistQueue()) {
            eventQueue.add(0, next)
            logger("Could not durably remove invalid queued KOTH '${next.arenaId}'; keeping it until the removal can be persisted", null)
            return
        }
        logger("Removing permanently invalid queued KOTH '${next.arenaId}'", null)
    }

    private fun scheduledClaimAllowsActivation(event: QueuedEvent): Boolean {
        if (event.startSource != EventKind.SCHEDULED || event.occurrenceId == null) return true
        val scheduleStore = queueStore as? ScheduleStateStore ?: return true
        return runCatching {
            when (scheduleStore.claimStatus("start:${event.occurrenceId}")) {
                ScheduleClaimStatus.PENDING -> false
                ScheduleClaimStatus.COMMITTED, null -> true
            }
        }.getOrElse { error ->
            logger("Could not verify schedule claim for queued KOTH '${event.arenaId}'; activation remains deferred", error)
            false
        }
    }

    private fun retryLater(event: QueuedEvent, now: Instant): QueuedEvent = event.copy(
        attempts = if (event.attempts == Int.MAX_VALUE) Int.MAX_VALUE else event.attempts + 1,
        nextAttemptAt = now.plusSeconds(QUEUE_RETRY_SECONDS),
        state = QueuedEventState.READY,
        activationId = null,
    )

    fun nextQueued(): QueuedEvent? = synchronized(this) { eventQueue.firstOrNull { it.state != QueuedEventState.COMPLETED } }
    fun queuedEvents(): List<QueuedEvent> = synchronized(this) { eventQueue.filter { it.state != QueuedEventState.COMPLETED } }
    internal fun queuedOccurrenceIds(): Set<String> = synchronized(this) { eventQueue.mapNotNull { it.occurrenceId }.toSet() }

    @Synchronized
    fun clearQueue(): Boolean {
        val previous = eventQueue.toList()
        eventQueue.clear()
        if (persistQueue()) return true
        eventQueue.addAll(previous)
        return false
    }

    private fun persistQueue(): Boolean {
        return try {
            queueStore.save(eventQueue)
            queueDirty = false
            true
        } catch (error: Throwable) {
            queueDirty = true
            logger("Failed to persist KOTH queue; durable state was not advanced", error)
            false
        }
    }

    @Synchronized
    fun startPrivateTest(
        arena: KothArena,
        ownerId: UUID,
        cfg: EnthusiaKothConfig,
        teamMode: TeamMode,
        access: PrivateTestAccess,
        quickTiming: Boolean,
    ): Boolean {
        if (!supportsPrivateTesting(arena)) return false
        if (activeEvent != null || !cfg.locks.state.allows(EventKind.PRIVATE_TEST)) return false
        val testing = cfg.privateTesting
        val lobbySeconds = testing.lobbySeconds.coerceAtLeast(0)
        val duration = if (quickTiming) {
            testing.quickMatchDurationSeconds.takeIf { it > 0 } ?: arena.durationSeconds
        } else {
            arena.durationSeconds
        }
        val capture = if (quickTiming) {
            testing.quickCaptureSeconds.takeIf { it > 0 } ?: arena.captureSeconds
        } else {
            arena.captureSeconds
        }
        val now = clock.instant()
        val event = KothEvent(
            id = UUID.randomUUID(),
            arena = arena.copy(durationSeconds = duration, captureSeconds = capture),
            startsAt = now.plusSeconds(lobbySeconds.toLong()),
            endsAt = now.plusSeconds((lobbySeconds + duration).toLong()),
            state = if (lobbySeconds > 0) EventState.STARTING else EventState.ACTIVE,
            teamMode = if (arena.ignoreFactions) TeamMode.SOLO else teamMode,
            owner = ownerId,
            isPrivateTest = true,
            privateTestAccess = access,
            lobbySeconds = lobbySeconds,
        )
        event.join(ownerId)
        activeEvent = event
        if (event.state == EventState.ACTIVE) lifecycle(event, net.badgersmc.ek.api.KothLifecycle.STARTED)
        Bukkit.getPlayer(ownerId)?.let { owner ->
            if (lobbySeconds > 0) {
                val key = if (access == PrivateTestAccess.PERMISSION_JOIN) "private.lobby_open_staff" else "private.lobby_open_self"
                owner.sendMessage(lang.msg(key, "seconds" to lobbySeconds.toString()))
            } else {
                owner.sendMessage(lang.msg("private.started", "duration" to duration.toString(), "capture" to capture.toString()))
            }
        }
        return true
    }

    @Synchronized
    fun forceEnd(
        reason: CancellationReason = CancellationReason.ADMINISTRATIVE,
        announce: Boolean = reason == CancellationReason.ADMINISTRATIVE,
        arenaId: String? = null,
    ): Boolean {
        val selected = if (arenaId == null) allEvents().take(1) else allEvents().filter { it.arena.id == arenaId }
        return selected.firstOrNull()?.let { cancelEvent(it, reason, announce) } ?: false
    }

    private fun cancelEvent(
        event: KothEvent,
        reason: CancellationReason,
        announce: Boolean,
        advanceQueue: Boolean = reason != CancellationReason.RELOAD && reason != CancellationReason.PLUGIN_DISABLE,
    ): Boolean {
        if (events[event.id] !== event || event.state in setOf(EventState.COMPLETED, EventState.CANCELLED, EventState.ENDING)) return false
        lastCancellationRefundPending = false
        if (announce) {
            runCatching { sendEventMessage(event, lang.arenaMsg("koth.ended", "koth_name" to event.arena.name.arenaComponent())) }
                .onFailure { logger("KOTH '${event.arena.id}' cancellation announcement failed", it) }
        }
        val refunded = refundPayment(event, reason)
        lastCancellationRefundPending = !refunded
        event.state = EventState.CANCELLED
        lifecycle(event, net.badgersmc.ek.api.KothLifecycle.CANCELLED, reason = reason.name)
        cleanupEvent(event)
        if (advanceQueue) processQueue()
        return true
    }

    private fun refundPayment(event: KothEvent, reason: CancellationReason): Boolean {
        val receipt = event.paymentReceipt ?: return true
        if (!receipt.beginRefund()) {
            val complete = receipt.isRefunded() || !receipt.isOutstanding()
            if (!complete) notifyPendingRefund(receipt, reason)
            return complete
        }
        val refunded = try {
            economy.deposit(receipt.payerId, receipt.amount)
        } catch (error: Throwable) {
            logger(
                "KOTH ${reason.name.lowercase()} refund threw for ${receipt.payerId} amount ${receipt.amount}; journal remains REFUNDING for manual reconciliation",
                error,
            )
            notifyPendingRefund(receipt, reason)
            return false
        }
        if (!receipt.completeRefund(refunded)) {
            logger(
                "KOTH ${reason.name.lowercase()} refund result could not be persisted for ${receipt.payerId} amount ${receipt.amount}; durable state remains REFUNDING for manual reconciliation",
                null,
            )
            notifyPendingRefund(receipt, reason)
            return false
        }
        if (!refunded) notifyPendingRefund(receipt, reason)
        return refunded
    }

    private fun notifyPendingRefund(receipt: PaymentReceipt, reason: CancellationReason) {
        logger(
            "KOTH ${reason.name.lowercase()} refund remains unresolved for ${receipt.payerId} amount ${receipt.amount}; durable payment-journal recovery or manual reconciliation is required",
            null,
        )
        runCatching { Bukkit.getPlayer(receipt.payerId) }
            .onFailure { logger("Could not notify ${receipt.payerId} about the pending KOTH refund", it) }
            .getOrNull()
            ?.sendMessage(lang.msg("command.error.refund_failed"))
    }

    private fun resolveTeams(players: List<Player>, event: KothEvent): List<TeamId> =
        players.mapNotNull { teamFor(it, event) }.distinct()

    private fun consumeRecipientBudget(event: KothEvent): Boolean {
        val remaining = recipientBudgets[event.id] ?: return true
        if (remaining <= 0) return false
        recipientBudgets[event.id] = remaining - 1
        return true
    }

    private fun teamFor(player: Player, event: KothEvent): TeamId? {
        val match = protectedMatches[event.id] ?: return event.resolveTeam(player.uniqueId, guilds.playerGuildId(player))
        if (!match.accountEligible(accountEvidence(player), clock.instant())) {
            match.ineligibleAccounts.add(player.uniqueId)
            return null
        }
        return runCatching { match.team(player.uniqueId, guilds.playerGuildIds(player.uniqueId)) }
            .getOrElse { match.reconcile(null); null }
    }

    private fun teamName(team: TeamId): String = if (team.mode == TeamMode.GUILD) {
        guilds.guildName(team.id) ?: team.id.toString().take(8)
    } else Bukkit.getOfflinePlayer(team.id).name ?: team.id.toString().take(8)

    private fun sanitizeName(raw: String): String = raw
        .replace(Regex("§[0-9a-fk-orA-FK-OR]"), "")
        .replace(Regex("<[^>]*>"), "")
        .filter { it.isLetterOrDigit() || it == '_' }

    fun capperName(event: KothEvent): String? = event.currentController?.let(::teamName)

    private fun locString(zone: CaptureZone): String {
        val world = Bukkit.getWorld(zone.worldName) ?: return "?,?,?"
        val center = zone.center(world)
        return "${center.blockX}, ${center.blockY}, ${center.blockZ}"
    }

    private fun progressBar(event: KothEvent, cfg: ProgressBarConfig): net.kyori.adventure.text.Component {
        val progress = if (event.arena.family.equals("moving", true) || event.arena.family.equals("conquest", true) || event.arena.family.equals("score", true)) {
            displayProgress(event, clock.instant()).toDouble()
        } else {
            val maximum = event.arena.captureSeconds.coerceAtLeast(1).toDouble()
            ((event.scores.values.maxOrNull() ?: 0.0) / maximum).coerceIn(0.0, 1.0)
        }
        val filled = (progress * cfg.length).toInt()
        val bar = cfg.character.repeat(filled) + lang.raw("progress_bar.empty_color") +
            cfg.character.repeat((cfg.length - filled).coerceAtLeast(0))
        return lang.msg("progress_bar.format", "progress_bar" to bar)
    }

    private fun eventRecipients(event: KothEvent, captureOnly: Boolean = true): List<Player> = if (event.isPrivateTest) {
        event.participants.mapNotNull { Bukkit.getPlayer(it) }
    } else Bukkit.getOnlinePlayers().filter { notificationsEnabled(it) && (!captureOnly || captureAudience(event, it)) }

    private fun sendEventMessage(event: KothEvent, message: net.kyori.adventure.text.Component, captureOnly: Boolean = false) {
        if (event.isPrivateTest) eventRecipients(event, captureOnly).forEach { it.sendMessage(message) }
        else {
            eventRecipients(event, captureOnly).forEach { it.sendMessage(message) }
            Bukkit.getConsoleSender().sendMessage(message)
        }
    }

    private fun formatTime(seconds: Long): String {
        val safe = seconds.coerceAtLeast(0)
        val minutes = safe / 60
        val remainder = safe % 60
        return if (minutes > 0) "${minutes}m ${remainder}s" else "${remainder}s"
    }

    fun shutdown(reason: CancellationReason = CancellationReason.PLUGIN_DISABLE) {
        if (reason == CancellationReason.RELOAD || reason == CancellationReason.PLUGIN_DISABLE) {
            suppressNextQueueProcess = true
        }
        var refundPending = false
        allEvents().forEach { cancelEvent(it, reason, announce = false, advanceQueue = false); refundPending = refundPending || lastCancellationRefundPending }
        lastCancellationRefundPending = refundPending
        persistQueue()
    }
}
