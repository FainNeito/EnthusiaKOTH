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

    /** A note block's pitch for a note [semitones] above F#3, the lowest it plays (F#3 = 0.5, F#4 = 1.0, F#5 = 2.0). */
    private fun note(semitones: Int): Float = Math.pow(2.0, (semitones - 12) / 12.0).toFloat()
    private val C4 = note(6); private val E4 = note(10); private val G4 = note(13); private val C5 = note(18); private val E5 = note(22)

    private fun layer(tick: Long, sound: String, volume: Float, pitch: Float) = SoundLayer("minecraft:$sound", volume, pitch, tick)

    /**
     * Candidate victory sounds to audition in game with /ekoth sound victory-<name>. None of them plays at a real KOTH
     * until one is chosen and made the configured victory sound.
     */
    val VICTORY_DRAFTS: Map<String, List<SoundLayer>> = linkedMapOf(
        // Bright and classic: a bell arpeggio climbs a C major chord into the advancement fanfare and a firework burst.
        "triumph" to listOf(
            layer(0, "block.note_block.bell", 0.45f, C4), layer(0, "block.note_block.harp", 0.35f, C4),
            layer(3, "block.note_block.bell", 0.45f, E4), layer(6, "block.note_block.bell", 0.45f, G4),
            layer(9, "block.note_block.bell", 0.5f, C5), layer(9, "block.note_block.pling", 0.4f, C5),
            layer(12, "ui.toast.challenge_complete", 0.6f, 1.0f), layer(12, "entity.firework_rocket.large_blast", 0.5f, 1.0f),
            layer(17, "entity.firework_rocket.twinkle", 0.45f, 1.0f),
            layer(22, "block.note_block.bell", 0.5f, E5), layer(22, "block.amethyst_block.chime", 0.5f, 1.0f),
        ),
        // Regal: a church bell, a long horn call, the bell again, then a beacon lighting and a totem's flare under a chord.
        "coronation" to listOf(
            layer(0, "block.bell.use", 0.6f, 0.8f),
            layer(2, "item.goat_horn.sound.1", 0.7f, 1.0f),
            layer(22, "block.bell.use", 0.6f, 1.0f),
            layer(26, "block.beacon.activate", 0.6f, 1.2f),
            layer(28, "item.totem.use", 0.35f, 1.2f),
            layer(32, "block.note_block.bell", 0.45f, E4), layer(32, "block.note_block.bell", 0.45f, G4),
            layer(32, "block.note_block.bell", 0.5f, C5),
            layer(36, "ui.toast.challenge_complete", 0.5f, 1.0f),
        ),
        // Dark, like the opening call: thunder, the end portal opening and a dragon's cry, resolving to a major chord.
        "storm" to listOf(
            layer(0, "entity.lightning_bolt.thunder", 0.6f, 0.8f), layer(0, "item.trident.thunder", 0.5f, 1.0f),
            layer(4, "block.end_portal.spawn", 0.45f, 1.0f),
            layer(10, "entity.ender_dragon.growl", 0.4f, 1.3f),
            layer(22, "block.note_block.bass", 0.6f, C4), layer(22, "block.note_block.bell", 0.45f, C4),
            layer(26, "block.note_block.bell", 0.45f, G4),
            layer(30, "block.note_block.bell", 0.5f, C5), layer(30, "entity.player.levelup", 0.5f, 0.8f),
            layer(32, "entity.firework_rocket.large_blast", 0.5f, 0.8f),
        ),
        // Festive: three rockets go up, a quick chime run, then bursts and crackles overhead.
        "fireworks" to listOf(
            layer(0, "entity.firework_rocket.launch", 0.5f, 1.0f), layer(3, "entity.firework_rocket.launch", 0.5f, 1.2f),
            layer(6, "entity.firework_rocket.launch", 0.5f, 0.9f),
            layer(8, "block.note_block.pling", 0.35f, C4), layer(9, "block.note_block.pling", 0.35f, E4),
            layer(10, "block.note_block.pling", 0.35f, G4), layer(11, "block.note_block.pling", 0.35f, C5),
            layer(12, "block.note_block.pling", 0.4f, E5),
            layer(14, "entity.firework_rocket.large_blast", 0.6f, 1.0f), layer(16, "entity.firework_rocket.blast", 0.5f, 1.1f),
            layer(18, "entity.firework_rocket.large_blast", 0.55f, 0.9f),
            layer(20, "entity.firework_rocket.twinkle", 0.5f, 1.0f), layer(24, "entity.firework_rocket.twinkle", 0.45f, 1.2f),
            layer(24, "entity.player.levelup", 0.5f, 1.2f),
        ),
        // Magical: a swelling crystal hum, a chime climbing step by step, then a totem's flare and a level-up.
        "ascension" to listOf(
            layer(0, "block.amethyst_block.resonate", 0.5f, 0.8f), layer(0, "block.respawn_anchor.charge", 0.4f, 1.0f),
            layer(4, "block.note_block.chime", 0.4f, C4), layer(8, "block.note_block.chime", 0.4f, E4),
            layer(12, "block.note_block.chime", 0.4f, G4), layer(16, "block.note_block.chime", 0.45f, C5),
            layer(16, "block.beacon.activate", 0.5f, 1.0f),
            layer(20, "block.note_block.chime", 0.5f, E5), layer(20, "item.totem.use", 0.4f, 1.0f),
            layer(26, "block.note_block.bell", 0.5f, C5), layer(26, "entity.player.levelup", 0.5f, 1.0f),
        ),
    )

    /** The /ekoth sound names of the victory drafts, in audition order. */
    val DRAFT_NAMES = VICTORY_DRAFTS.keys.map { "victory-$it" }

    private val ALIASES = mapOf("opening" to "start", "live" to "start", "win" to "victory", "leave" to "lost")

    /** The canonical name for [requested], or null when no KOTH sound goes by it. */
    fun resolve(requested: String?): String? = requested?.lowercase()?.let { ALIASES[it] ?: it }?.takeIf { it in NAMES || it in DRAFT_NAMES }

    /** The notes of [name] as currently configured; empty when every note of it is switched off. */
    fun cue(name: String, sounds: CaptureSoundsConfig): List<SoundLayer> = when (val resolved = resolve(name)) {
        "start" -> OPENING
        "capture" -> listOf(sounds.capture, sounds.captureAccent, sounds.captureAccent2, sounds.captureAccent3).layers()
        "lost" -> listOf(sounds.lost).layers()
        "victory" -> listOf(sounds.win, sounds.winAccent, sounds.winAccent2, sounds.winAccent3).layers()
        null -> emptyList()
        else -> VICTORY_DRAFTS[resolved.removePrefix("victory-")].orEmpty()
    }

    private fun List<SoundCueConfig>.layers() =
        filter { it.sound.isNotBlank() && it.volume > 0f }.map { SoundLayer(it.sound, it.volume, it.pitch, it.delayTicks) }
}
