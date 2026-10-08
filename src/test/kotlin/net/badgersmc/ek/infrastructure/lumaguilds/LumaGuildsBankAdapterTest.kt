package net.badgersmc.ek.infrastructure.lumaguilds

import io.mockk.*
import net.lumalyte.lg.api.GuildLookup
import org.bukkit.Bukkit
import org.bukkit.plugin.ServicesManager
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.util.UUID

class LumaGuildsBankAdapterTest {
    private val guildId = UUID.randomUUID()
    private val lookup = mockk<GuildLookup>(relaxed = true)
    private val services = mockk<ServicesManager>()
    private val adapter = LumaGuildsAdapter()

    @BeforeEach
    fun setup() {
        mockkStatic(Bukkit::class)
        every { Bukkit.getServicesManager() } returns services
        every { services.load(GuildLookup::class.java) } returns lookup
    }

    @AfterEach
    fun cleanup() { unmockkAll() }

    @Test
    fun `rewards credit system bank without personal actor`() {
        every { lookup.systemBankDeposit(guildId, 250L, "KOTH reward") } returns true
        assertTrue(adapter.depositToVault(guildId, 250.0, "KOTH reward"))
        verify(exactly = 1) { lookup.systemBankDeposit(guildId, 250L, "KOTH reward") }
        verify(exactly = 0) { lookup.bankDeposit(any(), any(), any(), any()) }
    }

    @Test
    fun `debits use system bank without personal actor`() {
        every { lookup.systemBankWithdraw(guildId, 250L, "KOTH debit") } returns true
        assertTrue(adapter.withdrawFromVault(guildId, 250.0, "KOTH debit"))
        verify(exactly = 1) { lookup.systemBankWithdraw(guildId, 250L, "KOTH debit") }
        verify(exactly = 0) { lookup.bankWithdraw(any(), any(), any(), any()) }
    }

    @Test
    fun `invalid amounts never reach bank provider`() {
        for (amount in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY,
                -1.0, 0.0, 1.5, Int.MAX_VALUE.toDouble() + 1, Long.MAX_VALUE.toDouble())) {
            assertFalse(adapter.depositToVault(guildId, amount, "invalid"))
            assertFalse(adapter.withdrawFromVault(guildId, amount, "invalid"))
        }
        verify { lookup wasNot Called }
    }

    @Test
    fun `maximum bank amount is retained exactly`() {
        every { lookup.systemBankDeposit(guildId, Int.MAX_VALUE.toLong(), "max") } returns true
        assertTrue(adapter.depositToVault(guildId, Int.MAX_VALUE.toDouble(), "max"))
    }

    @Test
    fun `provider rejection is failure without personal fallback`() {
        assertFalse(adapter.depositToVault(guildId, 100.0, "rejected"))
        assertFalse(adapter.withdrawFromVault(guildId, 100.0, "rejected"))
        verify(exactly = 0) { lookup.bankDeposit(any(), any(), any(), any()) }
        verify(exactly = 0) { lookup.bankWithdraw(any(), any(), any(), any()) }
    }

    @Test
    fun `old provider missing system API fails closed`() {
        every { lookup.systemBankDeposit(any(), any(), any()) } throws NoSuchMethodError("old API")
        every { lookup.systemBankWithdraw(any(), any(), any()) } throws NoSuchMethodError("old API")
        assertFalse(adapter.depositToVault(guildId, 100.0, "old"))
        assertFalse(adapter.withdrawFromVault(guildId, 100.0, "old"))
        verify(exactly = 0) { lookup.bankDeposit(any(), any(), any(), any()) }
        verify(exactly = 0) { lookup.bankWithdraw(any(), any(), any(), any()) }
    }

    @Test
    fun `missing provider can be retried after registration`() {
        every { services.load(GuildLookup::class.java) } returnsMany listOf(null, lookup)
        every { lookup.systemBankDeposit(guildId, 100L, "retry") } returns true
        assertFalse(adapter.depositToVault(guildId, 100.0, "retry"))
        assertTrue(adapter.depositToVault(guildId, 100.0, "retry"))
    }

    @Test
    fun `incomplete old implementation fails closed`() {
        every { lookup.systemBankDeposit(any(), any(), any()) } throws AbstractMethodError("old implementation")
        assertFalse(adapter.depositToVault(guildId, 100.0, "old"))
        verify(exactly = 0) { lookup.bankDeposit(any(), any(), any(), any()) }
    }

    @Test
    fun `unexpected provider failure is not retried or reported as success`() {
        every { lookup.systemBankDeposit(any(), any(), any()) } throws IllegalStateException("database failure")
        assertThrows(IllegalStateException::class.java) {
            adapter.depositToVault(guildId, 100.0, "failure")
        }
        verify(exactly = 1) { lookup.systemBankDeposit(guildId, 100L, "failure") }
        verify(exactly = 0) { lookup.bankDeposit(any(), any(), any(), any()) }
    }
}
