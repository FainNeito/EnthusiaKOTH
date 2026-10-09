# KOTH lifecycle API

Depend on EnthusiaKOTH at runtime and compile against its public API package
`net.badgersmc.ek.api`. Register a normal Bukkit listener. Paper plugin consumers
must declare their EnthusiaKOTH server dependency and enable classpath access.

```kotlin
class KothObserver : org.bukkit.event.Listener {
    @org.bukkit.event.EventHandler
    fun onKoth(event: net.badgersmc.ek.api.KothLifecycleEvent) {
        val snapshot = event.snapshot
        if (snapshot.privateTest) return
        when (event.lifecycle) {
            net.badgersmc.ek.api.KothLifecycle.COMPLETED -> {
                // Deduplicate external actions using snapshot.eventId.
                // snapshot.winner is null for ties/no eligible winner.
            }
            else -> Unit
        }
    }
}
```

STARTED, CONTROL_CHANGED, COMPLETED and CANCELLED are synchronous informational
events on the server thread. Snapshots contain event UUID, arena ID, family,
private-test flag, start/end Instants, controller/winner team identity, score map
and optional cancellation reason. Team modes are strings; identities are UUIDs.
The delivered score map is an immutable copy. Distinguish simultaneous events
by event UUID, never by the compatibility primary-event getter.

Events are not cancellable transaction gates. COMPLETED is emitted when the
terminal result is determined, before fault-isolated statistics/rewards work;
it does not acknowledge successful payouts. Do not retry KOTH rewards from an
observer. Keep handlers short and use copied snapshots for asynchronous external
work. Observer failures are isolated from KOTH settlement/cleanup. STARTED may
be followed immediately by CANCELLED when a handler invokes an authorized stop.
Avoid reentrant administrative actions unless that behavior is intentional.

This API has local regression coverage; no live companion integration is claimed.
