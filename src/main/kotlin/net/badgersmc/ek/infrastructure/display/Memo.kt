package net.badgersmc.ek.infrastructure.display

/**
 * Remembers each value for [ttlMillis], so holograms that refresh every second don't repeat a database read or a
 * schedule expansion every second.
 */
class Memo<K, V>(private val ttlMillis: Long, private val now: () -> Long) {
    private class Entry<V>(val value: V, val at: Long)
    private val entries = mutableMapOf<K, Entry<V>>()

    fun get(key: K, load: () -> V): V {
        val time = now()
        entries[key]?.takeIf { time >= it.at && time - it.at < ttlMillis }?.let { return it.value }
        return load().also { entries[key] = Entry(it, time) }
    }
}
