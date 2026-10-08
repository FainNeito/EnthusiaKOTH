package net.badgersmc.ek.application

import io.mockk.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class StaffSettingsServiceTest {
    private val store = mockk<StaffSettingsStore>(relaxed = true).also {
        every { it.read() } returns StaffSettingsSnapshot("revision", mapOf("arenas.hill.family" to "capture", "schedule.enabled" to false))
    }
    private val service = StaffSettingsService(store) { false }
    @Test fun `draft mutations do not persist until Save`() {
        val draft = service.begin("hill"); draft.set("schedule.enabled", true)
        verify(exactly = 0) { store.write(any(), any()) }; service.save(draft)
        verify { store.write(mapOf("schedule.enabled" to true), "revision") }
    }
    @Test fun `busy Save refuses all persistence`() {
        val busy = StaffSettingsService(store) { true }; val draft = busy.begin("hill"); draft.set("schedule.enabled", true)
        assertThrows(IllegalStateException::class.java) { busy.save(draft) }; verify(exactly = 0) { store.write(any(), any()) }
    }
    @Test fun `invalid arena cannot begin a draft`() { assertThrows(IllegalArgumentException::class.java) { service.begin("missing") } }
    @Test fun `time parser normalizes deduplicates and orders daily times`() {
        assertEquals(listOf("08:00", "18:00"), service.times("18:00,8:0,18:00")); assertTrue(service.times("-").isEmpty())
        assertThrows(IllegalArgumentException::class.java) { service.times("24:00") }
    }
    @Test fun `invalid and nonfinite money rejected before Save`() {
        listOf("NaN", "Infinity", "-1", "1000000000001").forEach { raw -> assertThrows(IllegalArgumentException::class.java) { service.money(raw) } }
        assertEquals(12.5, service.money("12.50"))
    }
    @Test fun `commands retain contribution placeholders without executing`() {
        assertEquals(listOf("give {CONTRIBUTORS} diamond 1", "say {KOTH}"), service.commands("give {CONTRIBUTORS} diamond 1; say {KOTH}"))
        assertThrows(IllegalArgumentException::class.java) { service.commands("say hello\nsay bad") }
        assertThrows(IllegalArgumentException::class.java) { service.commands("bank NaN") }
    }
    @Test fun `unknown paths and shared family writes rejected`() {
        val d = service.begin("hill"); d.set("rewards.capture.guild-vault-money", 500.0)
        assertThrows(IllegalArgumentException::class.java) { service.save(d) }
        verify(exactly = 0) { store.write(any(), any()) }
    }
    @Test fun `timezone season and display enums validate`() {
        service.validate("general.timezone", "America/New_York"); service.validate("leaderboards.season-start", "2026-10-01")
        assertThrows(Exception::class.java) { service.validate("general.timezone", "Mars/Bad") }
        assertThrows(Exception::class.java) { service.validate("leaderboards.season-start", "2026-02-30") }
        assertThrows(Exception::class.java) { service.validate("display.bossbar-color", "INVALID") }
        assertThrows(Exception::class.java) { service.validate("events.max-concurrent", 17) }
    }
    @Test fun `chance commands allow identical percentages and reject invalid probability`() {
        service.validate("arenas.hill.chanced-rewards", listOf(mapOf("command" to "say a", "chance" to 25.0), mapOf("command" to "say b", "chance" to 25.0)))
        assertThrows(Exception::class.java) { service.validate("arenas.hill.chanced-rewards", listOf(mapOf("command" to "say a", "chance" to 101.0))) }
    }
}
