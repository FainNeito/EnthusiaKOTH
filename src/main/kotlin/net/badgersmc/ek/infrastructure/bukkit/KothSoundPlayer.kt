package net.badgersmc.ek.infrastructure.bukkit

import net.badgersmc.ek.application.SoundLayer
import org.bukkit.SoundCategory
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin

/** Plays a KOTH sound's notes to each listener at their own position, so they hear it wherever they stand. */
object KothSoundPlayer {
    fun play(plugin: JavaPlugin, listeners: List<Player>, layers: List<SoundLayer>) {
        layers.forEach { layer ->
            val play = {
                listeners.filter { it.isOnline }.forEach { player ->
                    val category = if (layer.master) SoundCategory.MASTER else SoundCategory.PLAYERS
                    runCatching { player.playSound(player.location, layer.sound, category, layer.volume, layer.pitch) }
                        .onFailure { plugin.logger.warning("Could not play KOTH sound ${layer.sound}: ${it.message}") }
                }
            }
            if (layer.delayTicks == 0L) play() else plugin.server.scheduler.runTaskLater(plugin, Runnable { play() }, layer.delayTicks)
        }
    }
}
