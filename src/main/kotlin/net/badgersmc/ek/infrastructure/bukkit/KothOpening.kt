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
 * The moment a KOTH goes live: the arena's name across the screen, a low, sinister two-tone call with thunder, and a
 * burst of light on the point that rings outward in gold. Everything is sent only to [audience] (players following the
 * event or standing at the arena), so it never reaches the rest of the server, and nothing in the world is touched.
 */
object KothOpening {
    private val GOLD = Particle.DustOptions(Color.fromRGB(255, 207, 31), 1.6f)
    private val CRIMSON = Particle.DustOptions(Color.fromRGB(192, 38, 29), 1.4f)

    private data class Cue(val delayTicks: Long, val sound: Sound, val volume: Float, val pitch: Float)

    /**
     * The opening call, built only from vanilla sounds: a beacon shutting down at half pitch as a low drone, a warden's
     * roar pitched far down as the bellow, the same roar a fifth higher as the answer, and thunder rolling in behind.
     */
    private val CALL = listOf(
        Cue(0L, Sound.BLOCK_BEACON_DEACTIVATE, 1.0f, 0.5f),
        Cue(0L, Sound.ENTITY_WARDEN_ROAR, 0.75f, 0.5f),
        Cue(4L, Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 0.4f, 0.6f),
        Cue(14L, Sound.ENTITY_WARDEN_ROAR, 0.7f, 0.75f),
        Cue(14L, Sound.ENTITY_ELDER_GUARDIAN_CURSE, 0.35f, 0.5f),
    )

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
        // A sinister call: a dying drone underneath, a deep bellow, then a second, higher bellow answering it.
        // Each sound plays at the player's own position so everyone hears it, wherever they stand.
        CALL.forEach { (delay, sound, volume, pitch) ->
            val play = { audience.filter { it.isOnline }.forEach { it.playSound(it.location, sound, SoundCategory.MASTER, volume, pitch) } }
            if (delay == 0L) play() else plugin.server.scheduler.runTaskLater(plugin, Runnable { play() }, delay)
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
        // Rings of gold and crimson spread out across the point.
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
