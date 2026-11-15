package net.badgersmc.ek.application

import net.badgersmc.ek.config.CaptureSoundsConfig
import net.badgersmc.ek.config.SoundCueConfig

/** One note of a KOTH sound: a namespaced sound played at the listener, [delayTicks] after the sound starts. */
data class SoundLayer(val sound: String, val volume: Float, val pitch: Float, val delayTicks: Long = 0, val master: Boolean = false)

/** Every sound a KOTH plays, by the name staff use to hear it with /ekoth sound. */
object KothSounds {
    /**
     * The opening call when a KOTH goes live, built only from vanilla sounds: a beacon shutting down at half pitch as a
     * low drone, a warden's roar pitched far down as the bellow, the same roar a fifth higher as the answer, and thunder
     * rolling in behind.
     */
    val OPENING = listOf(
        SoundLayer("minecraft:block.beacon.deactivate", 1.0f, 0.5f, 0L, master = true),
        SoundLayer("minecraft:entity.warden.roar", 0.75f, 0.5f, 0L, master = true),
        SoundLayer("minecraft:entity.lightning_bolt.thunder", 0.4f, 0.6f, 4L, master = true),
        SoundLayer("minecraft:entity.warden.roar", 0.7f, 0.75f, 14L, master = true),
        SoundLayer("minecraft:entity.elder_guardian.curse", 0.35f, 0.5f, 14L, master = true),
    )

    /** In the order a KOTH plays them. */
    val NAMES = listOf("start", "capture", "lost", "victory")

    private val ALIASES = mapOf("opening" to "start", "live" to "start", "win" to "victory", "leave" to "lost")

    /** The canonical name for [requested], or null when no KOTH sound goes by it. */
    fun resolve(requested: String?): String? = requested?.lowercase()?.let { ALIASES[it] ?: it }?.takeIf { it in NAMES }

    /** The notes of [name] as currently configured; empty when every note of it is switched off. */
    fun cue(name: String, sounds: CaptureSoundsConfig): List<SoundLayer> = when (resolve(name)) {
        "start" -> OPENING
        "capture" -> listOf(sounds.capture, sounds.captureAccent, sounds.captureAccent2, sounds.captureAccent3).layers()
        "lost" -> listOf(sounds.lost).layers()
        "victory" -> listOf(sounds.win, sounds.winAccent, sounds.winAccent2, sounds.winAccent3).layers()
        else -> emptyList()
    }

    private fun List<SoundCueConfig>.layers() =
        filter { it.sound.isNotBlank() && it.volume > 0f }.map { SoundLayer(it.sound, it.volume, it.pitch, it.delayTicks) }
}
