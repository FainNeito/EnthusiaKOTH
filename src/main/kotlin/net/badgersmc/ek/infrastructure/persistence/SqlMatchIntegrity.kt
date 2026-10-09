package net.badgersmc.ek.infrastructure.persistence

import net.badgersmc.ek.application.*
import java.sql.Connection
import java.time.ZoneOffset
import java.util.UUID

/** Shares the progression writer transaction: decisions and budget reservations cannot race claims. */
internal object SqlMatchIntegrity {
    fun init(c: Connection) = c.createStatement().use { s ->
        s.execute("CREATE TABLE IF NOT EXISTS koth_match_decisions(event TEXT PRIMARY KEY,status TEXT NOT NULL,snapshot TEXT NOT NULL,flags TEXT NOT NULL,identity TEXT NOT NULL)")
        s.execute("CREATE TABLE IF NOT EXISTS koth_match_reviews(event TEXT,actor TEXT,at INTEGER,decision TEXT,reason TEXT)")
        s.execute("CREATE TABLE IF NOT EXISTS koth_arena_aliases(alias TEXT PRIMARY KEY,identity TEXT NOT NULL)")
        s.execute("CREATE TABLE IF NOT EXISTS koth_arena_geometry(geometry TEXT PRIMARY KEY,identity TEXT NOT NULL)")
        s.execute("CREATE TABLE IF NOT EXISTS koth_daily_allocations(event TEXT,player TEXT,day TEXT,cents INTEGER,units INTEGER,PRIMARY KEY(event,player))")
        s.execute("CREATE INDEX IF NOT EXISTS koth_daily_player ON koth_daily_allocations(day,player)")
        s.execute("CREATE TABLE IF NOT EXISTS koth_daily_sides(event TEXT,side TEXT,PRIMARY KEY(event,side))")
        // Old known pending/paid budgets count too. Choice budgets are reserved before redemption.
        s.execute("INSERT OR IGNORE INTO koth_daily_allocations SELECT m.id,p.player,strftime('%Y-%m-%d',m.at/1000,'unixepoch')," +
            "COALESCE((SELECT SUM(amount) FROM koth_claims WHERE player=p.player AND kind='MONEY' AND id LIKE 'event:'||m.id||':%'),0)+" +
            "COALESCE((SELECT MAX(o.cents) FROM koth_package_options o JOIN koth_claims q ON q.id=o.claim WHERE q.player=p.player AND q.status='PENDING' AND q.id LIKE 'event:'||m.id||':%'),0)," +
            "COALESCE((SELECT MAX(o.units) FROM koth_package_options o JOIN koth_claims q ON q.id=o.claim WHERE q.player=p.player AND q.id LIKE 'event:'||m.id||':%'),0) " +
            "FROM koth_matches m JOIN koth_match_players p ON p.event=m.id WHERE p.eligible=1")
        s.execute("INSERT OR IGNORE INTO koth_daily_sides SELECT id,winner FROM koth_matches WHERE winner IS NOT NULL")
    }

    fun arena(c: Connection, alias: String, geometry: String): String {
        require(alias.isNotBlank() && alias.length<=128 && geometry.isNotBlank() && geometry.length<=512)
        fun lookup(table: String, column: String, key: String): String? = c.prepareStatement("SELECT identity FROM $table WHERE $column=?").use { s ->
            s.setString(1,key); s.executeQuery().use { if(it.next()) it.getString(1) else null }
        }
        val named=lookup("koth_arena_aliases","alias",alias)
        val physical=lookup("koth_arena_geometry","geometry",geometry)
        val identity=physical ?: named ?: alias
        if(named!=null && physical!=null && named!=physical) {
            // Conservative union: changed geometry cannot split accumulated arena identities.
            listOf("koth_arena_aliases","koth_arena_geometry").forEach { table -> c.prepareStatement("UPDATE $table SET identity=? WHERE identity=?").use { s ->
                s.setString(1,physical); s.setString(2,named); s.executeUpdate()
            } }
            c.prepareStatement("UPDATE koth_match_decisions SET identity=? WHERE identity=?").use { s -> s.setString(1,physical); s.setString(2,named); s.executeUpdate() }
        }
        c.prepareStatement("INSERT OR REPLACE INTO koth_arena_aliases VALUES(?,?)").use { s -> s.setString(1,alias); s.setString(2,identity); s.executeUpdate() }
        c.prepareStatement("INSERT OR IGNORE INTO koth_arena_geometry VALUES(?,?)").use { s -> s.setString(1,geometry); s.setString(2,identity); s.executeUpdate() }
        return identity
    }

    fun flags(c: Connection, match: VerifiedMatch, cfg: ProgressionPolicy): List<String> {
        val e=match.evidence
        return buildList {
            if (!cfg.integrity.contested(e)) add("PASSIVE_OPPOSITION")
            val since=match.at.minusSeconds(cfg.integrity.patternWindowHours*3600L).toEpochMilli()
            val identities=match.winnerSide + match.opponents.flatMap { it.split(',') }
            val recent=mutableListOf<VerifiedMatch>()
            c.prepareStatement("SELECT d.snapshot FROM koth_match_decisions d JOIN koth_matches m ON m.id=d.event WHERE m.at>=? AND m.at<=? AND m.origin IN ('SCHEDULED','PLAYER_COMMAND','GUI','FLARE') ORDER BY m.at DESC LIMIT 500").use { s ->
                s.setLong(1,since); s.setLong(2,match.at.toEpochMilli()); s.executeQuery().use { r -> while(r.next()) recent.add(MatchSnapshotCodec.decode(r.getString(1)).first) }
            }
            val opponents=match.opponents.flatMap { it.split(',') }.toSet()
            val pair=recent.filter { prior ->
                val priorOpponents=prior.opponents.flatMap { it.split(',') }.toSet()
                (prior.winnerSide.any { it in match.winnerSide } && priorOpponents.any { it in opponents }) ||
                    (prior.winnerSide.any { it in opponents } && priorOpponents.any { it in match.winnerSide })
            }
            if(pair.size+1>=cfg.integrity.patternMinimumMatches) {
                add("REPEATED_SIDES")
                if(pair.any { prior -> prior.winnerSide.any { it !in match.winnerSide && it in identities } }) add("ALTERNATING_WINNERS")
            }
            val current=match.contributions.associate { it.player to it.guild }
            val switched=recent.count { prior -> prior.contributions.any { current[it.player]?.let { guild -> guild != it.guild } == true } }
            if(switched+1>=cfg.integrity.patternMinimumMatches) add("RECURRING_ACCOUNTS_DIFFERENT_TEAMS")
        }.distinct()
    }

    fun record(c: Connection,m: VerifiedMatch,p: ProgressionPolicy,status: String,flags: List<String>) {
        c.prepareStatement("INSERT INTO koth_match_decisions VALUES(?,?,?,?,?)").use { s ->
            s.setString(1,m.eventId.toString()); s.setString(2,status); s.setString(3,MatchSnapshotCodec.encode(m,p))
            s.setString(4,flags.joinToString(",")); s.setString(5,m.arenaIdentity); s.executeUpdate()
        }
    }

    fun allocate(c: Connection,m: VerifiedMatch,p: ProgressionPolicy,recipients: List<MatchContribution>): Pair<Map<UUID,Long>,Map<UUID,Long>> {
        val day=m.at.atZone(ZoneOffset.UTC).toLocalDate().toString()
        fun playerUsed(player: UUID,column: String): Long = c.prepareStatement("SELECT COALESCE(SUM($column),0) FROM koth_daily_allocations WHERE day=? AND player=?").use { s ->
            s.setString(1,day); s.setString(2,player.toString()); s.executeQuery().use { it.next(); it.getLong(1) }
        }
        fun sideUsed(column: String): Long {
            if(m.winnerSide.isEmpty()) return 0
            val marks=m.winnerSide.joinToString(",") { "?" }
            return c.prepareStatement("SELECT COALESCE(SUM($column),0) FROM koth_daily_allocations WHERE day=? AND event IN (SELECT event FROM koth_daily_sides WHERE side IN ($marks))").use { s ->
                s.setString(1,day); m.winnerSide.sorted().forEachIndexed { i,v -> s.setString(i+2,v) }
                s.executeQuery().use { it.next(); it.getLong(1) }
            }
        }
        fun budget(total: Long,cap: Long,used: Long)=if(cap==0L) total else minOf(total,(cap-used).coerceAtLeast(0))
        val i=p.integrity
        val cents=RewardPool.split(budget(p.poolCents,i.sideDailyCents,sideUsed("cents")),recipients).mapValues { (id,value) -> budget(value,i.playerDailyCents,playerUsed(id,"cents")) }
        val units=RewardPool.split(budget(p.packageUnits.toLong(),i.sideDailyUnits.toLong(),sideUsed("units")),recipients).mapValues { (id,value) -> budget(value,i.playerDailyUnits.toLong(),playerUsed(id,"units")) }
        m.winnerSide.forEach { side -> c.prepareStatement("INSERT OR IGNORE INTO koth_daily_sides VALUES(?,?)").use { s -> s.setString(1,m.eventId.toString()); s.setString(2,side); s.executeUpdate() } }
        recipients.forEach { r -> c.prepareStatement("INSERT INTO koth_daily_allocations VALUES(?,?,?,?,?)").use { s ->
            s.setString(1,m.eventId.toString()); s.setString(2,r.player.toString()); s.setString(3,day)
            s.setLong(4,cents[r.player] ?: 0); s.setLong(5,units[r.player] ?: 0); s.executeUpdate()
        } }
        return cents to units
    }
}
