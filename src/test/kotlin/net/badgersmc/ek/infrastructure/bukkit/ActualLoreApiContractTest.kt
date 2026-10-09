package net.badgersmc.ek.infrastructure.bukkit

import net.enthusia.loreitems.api.v1.LoreItemsServiceV1
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.nio.file.Paths
import java.util.UUID
import java.util.concurrent.CompletionStage

class ActualLoreApiContractTest {
    @Test fun `real provider preserves functional delivery API and default query contract`() {
        assumeTrue(System.getenv("ENTHUSIA_LORE_API_CONTRACT") == "1")
        val loaded = Paths.get(LoreItemsServiceV1::class.java.protectionDomain.codeSource.location.toURI()).toRealPath()
        assertEquals(Paths.get(System.getenv("ENTHUSIA_LORE_API_JAR")).toRealPath(), loaded)
        assertEquals(CompletionStage::class.java,
            LoreItemsServiceV1::class.java.getMethod("queueDelivery", String::class.java, UUID::class.java, String::class.java).returnType)
        assertTrue(LoreItemsServiceV1::class.java.getMethod("isDefinitionActive", String::class.java).isDefault)
        val legacyImplementation = LoreItemsServiceV1 { _, _, _ -> throw AssertionError("Readiness must never deliver") }
        assertFalse(LoreDefinitionReadiness().issues(legacyImplementation, listOf("blade")).isEmpty())
    }
}
