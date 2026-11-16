package net.badgersmc.ek.infrastructure.persistence

import java.time.Instant
import java.util.UUID
import javax.sql.DataSource
import net.badgersmc.ek.application.*
import net.badgersmc.ek.domain.*

/** Evidence only. It never authorizes confiscation or invokes a provider. */
class SqlCrownActivityStore(private val source: DataSource) {
    fun init() = source.connection.use { c -> c.createStatement().use { s ->
        s.execute("""CREATE TABLE IF NOT EXISTS koth_crown_activity_tenure (
            instance TEXT NOT NULL, generation INTEGER NOT NULL CHECK(generation>=0), holder TEXT NOT NULL,
            revision TEXT NOT NULL, observed_from INTEGER NOT NULL, last_observed INTEGER NOT NULL,
            conflicted INTEGER NOT NULL DEFAULT 0 CHECK(conflicted IN (0,1)), PRIMARY KEY(instance,generation)
        )""".trimIndent())
        s.execute("""CREATE TABLE IF NOT EXISTS koth_crown_activity_evidence (
            instance TEXT NOT NULL, generation INTEGER NOT NULL, occurrence TEXT NOT NULL, at_ms INTEGER NOT NULL,
            status TEXT NOT NULL, participated INTEGER NOT NULL CHECK(participated IN (0,1)),
            PRIMARY KEY(instance,generation,occurrence)
        )""".trimIndent())
    } }

    /** First observation starts a new window, never an inferred historical acquisition time. */
    fun observe(tenure: CrownTenure, revision: String, now: Instant): Boolean {
        require(revision.matches(Regex("[a-zA-Z0-9_.-]{1,64}")))
        return source.connection.use { c ->
            c.autoCommit=false
            try {
                c.prepareStatement("INSERT OR IGNORE INTO koth_crown_activity_tenure VALUES(?,?,?,?,?,?,0)").use { s ->
                    s.setString(1,tenure.instance.toString());s.setLong(2,tenure.generation);s.setString(3,tenure.holder.toString())
                    s.setString(4,revision);s.setLong(5,now.toEpochMilli());s.setLong(6,now.toEpochMilli());s.executeUpdate()
                }
                val updated=c.prepareStatement("""UPDATE koth_crown_activity_tenure SET
                    conflicted=CASE WHEN holder<>? OR revision<>? OR last_observed>? THEN 1 ELSE conflicted END,
                    last_observed=MAX(last_observed,?) WHERE instance=? AND generation=?""").use { s ->
                    s.setString(1,tenure.holder.toString());s.setString(2,revision);s.setLong(3,now.toEpochMilli())
                    s.setLong(4,now.toEpochMilli());s.setString(5,tenure.instance.toString());s.setLong(6,tenure.generation);s.executeUpdate()
                }
                c.commit();updated==1
            } catch(error:Exception){c.rollback();throw error}
        }
    }

    /** Input must come from the protected event evidence adapter, never a player command or login. */
    fun record(tenure: CrownTenure, opportunity: CrownOpportunity): Boolean {
        require(opportunity.occurrenceId.isNotBlank())
        return source.connection.use connection@{ c ->
            c.autoCommit=false
            try {
                // Lock before identity and replay checks.
                c.prepareStatement("UPDATE koth_crown_activity_tenure SET conflicted=conflicted WHERE instance=? AND generation=? AND holder=?").use { s ->
                    s.setString(1,tenure.instance.toString());s.setLong(2,tenure.generation);s.setString(3,tenure.holder.toString())
                    if(s.executeUpdate()!=1){c.rollback();return@connection false}
                }
                val inserted=c.prepareStatement("INSERT OR IGNORE INTO koth_crown_activity_evidence VALUES(?,?,?,?,?,?)").use { s ->
                    s.setString(1,tenure.instance.toString());s.setLong(2,tenure.generation);s.setString(3,opportunity.occurrenceId)
                    s.setLong(4,opportunity.at.toEpochMilli());s.setString(5,opportunity.status.name)
                    s.setInt(6,if(opportunity.participationVerified)1 else 0);s.executeUpdate()==1
                }
                val same=inserted || c.prepareStatement("SELECT at_ms,status,participated FROM koth_crown_activity_evidence WHERE instance=? AND generation=? AND occurrence=?").use { s ->
                    s.setString(1,tenure.instance.toString());s.setLong(2,tenure.generation);s.setString(3,opportunity.occurrenceId)
                    s.executeQuery().use { r -> r.next() && r.getLong(1)==opportunity.at.toEpochMilli() &&
                        r.getString(2)==opportunity.status.name && r.getInt(3)==if(opportunity.participationVerified)1 else 0 }
                }
                if(!same)c.prepareStatement("UPDATE koth_crown_activity_tenure SET conflicted=1 WHERE instance=? AND generation=?").use { s ->
                    s.setString(1,tenure.instance.toString());s.setLong(2,tenure.generation);s.executeUpdate()
                }
                c.commit();same
            } catch(error:Exception){c.rollback();throw error}
        }
    }

    fun read(tenure: CrownTenure): CrownActivitySnapshot? = source.connection.use connection@{ c ->
        c.autoCommit=false
        try {
            c.prepareStatement("SELECT holder,revision,observed_from,last_observed,conflicted FROM koth_crown_activity_tenure WHERE instance=? AND generation=?").use { s ->
                s.setString(1,tenure.instance.toString());s.setLong(2,tenure.generation)
                s.executeQuery().use { r ->
                    if(!r.next() || UUID.fromString(r.getString(1))!=tenure.holder)return@connection null
                    val rows=c.prepareStatement("SELECT occurrence,at_ms,status,participated FROM koth_crown_activity_evidence WHERE instance=? AND generation=? ORDER BY at_ms,occurrence").use { q ->
                        q.setString(1,tenure.instance.toString());q.setLong(2,tenure.generation)
                        q.executeQuery().use { e -> buildList { while(e.next()) add(CrownOpportunity(e.getString(1),
                            Instant.ofEpochMilli(e.getLong(2)),CrownOpportunityStatus.valueOf(e.getString(3)),e.getInt(4)==1)) } }
                    }
                    CrownActivitySnapshot(tenure,r.getString(2),Instant.ofEpochMilli(r.getLong(3)),
                        Instant.ofEpochMilli(r.getLong(4)),rows,r.getInt(5)==1)
                }
            }
        } finally { c.rollback() }
    }
}
