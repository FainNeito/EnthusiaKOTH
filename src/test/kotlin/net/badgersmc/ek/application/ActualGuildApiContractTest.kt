package net.badgersmc.ek.application

import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import net.badgersmc.ek.infrastructure.lumaguilds.LumaGuildsAdapter
import net.lumalyte.lg.api.GuildLookup
import org.bukkit.Bukkit
import org.bukkit.plugin.ServicesManager
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.util.UUID

class ActualGuildApiContractTest {
    @AfterEach
    fun cleanup() = unmockkAll()

    @Test
    fun `real provider classes support the alliance adapter`() {
        assumeTrue(System.getenv("ENTHUSIA_GUILD_API_CONTRACT") == "1")
        val expectedJar = File(requireNotNull(System.getenv("ENTHUSIA_GUILD_API_JAR"))).canonicalFile
        val loadedJar = File(GuildLookup::class.java.protectionDomain.codeSource.location.toURI()).canonicalFile
        assertEquals(expectedJar, loadedJar, "The compile shim must not supply the runtime API")

        val first = UUID.randomUUID()
        val second = UUID.randomUUID()
        val graph = mapOf(first to setOf(second), second to setOf(first))
        val provider = mockk<GuildLookup>()
        val services = mockk<ServicesManager>()
        mockkStatic(Bukkit::class)
        every { Bukkit.getServicesManager() } returns services
        every { services.load(GuildLookup::class.java) } returns provider
        every { provider.getActiveAllianceGraph() } returns graph

        assertEquals(graph, LumaGuildsAdapter().allianceGraph())
    }
}
