package net.badgersmc.ek.infrastructure.bukkit

import net.enthusia.loreitems.api.v1.LoreItemsServiceV1
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/** Bounded, nonblocking existence snapshots. No item operation is used as a probe. */
class LoreDefinitionReadiness(private val nanoTime: () -> Long = System::nanoTime) {
    private data class Entry(val started: Long, val result: CompletableFuture<Boolean>)
    private data class Check(var completed: Entry? = null, var pending: Entry? = null)
    private val entries = linkedMapOf<String, Check>()
    private var currentProvider: LoreItemsServiceV1? = null

    @Synchronized fun clear() { entries.clear(); currentProvider = null }

    @Synchronized fun issues(provider: LoreItemsServiceV1?, definitions: List<String>): List<String> {
        if (provider !== currentProvider) { entries.clear(); currentProvider = provider }
        val keys = definitions.filter(String::isNotBlank).distinct()
        entries.keys.retainAll(keys.toSet())
        if (keys.isEmpty()) return emptyList()
        if (provider == null) return listOf("LoreItems delivery provider is unavailable")
        if (keys.size > 128) { entries.clear(); return listOf("LoreItems readiness exceeds 128 definition limit") }
        return keys.mapNotNull { key -> issue(provider, key) }
    }

    private fun issue(provider: LoreItemsServiceV1, key: String): String? {
        val now = nanoTime()
        val check = entries.getOrPut(key) { Check() }
        acceptFinished(check)
        // Refresh proactively while a valid snapshot can still be used. Failed/missing
        // refreshes replace it immediately; pending work never extends the five-second age.
        val age = check.completed?.let { now - it.started }
        if (check.pending == null && (age == null || age < 0 || age >= TimeUnit.SECONDS.toNanos(2))) {
            check.pending = Entry(now, query(provider, key))
            acceptFinished(check)
        }
        val entry = check.completed
        if (entry == null || now - entry.started !in 0 until TimeUnit.SECONDS.toNanos(5))
            return "LoreItems definition check pending: $key; retry after completion"
        return if (runCatching { entry.result.getNow(false) }.getOrDefault(false)) null
            else "LoreItems definition missing, unavailable or read-only query unsupported: $key"
    }

    private fun acceptFinished(check: Check) {
        check.pending?.takeIf { it.result.isDone }?.let { check.completed = it; check.pending = null }
    }

    private fun query(provider: LoreItemsServiceV1, key: String): CompletableFuture<Boolean> = try {
        // Copy the stage so the timeout does not mutate the provider's future.
        provider.isDefinitionActive(key).thenApply { it == true }.toCompletableFuture()
            .orTimeout(3, TimeUnit.SECONDS)
    } catch (failure: Exception) {
        CompletableFuture.completedFuture(false)
    } catch (unsupported: LinkageError) {
        // An older runtime V1 class may lack the optional method entirely.
        CompletableFuture.completedFuture(false)
    }
}
