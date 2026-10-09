package net.badgersmc.ek.infrastructure.bukkit

import io.mockk.*
import net.enthusia.loreitems.api.v1.LoreItemsServiceV1
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class LoreDefinitionReadinessTest {
    @Test fun `pending checks do not block and do not queue rewards`() {
        val provider = mockk<LoreItemsServiceV1>()
        val result = CompletableFuture<Boolean>()
        every { provider.isDefinitionActive("blade") } returns result
        val readiness = LoreDefinitionReadiness()
        assertTrue(readiness.issues(provider, listOf("blade", "blade")).single().contains("pending"))
        assertFalse(result.isDone)
        result.complete(true)
        assertTrue(readiness.issues(provider, listOf("blade")).isEmpty())
        verify(exactly = 1) { provider.isDefinitionActive("blade") }
        verify(exactly = 0) { provider.queueDelivery(any(), any(), any()) }
    }

    @Test fun `expired snapshot and reload discard previous ready result`() {
        var now = 0L
        val readiness = LoreDefinitionReadiness { now }
        val provider = mockk<LoreItemsServiceV1>()
        every { provider.isDefinitionActive("blade") } returnsMany listOf(
            CompletableFuture.completedFuture(true), CompletableFuture.completedFuture(false),
            CompletableFuture.completedFuture(true))
        assertTrue(readiness.issues(provider, listOf("blade")).isEmpty())
        now = TimeUnit.SECONDS.toNanos(5)
        assertFalse(readiness.issues(provider, listOf("blade")).isEmpty())
        readiness.clear()
        assertTrue(readiness.issues(provider, listOf("blade")).isEmpty())
        verify(exactly = 3) { provider.isDefinitionActive("blade") }
    }

    @Test fun `replacement and unavailable providers cannot inherit acceptance`() {
        val first = mockk<LoreItemsServiceV1>()
        val second = mockk<LoreItemsServiceV1>()
        every { first.isDefinitionActive(any()) } returns CompletableFuture.completedFuture(true)
        every { second.isDefinitionActive(any()) } returns CompletableFuture.completedFuture(false)
        val readiness = LoreDefinitionReadiness()
        assertTrue(readiness.issues(first, listOf("blade")).isEmpty())
        assertFalse(readiness.issues(second, listOf("blade")).isEmpty())
        assertFalse(readiness.issues(null, listOf("blade")).isEmpty())
        assertTrue(readiness.issues(null, emptyList()).isEmpty())
    }

    @Test fun `proactive pending refresh cannot extend a ready snapshot and deletion revokes it`() {
        var now = 0L
        val provider = mockk<LoreItemsServiceV1>()
        val pending = CompletableFuture<Boolean>()
        every { provider.isDefinitionActive("blade") } returnsMany listOf(
            CompletableFuture.completedFuture(true), pending)
        val readiness = LoreDefinitionReadiness { now }
        assertTrue(readiness.issues(provider, listOf("blade")).isEmpty())
        now = TimeUnit.SECONDS.toNanos(2)
        assertTrue(readiness.issues(provider, listOf("blade")).isEmpty())
        now = TimeUnit.SECONDS.toNanos(5)
        assertTrue(readiness.issues(provider, listOf("blade")).single().contains("pending"))
        pending.complete(false)
        assertTrue(readiness.issues(provider, listOf("blade")).single().contains("missing"))
    }

    @Test fun `failed thrown unsupported and old binary query fail closed`() {
        val provider = mockk<LoreItemsServiceV1>()
        val readiness = LoreDefinitionReadiness()
        every { provider.isDefinitionActive("failed") } returns CompletableFuture.failedFuture(IllegalStateException())
        every { provider.isDefinitionActive("throwing") } throws IllegalStateException()
        every { provider.isDefinitionActive("old") } throws NoSuchMethodError()
        assertEquals(3, readiness.issues(provider, listOf("failed", "throwing", "old")).size)
        val oldImplementation = LoreItemsServiceV1 { _, _, _ -> throw AssertionError("Must not issue items") }
        assertFalse(readiness.issues(oldImplementation, listOf("blade")).isEmpty())
    }

    @Test fun `bounded fanout rejects large configuration before querying`() {
        val provider = mockk<LoreItemsServiceV1>()
        val readiness = LoreDefinitionReadiness()
        assertTrue(readiness.issues(provider, (1..129).map { "reward_$it" }).single().contains("limit"))
        verify { provider wasNot Called }
    }

    @Test fun `query timeout preserves provider future and never authorizes readiness`() {
        val provider = mockk<LoreItemsServiceV1>()
        val original = CompletableFuture<Boolean>()
        val nextQuery = CompletableFuture<Boolean>()
        every { provider.isDefinitionActive("blade") } returnsMany listOf(original, nextQuery)
        val readiness = LoreDefinitionReadiness()
        assertFalse(readiness.issues(provider, listOf("blade")).isEmpty())
        // Await the actual three-second asynchronous timeout, not a manufactured implementation result.
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4)
        var issues = readiness.issues(provider, listOf("blade"))
        while (issues.any { it.contains("pending") } && System.nanoTime() < deadline) {
            Thread.sleep(20)
            issues = readiness.issues(provider, listOf("blade"))
        }
        assertTrue(issues.single().contains("unavailable"))
        assertFalse(original.isDone, "Timeout must not mutate the provider's future")
        original.complete(true)
        assertFalse(readiness.issues(provider, listOf("blade")).isEmpty())
    }
}
