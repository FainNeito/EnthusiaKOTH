package net.badgersmc.ek.application

import java.util.UUID

enum class CaptureCue { CAPTURE, LOST, WIN }

object CaptureSoundPolicy {
    fun transition(previous: Any?, current: Any?): CaptureCue? = when {
        previous == current -> null
        current != null -> CaptureCue.CAPTURE
        previous != null -> CaptureCue.LOST
        else -> null
    }
}

/** Event-scoped throttle; a backwards clock never accelerates the next allowed cue. */
class CaptureSoundLimiter {
    private val last = mutableMapOf<UUID, Long>()
    fun allow(event: UUID, nowMillis: Long, cooldownSeconds: Int): Boolean {
        require(cooldownSeconds >= 0)
        val previous = last[event]
        if (cooldownSeconds > 0 && previous != null &&
            (nowMillis < previous || nowMillis - previous < cooldownSeconds.toLong() * 1000)) return false
        last[event] = nowMillis
        return true
    }
    fun remove(event: UUID) { last.remove(event) }
}
