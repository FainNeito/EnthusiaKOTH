package net.badgersmc.ek.config

import net.badgersmc.ek.domain.LockState
import net.badgersmc.ek.infrastructure.restriction.RuleSet
import java.time.ZoneId

data class EnthusiaKothConfig(
    val configVersion: Int = 8,
    val timezone: ZoneId = ZoneId.of("America/New_York"),
    val manualStart: ManualStartConfig = ManualStartConfig(),
    val schedule: ScheduleConfig = ScheduleConfig(),
    val flares: FlareConfig = FlareConfig(),
    val progressBar: ProgressBarConfig = ProgressBarConfig(),
    val reminders: ReminderConfig = ReminderConfig(),
    val arenas: Map<String, ArenaConfig> = emptyMap(),
    val rewards: Map<String, RewardConfig> = emptyMap(),
    val discord: DiscordConfig = DiscordConfig(),
    val display: DisplayConfig = DisplayConfig(),
    val rules: FamilyRulesConfig = FamilyRulesConfig(),
    val privateTesting: PrivateTestingConfig = PrivateTestingConfig(),
    val locks: LockConfig = LockConfig(),
    val fairness: FairnessConfig = FairnessConfig(),
    val rewardProtection: net.badgersmc.ek.application.RewardProtectionConfig = net.badgersmc.ek.application.RewardProtectionConfig(),
    val followWarzoneCombat: Boolean = true,
    val maxConcurrentEvents: Int = 1,
    val seasonStart: String? = null,
    val captureNotificationRegions: List<String> = listOf("spawn", "warzone", "market"),
    val captureSounds: CaptureSoundsConfig = CaptureSoundsConfig(),
    val arenaBuild: ArenaBuildConfig = ArenaBuildConfig(),
)

/** Arenas that build themselves from their base form when a KOTH starts (see /ekoth build). */
data class ArenaBuildConfig(
    val enabled: Boolean = true,
    val buildSeconds: Int = 4,
    val teardownSeconds: Int = 3,
    val previewSeconds: Int = 10,
    /** How far below and above the capture point the recorded region reaches. */
    val below: Int = 8,
    val above: Int = 24,
    /** Margin around the capture zone when the arena has no protection region. */
    val margin: Int = 16,
    val maxBlocks: Int = 20_000,
    val maxScanBlocks: Long = 2_000_000,
)

data class SoundCueConfig(val sound: String, val volume: Float, val pitch: Float, val delayTicks: Long = 0)
data class CaptureSoundsConfig(
    val enabled: Boolean = true,
    val cooldownSeconds: Int = 5,
    val capture: SoundCueConfig = SoundCueConfig("minecraft:block.note_block.pling", 0.19f, 0.95f, 0),
    val captureAccent: SoundCueConfig = SoundCueConfig("minecraft:block.note_block.pling", 0.20f, 1.12f, 4),
    val captureAccent2: SoundCueConfig = SoundCueConfig("minecraft:block.amethyst_block.chime", 0.07f, 1.0f, 5),
    val captureAccent3: SoundCueConfig = SoundCueConfig("", 0f, 1f, 0),
    val lost: SoundCueConfig = SoundCueConfig("minecraft:block.note_block.bass", 0.35f, 0.8f),
    val win: SoundCueConfig = SoundCueConfig("minecraft:block.note_block.bell", 0.45f, 0.75f),
    val winAccent: SoundCueConfig = SoundCueConfig("minecraft:entity.player.levelup", 0.55f, 1f, 3),
    val winAccent2: SoundCueConfig = SoundCueConfig("minecraft:block.note_block.bell", 0.38f, 1f, 8),
    val winAccent3: SoundCueConfig = SoundCueConfig("minecraft:block.note_block.bell", 0.42f, 1.25f, 13),
)

data class FairnessConfig(
    val contributorMinimumPercent: Double = 10.0,
    val starterCooldownSeconds: Int = 0,
    val minimumOnlineTeams: Int = 0,
    val minimumParticipatingTeams: Int = 0,
)

data class ManualStartConfig(val enabled: Boolean = true, val basicCost: Double = 0.0, val advancedCost: Double = 0.0, val delaySeconds: Int = 0)
data class ScheduleConfig(val enabled: Boolean = false, val zone: ZoneId = ZoneId.of("America/New_York"), val preStartWarningSeconds: Int = 300, val times: List<String> = listOf("00:00", "08:00", "16:00"), val blitz: BlitzConfig = BlitzConfig())

/**
 * A blitz chains short KOTH rounds back to back, each in a different arena, starting at fixed times on chosen days.
 * Round slots are fixed in advance (start + round index x (round + break)), so every round is a durable scheduled occurrence.
 */
data class BlitzConfig(
    val enabled: Boolean = false,
    val days: Set<java.time.DayOfWeek> = setOf(java.time.DayOfWeek.SATURDAY, java.time.DayOfWeek.SUNDAY),
    val startTimes: List<String> = listOf("18:00"),
    val rounds: Int = 4,
    val roundSeconds: Int = 300,
    val captureSeconds: Int = 60,
    val breakSeconds: Int = 60,
    /** Arena IDs in rotation order; empty uses every enabled arena. */
    val arenas: List<String> = emptyList(),
) {
    val slotSeconds: Long get() = roundSeconds.toLong() + breakSeconds.toLong()
}
data class FlareConfig(val enabled: Boolean = true, val item: FlareItemConfig = FlareItemConfig())
data class FlareItemConfig(val material: String = "REDSTONE_TORCH", val name: String = "&c{KOTH} Koth Flare", val lore: List<String> = listOf("&7Right-Click to start a &c{KOTH} &7koth!"))
data class ProgressBarConfig(val enabled: Boolean = true, val length: Int = 10, val character: String = "|")
data class ReminderConfig(val enabled: Boolean = true, val intervalSeconds: Int = 300)

data class ArenaConfig(
    val enabled: Boolean = true,
    val family: String = "capture",
    val world: String = "world",
    val center: PositionConfig = PositionConfig(),
    val protectedRegion: ProtectedRegionConfig = ProtectedRegionConfig(),
    val worldGuardRegion: String? = null,
    val radius: Double = 5.0,
    val durationSeconds: Int = 900,
    val captureSeconds: Int = 120,
    val leaveBehavior: String = "RESET",
    val decayPerSecond: Double = 1.0,
    val movingSquareSize: Double = 20.0,
    val movingSpeedBlocksPerSecond: Double = 1.0,
    val ignoreFactions: Boolean = false,
    val contestWhenMultipleCappers: Boolean = true,
    val flaresMustBePlacedOnCap: Boolean = true,
    val schedule: List<String> = emptyList(),
    val rewards: List<String> = emptyList(),
    val chancedRewards: Map<String, Double> = emptyMap(),
    val captureSpeedBonuses: Map<Int, Double> = emptyMap(),
    val keepInventory: Boolean = false,
    val keepExperience: Boolean = true,
    val rewardFamily: String? = null,
    val displayName: String? = null,
)

data class PositionConfig(val x: Double = 0.0, val y: Double = 80.0, val z: Double = 0.0)
data class ProtectedRegionConfig(val corner1: PositionConfig = PositionConfig(-32.0, -64.0, -32.0), val corner2: PositionConfig = PositionConfig(32.0, 320.0, 32.0))
data class RewardConfig(val soloVaultMoney: Double = 0.0, val guildVaultMoney: Double = 0.0)
data class DiscordConfig(
    val enabled: Boolean = false,
    val webhookUrl: String = "",
    val preStartPingMinutes: Int = 10,
    val liveUpdateSeconds: Int = 60,
    val embeds: Map<DiscordMessageType, DiscordEmbedTemplate> = DiscordEmbedDefaults.templates,
    val startRoleId: String = "",
)
data class DisplayConfig(val zoneBorder: Boolean = true, val bossbar: Boolean = true, val actionbar: Boolean = true, val hologram: Boolean = false, val scoreboard: Boolean = false, val movingFloor: Boolean = true, val startTitle: Boolean = true, val startEffects: Boolean = true, val bossbarColor: String = "RED", val bossbarOverlay: String = "PROGRESS", val bossbarTitle: String = "")
data class FamilyRulesConfig(val rules: Map<String, RuleSet> = emptyMap())
data class PrivateTestingConfig(val lobbySeconds: Int = 0, val quickMatchDurationSeconds: Int = 120, val quickCaptureSeconds: Int = 15, val showObjectiveParticles: Boolean = true)
data class LockConfig(val state: LockState = LockState.UNLOCKED)
