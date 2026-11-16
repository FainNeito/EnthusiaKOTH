package net.badgersmc.ek.infrastructure.bukkit

import org.bukkit.configuration.file.YamlConfiguration
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class CaptureSoundConfigTest {
    @Test fun `defaults are quiet and per-cue overrides can disable a sound`() {
        val yaml = YamlConfiguration()
        val defaults = CaptureSoundConfigLoader.load(yaml)
        assertTrue(defaults.enabled)
        assertEquals("minecraft:block.note_block.pling", defaults.capture.sound)
        assertEquals("minecraft:block.amethyst_block.chime", defaults.captureAccent2.sound)
        assertEquals(listOf(0L, 4L, 5L), listOf(defaults.capture.delayTicks, defaults.captureAccent.delayTicks, defaults.captureAccent2.delayTicks))
        assertEquals(listOf(0L, 3L, 8L, 13L), listOf(defaults.win.delayTicks, defaults.winAccent.delayTicks, defaults.winAccent2.delayTicks, defaults.winAccent3.delayTicks))
        yaml.set("sounds.enabled", false)
        yaml.set("sounds.capture.sound", "")
        yaml.set("sounds.lost.volume", 0.2)
        val changed = CaptureSoundConfigLoader.load(yaml)
        assertFalse(changed.enabled); assertEquals("", changed.capture.sound)
        assertEquals(0.2f, changed.lost.volume)
        assertEquals(defaults.win, changed.win)
    }
    @Test fun `the victory list is read note by note and falls back to the win slots when empty`() {
        val yaml = YamlConfiguration()
        assertEquals(net.badgersmc.ek.config.VICTORY_TRIUMPH, CaptureSoundConfigLoader.load(yaml).victoryNotes())
        yaml.set("sounds.victory", listOf(
            mapOf("sound" to "minecraft:block.bell.use", "volume" to 3.0, "pitch" to 0.1, "delay-ticks" to 500),
            mapOf("sound" to "not a sound", "volume" to 0.5),
            mapOf("sound" to "minecraft:entity.player.levelup", "volume" to 0),
            mapOf("sound" to "minecraft:ui.toast.challenge_complete"),
        ))
        val notes = CaptureSoundConfigLoader.load(yaml).victoryNotes()
        assertEquals(listOf("minecraft:block.bell.use", "minecraft:ui.toast.challenge_complete"), notes.map { it.sound })
        assertEquals(net.badgersmc.ek.config.SoundCueConfig("minecraft:block.bell.use", 1f, 0.5f, 100), notes.first())
        assertEquals(net.badgersmc.ek.config.SoundCueConfig("minecraft:ui.toast.challenge_complete", 0.5f, 1f, 0), notes.last())
        yaml.set("sounds.victory", emptyList<Any>())
        val legacy = CaptureSoundConfigLoader.load(yaml)
        assertEquals(listOf(legacy.win, legacy.winAccent, legacy.winAccent2, legacy.winAccent3), legacy.victoryNotes())
    }

    @Test fun `values are bounded and invalid sound identifiers are disabled`() {
        val yaml = YamlConfiguration()
        yaml.set("sounds.capture.sound", "not a sound")
        yaml.set("sounds.capture.volume", 100.0)
        yaml.set("sounds.capture.pitch", 0.1)
        yaml.set("sounds.control-cooldown-seconds", -4)
        val cfg = CaptureSoundConfigLoader.load(yaml)
        assertEquals("", cfg.capture.sound); assertEquals(1f, cfg.capture.volume)
        assertEquals(0.5f, cfg.capture.pitch); assertEquals(0, cfg.cooldownSeconds)
    }
}
