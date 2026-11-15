package net.badgersmc.ek.application

import net.badgersmc.ek.config.CaptureSoundsConfig
import net.badgersmc.ek.config.SoundCueConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class KothSoundsTest {
    private val defaults = CaptureSoundsConfig()

    @Test fun `names and aliases resolve, anything else does not`() {
        assertEquals("victory", KothSounds.resolve("WIN"))
        assertEquals("start", KothSounds.resolve("opening"))
        assertEquals("lost", KothSounds.resolve("lost"))
        assertNull(KothSounds.resolve("defeat"))
        assertNull(KothSounds.resolve(null))
    }

    @Test fun `every named sound has notes with the defaults`() {
        KothSounds.NAMES.forEach { assertTrue(KothSounds.cue(it, defaults).isNotEmpty(), it) }
        assertEquals(KothSounds.OPENING, KothSounds.cue("start", defaults))
    }

    @Test fun `victory plays the configured notes in order and skips switched-off ones`() {
        val sounds = defaults.copy(winAccent2 = SoundCueConfig("", 0f, 1f, 0), winAccent3 = SoundCueConfig("minecraft:x.y", 0f, 1f, 9))
        val notes = KothSounds.cue("victory", sounds)
        assertEquals(listOf(defaults.win.sound, defaults.winAccent.sound), notes.map { it.sound })
        assertEquals(listOf(0L, defaults.winAccent.delayTicks), notes.map { it.delayTicks })
    }

    @Test fun `a sound with every note off is silent rather than unknown`() {
        val sounds = defaults.copy(lost = SoundCueConfig("", 0f, 1f))
        assertTrue(KothSounds.cue("lost", sounds).isEmpty())
        assertTrue(KothSounds.cue("nonsense", sounds).isEmpty())
    }
}
