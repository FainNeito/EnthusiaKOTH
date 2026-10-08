package net.lumalyte.lg.api.events
import org.bukkit.event.Event
import org.bukkit.event.HandlerList
import java.util.UUID
/** Compile-only getter mirror; excluded from the artifact. Never instantiated. */
class GuildRelationChangeEvent(val guild1: UUID, val guild2: UUID) : Event() {
    override fun getHandlers(): HandlerList = handlers
    companion object {
        private val handlers = HandlerList()
        @JvmStatic fun getHandlerList(): HandlerList = handlers
    }
}
