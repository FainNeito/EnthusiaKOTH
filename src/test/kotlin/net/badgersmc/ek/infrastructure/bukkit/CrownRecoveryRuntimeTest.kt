package net.badgersmc.ek.infrastructure.bukkit

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CrownRecoveryRuntimeTest {
    @Test fun `blank or missing quest revision falls back to the recovery revision`() {
        assertEquals("test-v1", CrownRecoveryRuntime.questRevision("", "test-v1"))
        assertEquals("test-v1", CrownRecoveryRuntime.questRevision("  ", "test-v1"))
        assertEquals("test-v1", CrownRecoveryRuntime.questRevision(null, "test-v1"))
        assertEquals("quest-2", CrownRecoveryRuntime.questRevision("quest-2", "test-v1"))
    }
}
