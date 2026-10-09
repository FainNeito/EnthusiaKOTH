package net.badgersmc.ek.infrastructure.persistence

import net.badgersmc.ek.application.RewardProtectionConfig
import net.badgersmc.ek.application.RewardProtectionStore
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

class SqlRewardProtectionStore(private val dataSource: DataSource) : RewardProtectionStore {
    fun init() = dataSource.connection.use { c -> c.createStatement().use { s ->
        s.execute("CREATE TABLE IF NOT EXISTS koth_reward_audit(event_id TEXT PRIMARY KEY, arena TEXT NOT NULL, at INTEGER NOT NULL, accepted INTEGER NOT NULL, detail TEXT NOT NULL)")
        s.execute("CREATE TABLE IF NOT EXISTS koth_reward_opponents(event_id TEXT NOT NULL, winner TEXT NOT NULL, opponent TEXT NOT NULL, PRIMARY KEY(event_id,winner,opponent))")
        s.execute("CREATE INDEX IF NOT EXISTS koth_reward_pair ON koth_reward_opponents(winner,opponent)")
    } }

    @Synchronized override fun reserve(eventId: UUID, arena: String, winner: Set<String>, opponents: Set<String>,
        now: Instant, config: RewardProtectionConfig, detail: String): Boolean {
        require(winner.isNotEmpty() && opponents.isNotEmpty())
        return dataSource.connection.use { c ->
            c.autoCommit = false
            try {
                // First write obtains SQLite's writer lock before count-and-reserve.
                val inserted = c.prepareStatement("INSERT OR IGNORE INTO koth_reward_audit VALUES(?,?,?,?,?)").use { s ->
                    s.setString(1, eventId.toString()); s.setString(2, arena); s.setLong(3, now.toEpochMilli())
                    s.setInt(4, 0); s.setString(5, detail.take(16000)); s.executeUpdate() == 1
                }
                if (!inserted) { c.rollback(); return@use false }
                val limited = config.maximumRepeatedWins > 0 && winner.any { w -> opponents.any { o ->
                    c.prepareStatement("SELECT COUNT(DISTINCT a.event_id) FROM koth_reward_audit a JOIN koth_reward_opponents p ON p.event_id=a.event_id WHERE a.accepted=1 AND p.winner=? AND p.opponent=? AND a.at>=? AND a.at<=?").use { s ->
                        s.setString(1, w); s.setString(2, o)
                        s.setLong(3, now.minusSeconds(config.repeatedOpponentWindowHours.toLong() * 3600).toEpochMilli())
                        s.setLong(4, now.toEpochMilli())
                        s.executeQuery().use { r -> r.next(); r.getInt(1) >= config.maximumRepeatedWins }
                    }
                } }
                if (limited) {
                    c.prepareStatement("UPDATE koth_reward_audit SET detail=? WHERE event_id=?").use { s ->
                        s.setString(1, "REPEATED_OPPONENT; ${detail.take(15900)}"); s.setString(2, eventId.toString()); s.executeUpdate()
                    }
                } else {
                    c.prepareStatement("INSERT INTO koth_reward_opponents VALUES(?,?,?)").use { s ->
                        winner.forEach { w -> opponents.forEach { o ->
                            s.setString(1,eventId.toString()); s.setString(2,w); s.setString(3,o); s.addBatch()
                        } }; s.executeBatch()
                    }
                    c.prepareStatement("UPDATE koth_reward_audit SET accepted=1 WHERE event_id=?").use { s ->
                        s.setString(1,eventId.toString()); s.executeUpdate()
                    }
                }
                c.commit(); !limited
            } catch (error: Exception) { runCatching { c.rollback() }; throw error }
        }
    }

    override fun reject(eventId: UUID, arena: String, now: Instant, reason: String) {
        dataSource.connection.use { c -> c.prepareStatement("INSERT OR IGNORE INTO koth_reward_audit VALUES(?,?,?,?,?)").use { s ->
            s.setString(1,eventId.toString()); s.setString(2,arena); s.setLong(3,now.toEpochMilli()); s.setInt(4,0)
            s.setString(5,reason.take(16000)); s.executeUpdate()
        } }
    }
}
