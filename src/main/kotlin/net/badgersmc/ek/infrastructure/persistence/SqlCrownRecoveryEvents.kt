package net.badgersmc.ek.infrastructure.persistence

import java.time.Instant
import java.util.UUID
import javax.sql.DataSource
import net.badgersmc.ek.application.*
import net.badgersmc.ek.domain.*

data class RecoveryEventBinding(val occurrence:String,val recovery:UUID,val tenure:CrownTenure)
data class RecoveryEventWinner(val binding:RecoveryEventBinding,val match:UUID,val winner:UUID)

/** Durable schedule/result binding; provider still revalidates the exact held generation. */
class SqlCrownRecoveryEvents(private val source:DataSource) {
    fun init()=source.connection.use { c -> c.createStatement().use { s ->
        s.execute("""CREATE TABLE IF NOT EXISTS koth_crown_announcements (
            occurrence TEXT PRIMARY KEY, announced_at INTEGER NOT NULL, revision TEXT NOT NULL)""")
        s.execute("""CREATE TABLE IF NOT EXISTS koth_crown_recovery_events (
            occurrence TEXT PRIMARY KEY, recovery TEXT NOT NULL, instance TEXT NOT NULL, holder TEXT NOT NULL,
            generation INTEGER NOT NULL, retired INTEGER NOT NULL DEFAULT 0,
            match_id TEXT, winner TEXT)""")
        s.execute("CREATE UNIQUE INDEX IF NOT EXISTS koth_crown_one_recovery_event ON koth_crown_recovery_events(recovery) WHERE retired=0")
    } }
    fun announced(id:String,revision:String,at:Instant)=source.connection.use { c ->
        c.prepareStatement("INSERT OR IGNORE INTO koth_crown_announcements VALUES(?,?,?)").use { s ->
            s.setString(1,id);s.setLong(2,at.toEpochMilli());s.setString(3,revision);s.executeUpdate()
        }
    }
    fun assign(binding:RecoveryEventBinding):Boolean=source.connection.use { c ->
        c.prepareStatement("INSERT OR IGNORE INTO koth_crown_recovery_events(occurrence,recovery,instance,holder,generation) VALUES(?,?,?,?,?)").use { s ->
            s.setString(1,binding.occurrence);s.setString(2,binding.recovery.toString());s.setString(3,binding.tenure.instance.toString())
            s.setString(4,binding.tenure.holder.toString());s.setLong(5,binding.tenure.generation);s.executeUpdate()
        }
        c.prepareStatement("SELECT recovery,instance,holder,generation FROM koth_crown_recovery_events WHERE occurrence=? AND retired=0").use { s ->
            s.setString(1,binding.occurrence);s.executeQuery().use { r -> r.next() && r.getString(1)==binding.recovery.toString() &&
                r.getString(2)==binding.tenure.instance.toString() && r.getString(3)==binding.tenure.holder.toString() && r.getLong(4)==binding.tenure.generation }
        }
    }
    fun active():List<RecoveryEventBinding> = source.connection.use { c -> c.createStatement().use { s ->
        s.executeQuery("SELECT occurrence,recovery,instance,holder,generation FROM koth_crown_recovery_events WHERE retired=0 ORDER BY occurrence LIMIT 100").use { r ->
            buildList {while(r.next())add(RecoveryEventBinding(r.getString(1),UUID.fromString(r.getString(2)),
                CrownTenure(UUID.fromString(r.getString(3)),UUID.fromString(r.getString(4)),r.getLong(5))))}
        }
    } }
    fun finish(match:UUID,events:SqlCrownEventStore) {
        val id=source.connection.use { c -> c.prepareStatement("SELECT occurrence_id FROM koth_crown_occurrences WHERE match_id=? AND purpose<>'FIRST_CROWN_FINAL'").use { s ->
            s.setString(1,match.toString());s.executeQuery().use { r -> if(r.next())r.getString(1) else null }
        } } ?: return
        events.finish(id,match,false)
    }
    fun cancel(match:UUID)=source.connection.use { c -> c.prepareStatement("UPDATE koth_crown_occurrences SET state='CANCELLED' WHERE match_id=? AND state='STARTED'").use { s ->
        s.setString(1,match.toString());s.executeUpdate()
    } }
    /** Releases unawarded bindings: cancelled, no winner, or a final rejection. HELD stays bound for staff review. */
    fun retireEmpty()=source.connection.use { c -> c.createStatement().use { s ->
        // A store that was never initialised holds no rejection, so its clause is left out rather than failing.
        val tables=s.executeQuery("SELECT name FROM sqlite_master WHERE type='table' AND name IN ('koth_match_decisions','koth_reward_audit')")
            .use { r -> buildSet { while(r.next()) add(r.getString(1)) } }
        val rejected=buildList {
            add("m.winner IS NULL")
            if("koth_match_decisions" in tables) add("EXISTS(SELECT 1 FROM koth_match_decisions d WHERE d.event=m.id AND d.status='REJECTED')")
            if("koth_reward_audit" in tables) add("EXISTS(SELECT 1 FROM koth_reward_audit a WHERE a.event_id=m.id AND a.accepted=0)")
        }.joinToString(" OR ")
        s.executeUpdate("""UPDATE koth_crown_recovery_events SET retired=1 WHERE match_id IS NULL AND occurrence IN
            (SELECT o.occurrence_id FROM koth_crown_occurrences o LEFT JOIN koth_matches m ON m.id=o.match_id
             WHERE o.state='CANCELLED' OR (o.state='COMPLETED' AND m.id IS NOT NULL AND ($rejected)))""")
    } }
    fun observeSuccessor(tenure:CrownTenure)=source.connection.use { c ->
        c.prepareStatement("UPDATE koth_crown_recovery_events SET retired=1 WHERE instance=? AND generation+1=? AND winner=? AND match_id IS NOT NULL").use { s ->
            s.setString(1,tenure.instance.toString());s.setLong(2,tenure.generation);s.setString(3,tenure.holder.toString());s.executeUpdate()
        }
    }
    fun reconcileResults()=source.connection.use { c -> c.createStatement().use { s ->
        s.executeUpdate("""UPDATE koth_crown_occurrences SET state='COMPLETED' WHERE state='STARTED'
            AND purpose IN ('HOLDER_CHALLENGE','RECOVERY_CROWN_FINAL') AND match_id IN (SELECT id FROM koth_matches)""")
    } }
    /** Only a verified announced special event counts. Missing/ambiguous protected evidence remains unknown. */
    fun observe(tenure:CrownTenure,revision:String,from:Instant,now:Instant,leadSeconds:Long,ledger:SqlCrownActivityStore):Boolean {
        val evidence=source.connection.use connection@{ c ->
            c.autoCommit=false
            try {
                val rows=c.prepareStatement("""SELECT o.occurrence_id,o.state,o.scheduled_at,a.announced_at,d.snapshot,o.policy_revision
                    FROM koth_crown_occurrences o LEFT JOIN koth_crown_announcements a ON a.occurrence=o.occurrence_id AND a.revision=o.policy_revision
                    LEFT JOIN koth_match_decisions d ON d.event=o.match_id
                    WHERE o.purpose='HOLDER_CHALLENGE' AND (julianday(o.scheduled_at) IS NULL OR
                      (julianday(o.scheduled_at)>=julianday(?) AND julianday(o.scheduled_at)<=julianday(?)))
                    ORDER BY o.scheduled_at LIMIT 1001""").use { s ->
                    // Include a one-second margin so SQLite date precision cannot exclude boundary evidence.
                    s.setString(1,from.minusSeconds(1).toString());s.setString(2,now.plusSeconds(1).toString())
                    s.executeQuery().use { r -> buildList {while(r.next())add(listOf(r.getString(1),r.getString(2),r.getString(3),
                        r.getString(4),r.getString(5),r.getString(6)))} }
                }
                if(rows.size>1000)return@connection false to emptyList<CrownOpportunity>()
                var complete=true
                val observations=mutableListOf<CrownOpportunity>()
                for(row in rows) {
                    val at=Instant.parse(row[2]);if(at.isBefore(from) || at.isAfter(now))continue
                    if(row[5]!=revision) {complete=false;continue}
                    if(row[1]=="CANCELLED")continue
                    val announced=row[3]?.toLongOrNull()?.let(Instant::ofEpochMilli)
                    if(announced==null || announced.isAfter(at.minusSeconds(leadSeconds))) {complete=false;continue}
                    if(row[1]!="COMPLETED") {complete=false;continue}
                    val observation=SqlCrownFinalObservation(source).read(c,row[0]!!,revision)
                    if(observation==null){complete=false;continue}
                    val decoded=runCatching {MatchSnapshotCodec.decode(row[4]!!)}.getOrNull()
                    if(decoded==null){complete=false;continue}
                    val contributions=decoded.first.contributions.filter{it.player==tenure.holder}
                    val persisted=c.prepareStatement("SELECT guild,seconds,eligible FROM koth_match_players WHERE event=? AND player=?").use { s ->
                        s.setString(1,decoded.first.eventId.toString());s.setString(2,tenure.holder.toString())
                        s.executeQuery().use { r -> if(r.next())MatchContribution(tenure.holder,r.getString(1),r.getLong(2),r.getInt(3)==1) else null }
                    }
                    if(contributions.size>1 || contributions.singleOrNull()!=persisted){complete=false;continue}
                    val participated=SqlCrownFinalObservation(source).qualifies(c,decoded.first,decoded.second,tenure.holder)
                    observations.add(CrownOpportunity(row[0]!!,decoded.first.at,
                        if(persisted?.eligible==false)CrownOpportunityStatus.CANCELLED_OR_INELIGIBLE else CrownOpportunityStatus.VERIFIED_ELIGIBLE,participated))
                }
                c.commit()
                complete to observations
            } finally { c.rollback() }
        }
        var complete=evidence.first
        evidence.second.forEach {if(!ledger.record(tenure,it))complete=false}
        return complete
    }
    fun winner(binding:RecoveryEventBinding,revision:String,requirements:CrownQuestRequirements?,requireQuest:Boolean?,leadSeconds:Long,
               questRevision:String=revision):RecoveryEventWinner? {
        if(requireQuest==null || (requireQuest && requirements==null))return null
        return source.connection.use connection@{ c ->
            c.autoCommit=false
            try {
                c.createStatement().use{it.executeUpdate("UPDATE koth_crown_recovery_events SET retired=retired WHERE retired=0")}
                val observed=SqlCrownFinalObservation(source).read(c,binding.occurrence,questRevision) ?: return@connection null
                if(observed.purpose!=CrownEventPurpose.RECOVERY_CROWN_FINAL)return@connection null
                if(requireQuest && (observed.questTotals.wins<requirements!!.wins || observed.questTotals.scoringSeconds<requirements.scoringSeconds ||
                            observed.questTotals.distinctUtcDays<requirements.distinctDays))return@connection null
                val consent=c.prepareStatement("""SELECT o.scheduled_at,a.announced_at FROM koth_crown_occurrences o
                    JOIN koth_crown_announcements a ON a.occurrence=o.occurrence_id AND a.revision=o.policy_revision
                    JOIN koth_crown_final_consent f ON f.occurrence_id=o.occurrence_id AND f.policy_revision=o.policy_revision
                    WHERE o.occurrence_id=? AND o.policy_revision=? AND f.player=?""").use { s ->
                    s.setString(1,binding.occurrence);s.setString(2,revision);s.setString(3,observed.winnerId.toString())
                    s.executeQuery().use { r -> r.next() && !Instant.ofEpochMilli(r.getLong(2)).isAfter(Instant.parse(r.getString(1)).minusSeconds(leadSeconds)) }
                }
                if(!consent)return@connection null
                val updated=c.prepareStatement("""UPDATE koth_crown_recovery_events SET match_id=?,winner=?
                    WHERE occurrence=? AND recovery=? AND instance=? AND holder=? AND generation=? AND retired=0
                    AND (match_id IS NULL OR (match_id=? AND winner=?))""").use { s ->
                    s.setString(1,observed.eventId.toString());s.setString(2,observed.winnerId.toString());s.setString(3,binding.occurrence)
                    s.setString(4,binding.recovery.toString());s.setString(5,binding.tenure.instance.toString());s.setString(6,binding.tenure.holder.toString());s.setLong(7,binding.tenure.generation)
                    s.setString(8,observed.eventId.toString());s.setString(9,observed.winnerId.toString());s.executeUpdate()==1
                }
                if(!updated)return@connection null
                c.commit();RecoveryEventWinner(binding,observed.eventId,observed.winnerId)
            } finally {c.rollback()}
        }
    }
}
