package net.badgersmc.ek.application

import io.mockk.*
import net.badgersmc.ek.infrastructure.lumaguilds.LumaGuildsAdapter
import net.lumalyte.lg.api.GuildLookup
import org.bukkit.Bukkit
import org.bukkit.plugin.ServicesManager
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*

class AllianceAdapterCompatibilityTest {
    @AfterEach fun cleanup()=unmockkAll()
    @Test fun `old runtime API is unknown rather than assuming every guild is unrelated`() {
        mockkStatic(Bukkit::class)
        val services=mockk<ServicesManager>(); val api=mockk<GuildLookup>()
        every { Bukkit.getServicesManager() } returns services
        every { services.load(GuildLookup::class.java) } returns api
        every { api.getActiveAllianceGraph() } throws NoSuchMethodError("old API")
        val adapter=LumaGuildsAdapter()
        assertNull(adapter.allianceGraph()); assertNull(adapter.protectionRoster())
    }
}
