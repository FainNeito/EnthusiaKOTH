package net.badgersmc.ek.api

import org.bukkit.event.Event
import org.bukkit.event.HandlerList
import java.time.Instant
import java.util.UUID

enum class KothLifecycle { STARTED, CONTROL_CHANGED, COMPLETED, CANCELLED }

/** Read-only snapshots, published synchronously on the Paper server thread. */
data class KothTeamSnapshot(val mode: String, val id: UUID)
data class KothLifecycleSnapshot(
    val eventId: UUID,
    val arenaId: String,
    val family: String,
    val privateTest: Boolean,
    val startsAt: Instant,
    val endsAt: Instant,
    val controller: KothTeamSnapshot?,
    val winner: KothTeamSnapshot?,
    val scores: Map<KothTeamSnapshot, Double>,
    val reason: String? = null,
)

/** Informational; never a cancellable payment or reward gate. */
class KothLifecycleEvent(val lifecycle: KothLifecycle, val snapshot: KothLifecycleSnapshot) : Event() {
    override fun getHandlers(): HandlerList = HANDLERS
    companion object {
        private val HANDLERS = HandlerList()
        @JvmStatic fun getHandlerList(): HandlerList = HANDLERS
    }
}
