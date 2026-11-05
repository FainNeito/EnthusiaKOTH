package net.badgersmc.ek.application

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

class CaptureSoundPolicyTest {
    @Test fun `only controller transitions produce a cue`() {
        assertEquals(CaptureCue.CAPTURE, CaptureSoundPolicy.transition(null, "solo:a"))
        assertNull(CaptureSoundPolicy.transition("solo:a", "solo:a"))
        assertEquals(CaptureCue.LOST, CaptureSoundPolicy.transition("solo:a", null))
        assertEquals(CaptureCue.CAPTURE, CaptureSoundPolicy.transition("solo:a", "guild:b"))
        assertNull(CaptureSoundPolicy.transition(null, null))
    }
    @Test fun `cooldown applies to an event independently and cleans up`() {
        val limiter = CaptureSoundLimiter(); val a = UUID.randomUUID(); val b = UUID.randomUUID()
        assertTrue(limiter.allow(a, 1000, 5))
        assertFalse(limiter.allow(a, 5999, 5))
        assertTrue(limiter.allow(b, 1001, 5))
        assertTrue(limiter.allow(a, 6000, 5))
        limiter.remove(a)
        assertTrue(limiter.allow(a, 6001, 5))
    }
    @Test fun `clock rollback cannot defeat throttling and zero disables it`() {
        val limiter = CaptureSoundLimiter(); val id = UUID.randomUUID()
        assertTrue(limiter.allow(id, 10000, 5))
        assertFalse(limiter.allow(id, 9000, 5))
        assertTrue(limiter.allow(id, 9000, 0))
    }
}
