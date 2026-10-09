package net.badgersmc.ek.infrastructure.bukkit

import net.badgersmc.ek.application.*
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.plugin.java.JavaPlugin
import java.io.File

class ProgressionSettings(private val plugin: JavaPlugin) {
    private var current = ProgressionPolicy()
    init { reload() }
    fun policy(): ProgressionPolicy = current
    fun reload() {
        val file = File(plugin.dataFolder, "progression.yml")
        if (!file.exists()) plugin.saveResource("progression.yml", false)
        current = runCatching {
            val y = YamlConfiguration().also { it.load(file) }
            ProgressionPolicy(
                enabled = y.getBoolean("enabled", false), poolCents = y.getLong("pool-cents", 0),
                packageUnits = y.getInt("package-units", 0), contributionPercent = y.getDouble("contribution-percent", 10.0),
                minimumScoringSeconds = y.getLong("minimum-scoring-seconds", 120),
                minimumOppositionSeconds = y.getInt("minimum-opposition-seconds", 120),
                challenges = y.getConfigurationSection("challenges")?.getKeys(false).orEmpty().map { id ->
                    val p = "challenges.$id"
                    ChallengeRule(id, y.getInt("$p.wins"), y.getInt("$p.opponents"), y.getInt("$p.arenas"),
                        y.getInt("$p.days"), y.getLong("$p.seconds"), y.getString("$p.tag", "")!!,
                        y.getString("$p.lore-definition", "")!!, y.getBoolean("$p.exclusive"))
                },
                packages = y.getConfigurationSection("packages")?.getKeys(false).orEmpty().map { id ->
                    RewardPackage(id, y.getLong("pool-cents"), y.getString("packages.$id.lore-definition", "")!!, y.getInt("package-units"))
                }, guildXpCommand = y.getString("guild-xp-command", "")!!,
                minimumRosterAgeSeconds = y.getLong("minimum-roster-age-seconds", 0),
                integrity = IntegrityPolicy(
                    requireContest=y.getBoolean("integrity.require-contest",false),
                    minimumControlChanges=y.getInt("integrity.minimum-control-changes",1),
                    minimumOpposingScoreSeconds=y.getInt("integrity.minimum-opposing-score-seconds",30),
                    minimumReciprocalCombatSeconds=y.getInt("integrity.minimum-reciprocal-combat-seconds",10),
                    holdSuspicious=y.getBoolean("integrity.hold-suspicious",false),
                    patternWindowHours=y.getInt("integrity.pattern-window-hours",24),
                    patternMinimumMatches=y.getInt("integrity.pattern-minimum-matches",3),
                    playerDailyCents=y.getLong("integrity.player-daily-cents",0),
                    playerDailyUnits=y.getInt("integrity.player-daily-units",0),
                    sideDailyCents=y.getLong("integrity.side-daily-cents",0),
                    sideDailyUnits=y.getInt("integrity.side-daily-units",0),
                    requireReadiness=y.getBoolean("integrity.require-readiness",false),
                    acceptedArenas=y.getStringList("integrity.accepted-arenas").toSet(),
                ),
            ).also { it.validate() }
        }.getOrElse { plugin.logger.severe("Invalid progression.yml: progression disabled: ${it.message}"); ProgressionPolicy() }
    }
}
