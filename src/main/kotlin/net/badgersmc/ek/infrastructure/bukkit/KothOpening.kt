package net.badgersmc.ek.infrastructure.bukkit

import net.badgersmc.ek.config.DisplayConfig
import net.badgersmc.ek.domain.KothEvent
import net.badgersmc.ek.infrastructure.i18n.arenaComponent
import net.badgersmc.ek.infrastructure.i18n.arenaMsg
import net.badgersmc.nexus.i18n.LangService
import net.kyori.adventure.title.Title
import org.bukkit.Bukkit
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.SoundCategory
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin
import java.time.Duration
import kotlin.math.cos
import kotlin.math.sin

/**
 * The moment a KOTH goes live: the arena's name across the screen, a war horn with thunder rolling in behind it, and a
 * burst of light on the point that rings outward in gold. Everything is sent only to [audience] (players following the
 * event or standing at the arena), so it never reaches the rest of the server, and nothing in the world is touched.
 */
object KothOpening {
    private val GOLD = Particle.DustOptions(Color.fromRGB(255, 207, 31), 1.6f)
    private val CRIMSON = Particle.DustOptions(Color.fromRGB(192, 38, 29), 1.4f)

    fun play(plugin: JavaPlugin, lang: LangService, display: DisplayConfig, event: KothEvent, audience: List<Player>) {
        if (audience.isEmpty()) return
        val name = event.arena.name.arenaComponent()
        if (display.startTitle) {
            val title = Title.title(
                lang.arenaMsg("koth.live_title", "koth_name" to name),
                lang.msg("koth.live_subtitle"),
                Title.Times.times(Duration.ofMillis(250), Duration.ofMillis(2600), Duration.ofMillis(900)),
            )
            audience.forEach { it.showTitle(title) }
        }
        if (!display.startEffects) return
        val world = Bukkit.getWorld(event.arena.zone.worldName) ?: return
        val zone = event.arena.zone
        val point = event.movingPoint?.let { Location(world, it.first, it.second, it.third) }
            ?: Location(world, (zone.minX + zone.maxX) / 2, zone.objectiveY, (zone.minZ + zone.maxZ) / 2)
        val viewers = audience.filter { it.isOnline && it.world.uid == world.uid }
        // The horn sounds from each player's own position so everyone hears it, wherever they stand.
        audience.forEach { player ->
            player.playSound(player.location, Sound.ITEM_GOAT_HORN_SOUND_0, SoundCategory.MASTER, 0.9f, 0.85f)
            player.playSound(player.location, Sound.BLOCK_BEACON_ACTIVATE, SoundCategory.MASTER, 0.7f, 0.7f)
        }
        viewers.forEach { player ->
            // The flash takes a colour on newer clients and nothing on older ones.
            val flashAt = point.clone().add(0.0, 1.0, 0.0)
            runCatching { player.spawnParticle(Particle.FLASH, flashAt, 1, 0.0, 0.0, 0.0, 0.0, Color.WHITE) }
                .recoverCatching { player.spawnParticle(Particle.FLASH, flashAt, 1) }
            player.spawnParticle(Particle.TOTEM_OF_UNDYING, point.clone().add(0.0, 1.0, 0.0), 80, 0.6, 1.2, 0.6, 0.45)
            for (y in 0 until 24) {
                player.spawnParticle(Particle.END_ROD, point.clone().add(0.0, 0.5 + y * 0.5, 0.0), 1, 0.05, 0.1, 0.05, 0.0)
            }
        }
        // Thunder rolls in a moment later, then rings of gold and crimson spread out across the point.
        plugin.server.scheduler.runTaskLater(plugin, Runnable {
            audience.filter { it.isOnline }.forEach { it.playSound(it.location, Sound.ENTITY_LIGHTNING_BOLT_THUNDER, SoundCategory.MASTER, 0.45f, 0.75f) }
        }, 6L)
        for (wave in 0 until 4) {
            plugin.server.scheduler.runTaskLater(plugin, Runnable {
                val radius = 2.0 + wave * 2.5
                val steps = (radius * 10).toInt()
                viewers.filter { it.isOnline }.forEach { player ->
                    for (i in 0 until steps) {
                        val angle = 2 * Math.PI * i / steps
                        val at = point.clone().add(cos(angle) * radius, 0.2, sin(angle) * radius)
                        player.spawnParticle(Particle.DUST, at, 1, 0.0, 0.0, 0.0, 0.0, if (wave % 2 == 0) GOLD else CRIMSON)
                    }
                }
            }, 2L + wave * 3L)
        }
    }
}
