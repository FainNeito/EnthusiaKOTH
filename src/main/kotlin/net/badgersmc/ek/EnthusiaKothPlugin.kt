package net.badgersmc.ek

import net.badgersmc.ek.di.ServiceModule
import org.bukkit.plugin.java.JavaPlugin

class EnthusiaKothPlugin : JavaPlugin() {
    companion object {
        internal const val MIN_LUMAGUILDS_VERSION = "2.1.8"

        internal fun isVersionAtLeast(current: String, minimum: String): Boolean {
            fun numericParts(version: String): List<Int>? {
                val prefix = Regex("^(\\d+(?:\\.\\d+)*)").find(version.trim())?.value ?: return null
                return prefix.split('.').mapNotNull { it.toIntOrNull() }
            }

            val currentParts = numericParts(current) ?: return false
            val minimumParts = numericParts(minimum) ?: return false
            val count = maxOf(currentParts.size, minimumParts.size)
            for (index in 0 until count) {
                val currentPart = currentParts.getOrElse(index) { 0 }
                val minimumPart = minimumParts.getOrElse(index) { 0 }
                if (currentPart > minimumPart) return true
                if (currentPart < minimumPart) return false
            }
            return true
        }

        /** Returns the startup refusal reason for the given LumaGuilds state, or null when KOTH may enable. */
        internal fun lumaGuildsStartupProblem(version: String?, enabled: Boolean): String? {
            if (version == null || !isVersionAtLeast(version, MIN_LUMAGUILDS_VERSION)) {
                return "EnthusiaKOTH requires LumaGuilds >= $MIN_LUMAGUILDS_VERSION because guild rewards use GuildLookup.getGuildMemberIds(); " +
                    "found ${version ?: "no loaded LumaGuilds plugin"}"
            }
            if (!enabled) {
                return "EnthusiaKOTH requires LumaGuilds to be enabled; LumaGuilds $version is installed but not enabled " +
                    "(it likely failed during its own startup - check earlier LumaGuilds errors, e.g. a missing Vault dependency)"
            }
            return null
        }
    }

    lateinit var services: ServiceModule
        private set

    override fun onEnable() {
        dataFolder.mkdirs()
        saveDefaultConfig()

        val lumaGuilds = server.pluginManager.getPlugin("LumaGuilds")
        val lumaProblem = lumaGuildsStartupProblem(lumaGuilds?.pluginMeta?.version, lumaGuilds?.isEnabled == true)
        if (lumaProblem != null) {
            logger.severe(lumaProblem)
            server.pluginManager.disablePlugin(this)
            return
        }

        try {
            services = ServiceModule(this)
        } catch (error: Throwable) {
            logger.severe("EnthusiaKOTH failed to initialize: ${error.message}")
            error.printStackTrace()
            server.pluginManager.disablePlugin(this)
            return
        }
        server.scheduler.runTaskTimer(this, services::refreshRewardReadiness, 1L, 20L)
        server.scheduler.runTaskTimer(this, services.kothService::tick, 20L, 20L)
        server.scheduler.runTaskTimer(this, services.scheduleService::tick, 20L, 20L)
        server.scheduler.runTaskTimer(this, services::retryPendingPaymentRecovery, 20L * 5L, 20L * 30L)
        logger.info("EnthusiaKOTH enabled (v${pluginMeta.version})")
    }

    override fun onDisable() {
        if (::services.isInitialized) {
            services.kothService.shutdown()
            services.shutdown()
        }
        logger.info("EnthusiaKOTH disabled")
    }
}
