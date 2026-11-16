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

    /** The holder that lost the point this tick, whether it went empty, contested or straight to another holder. */
    fun <T : Any> lostBy(previous: T?, current: T?): T? = previous?.takeIf { it != current }

    /** The holder that took the point this tick, from nobody or from another holder. */
    fun <T : Any> capturedBy(previous: T?, current: T?): T? = current?.takeIf { it != previous }
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
