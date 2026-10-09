package net.badgersmc.ek.application

import io.mockk.*
import net.badgersmc.ek.config.*
import net.badgersmc.ek.domain.*
import org.bukkit.Location
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.util.UUID

class ProtectionStartGuardTest {
    @Test fun `unknown protected sides reject paid starts before journal or withdrawal`() {
        val economy=mockk<PlayerEconomy>(relaxed=true)
        val starter=mockk<EventStarter>(relaxed=true)
        val journal=mockk<PaymentJournal>(relaxed=true)
        val arena=KothArena("hill","capture",CaptureZone("hill","world",Location(null,0.0,0.0,0.0),Location(null,10.0,100.0,10.0)),durationSeconds=120,captureSeconds=60)
        val service=StartService(config={EnthusiaKothConfig(manualStart=ManualStartConfig(basicCost=20.0),rewardProtection=RewardProtectionConfig(enabled=true))},
            pluginReady={true},hasConflictingEvent={false},economy=economy,starter=starter,logError={_,_->},paymentJournal=journal,protectionReady={false})
        val result=service.start(StartRequest(StartActor(UUID.randomUUID(),canStartBasic=true),arena,StartSource.PLAYER_COMMAND,StartTier.BASIC,TeamMode.GUILD))
        assertTrue(result is StartResult.Rejected)
        verify(exactly=0) { economy.withdraw(any(),any()); journal.record(any()); starter.start(any(),any(),any(),any(),any()) }
    }
}
