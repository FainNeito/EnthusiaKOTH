package net.badgersmc.ek.infrastructure.persistence

import net.badgersmc.ek.application.RewardProtectionConfig
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.io.TempDir
import org.sqlite.SQLiteDataSource
import java.io.File
import java.time.Instant
import java.util.UUID

class RewardProtectionPersistenceTest {
    @TempDir lateinit var directory: File
    private fun source()=SQLiteDataSource().apply { url="jdbc:sqlite:${File(directory,"audit.db")}" }
    @Test fun `reservation survives restart deduplicates and records rejected repeats`() {
        val ds=source(); val store=SqlRewardProtectionStore(ds); store.init()
        val cfg=RewardProtectionConfig(maximumRepeatedWins=2); val now=Instant.parse("2026-10-08T12:00:00Z")
        fun reserve(s:SqlRewardProtectionStore,id:UUID=UUID.randomUUID(),at:Instant=now)=s.reserve(id,"hill",setOf("guild:a"),setOf("guild:b"),at,cfg,"activity=30")
        val id=UUID.randomUUID(); assertTrue(reserve(store,id)); assertFalse(reserve(store,id))
        val restarted=SqlRewardProtectionStore(ds); restarted.init(); assertTrue(reserve(restarted)); assertFalse(reserve(restarted))
        assertTrue(reserve(restarted,at=now.plusSeconds(86401)))
        ds.connection.use { c -> c.createStatement().use { s -> s.executeQuery("SELECT COUNT(*) FROM koth_reward_audit WHERE accepted=0").use { r -> r.next(); assertEquals(1,r.getInt(1)) } } }
    }
    @Test fun `alliance component identities prevent rotating guild representative bypass`() {
        val store=SqlRewardProtectionStore(source()); store.init(); val now=Instant.now(); val cfg=RewardProtectionConfig(maximumRepeatedWins=1)
        assertTrue(store.reserve(UUID.randomUUID(),"a",setOf("guild:a","guild:ally"),setOf("guild:b","guild:alt"),now,cfg,""))
        assertFalse(store.reserve(UUID.randomUUID(),"b",setOf("guild:ally"),setOf("guild:alt"),now,cfg,""))
    }
    @Test fun `independent stores reserve only one last eligible payout`() {
        val ds=source(); val a=SqlRewardProtectionStore(ds); a.init(); val b=SqlRewardProtectionStore(ds); b.init()
        val cfg=RewardProtectionConfig(maximumRepeatedWins=1); val now=Instant.now()
        val executor=java.util.concurrent.Executors.newFixedThreadPool(2)
        try {
            val results=listOf(a,b).map { store -> executor.submit<Boolean> {
                runCatching { store.reserve(UUID.randomUUID(),"hill",setOf("a"),setOf("b"),now,cfg,"") }.getOrDefault(false)
            } }.map { it.get() }
            assertEquals(1,results.count { it })
        } finally { executor.shutdownNow() }
    }
}
