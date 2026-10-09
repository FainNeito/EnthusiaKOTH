package net.badgersmc.ek.infrastructure.persistence

import net.badgersmc.ek.api.KothProgressionV1
import net.badgersmc.ek.application.*
import java.sql.Connection
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import javax.sql.DataSource

/** Own database only. Acceptance is checked against the protected outcome ledger. */
class SqlProgressionStore(private val source: DataSource, private val policy: () -> ProgressionPolicy) :
    MatchProgressionSink, KothProgressionV1 {
    private data class Cached(val policy:ProgressionPolicy,val revision:Long,val value:Map<String,Int>)
    private val revision=java.util.concurrent.atomic.AtomicLong()
    private val progressCache=object:LinkedHashMap<UUID,Cached>(512,0.75f,true) {
        override fun removeEldestEntry(eldest:MutableMap.MutableEntry<UUID,Cached>?) = size>512
    }
    fun init() = source.connection.use { c -> c.createStatement().use { s ->
        s.execute("CREATE TABLE IF NOT EXISTS koth_matches(id TEXT PRIMARY KEY,arena TEXT,family TEXT,origin TEXT,at INTEGER,winner TEXT,detail TEXT)")
        s.execute("CREATE TABLE IF NOT EXISTS koth_match_players(event TEXT,player TEXT,guild TEXT,seconds INTEGER,eligible INTEGER,PRIMARY KEY(event,player))")
        s.execute("CREATE INDEX IF NOT EXISTS koth_match_player ON koth_match_players(player)")
        s.execute("CREATE TABLE IF NOT EXISTS koth_match_sides(event TEXT,side TEXT,PRIMARY KEY(event,side))")
        s.execute("CREATE TABLE IF NOT EXISTS koth_challenge_awards(player TEXT,challenge TEXT,at INTEGER,event TEXT,PRIMARY KEY(player,challenge))")
        s.execute("CREATE TABLE IF NOT EXISTS koth_exclusive_owners(reward TEXT PRIMARY KEY,player TEXT NOT NULL,event TEXT NOT NULL,at INTEGER NOT NULL)")
        s.execute("CREATE TABLE IF NOT EXISTS koth_claims(id TEXT PRIMARY KEY,player TEXT,kind TEXT,value TEXT,amount INTEGER,status TEXT,detail TEXT)")
        s.execute("CREATE INDEX IF NOT EXISTS koth_claim_player ON koth_claims(player,status)")
        s.execute("CREATE TABLE IF NOT EXISTS koth_package_options(claim TEXT,package TEXT,cents INTEGER,definition TEXT,units INTEGER,PRIMARY KEY(claim,package))")
        s.execute("CREATE TABLE IF NOT EXISTS koth_guild_changes(id INTEGER PRIMARY KEY AUTOINCREMENT,at INTEGER,player TEXT,guild TEXT,kind TEXT)")
        s.execute("CREATE INDEX IF NOT EXISTS koth_guild_change_player ON koth_guild_changes(player,at)")
        s.execute("CREATE TABLE IF NOT EXISTS koth_claim_reviews(id INTEGER PRIMARY KEY AUTOINCREMENT,claim TEXT,actor TEXT,at INTEGER,action TEXT,reason TEXT)")
    } }

    override fun complete(match: VerifiedMatch) = complete(match, policy())

    override fun complete(match: VerifiedMatch, frozenPolicy: ProgressionPolicy) {
        require(match.contributions.size <= 2048 && match.opponents.size <= 2048)
        val cfg = frozenPolicy.also { it.validate() }
        if (!cfg.enabled) return
        transaction { c ->
            // Verify acceptance in the authoritative ledger, never trust a Bukkit event.
            val accepted = c.prepareStatement("SELECT accepted FROM koth_reward_audit WHERE event_id=? AND arena=?").use { s ->
                s.setString(1, match.eventId.toString()); s.setString(2, match.arena)
                s.executeQuery().use { it.next() && it.getInt(1) == 1 }
            }
            val valid = accepted && match.winner != null && match.source in setOf("SCHEDULED", "PLAYER_COMMAND", "GUI", "FLARE") &&
                !match.relationChanged && match.oppositionSeconds >= cfg.minimumOppositionSeconds
            val note = if (valid) "VERIFIED; ${match.detail}" else "NO_CHALLENGE_CREDIT; ${match.detail}"
            val recipients = match.contributions.filter { it.eligible && it.seconds >= cfg.minimumScoringSeconds &&
                (cfg.minimumRosterAgeSeconds == 0L || rosterAge(c, it.player, match.at)?.let { age -> age >= cfg.minimumRosterAgeSeconds } == true)
            }.distinctBy { it.player }
            val eligibleIds = recipients.map { it.player }.toSet()
            val inserted = c.prepareStatement("INSERT OR IGNORE INTO koth_matches VALUES(?,?,?,?,?,?,?)").use { s ->
                s.setString(1, match.eventId.toString()); s.setString(2, match.arena); s.setString(3, match.family)
                s.setString(4, match.source); s.setLong(5, match.at.toEpochMilli()); s.setString(6, match.winner)
                s.setString(7, note.take(16000)); s.executeUpdate() == 1
            }
            if (!inserted) return@transaction
            c.prepareStatement("INSERT INTO koth_match_players VALUES(?,?,?,?,?)").use { s ->
                match.contributions.distinctBy { it.player }.forEach { p ->
                    s.setString(1, match.eventId.toString()); s.setString(2, p.player.toString()); s.setString(3, p.guild)
                    s.setLong(4, p.seconds); s.setInt(5, if (valid && p.player in eligibleIds) 1 else 0)
                    s.addBatch()
                }; s.executeBatch()
            }
            c.prepareStatement("INSERT INTO koth_match_sides VALUES(?,?)").use { s ->
                match.opponents.forEach { s.setString(1, match.eventId.toString()); s.setString(2, it); s.addBatch() }; s.executeBatch()
            }
            if (!valid) return@transaction
            val cents = RewardPool.split(cfg.poolCents, recipients)
            val units = RewardPool.split(cfg.packageUnits.toLong(), recipients)
            cents.forEach { (player, amount) ->
                if (cfg.packages.isEmpty()) {
                    if (amount > 0) claim(c, "event:${match.eventId}:$player:money", player, "MONEY", "", amount)
                } else if (amount > 0 || (units[player] ?: 0) > 0) {
                    val id = "event:${match.eventId}:$player:choice"
                    claim(c, id, player, "CHOICE", "", 0)
                    c.prepareStatement("INSERT INTO koth_package_options VALUES(?,?,?,?,?)").use { s ->
                        cfg.packages.forEach { p ->
                            s.setString(1,id); s.setString(2,p.id); s.setLong(3,amount)
                            s.setString(4,p.loreDefinition); s.setLong(5,units[player] ?: 0); s.addBatch()
                        }; s.executeBatch()
                    }
                }
            }
            recipients.sortedWith(compareByDescending<MatchContribution> { it.seconds }.thenBy { it.player.toString() }).forEach { p ->
                val totals = totals(c,p.player)
                cfg.challenges.filter { totals.progress(it) == 100 && (!it.exclusive || it.loreDefinition.isNotBlank()) }.forEach { rule ->
                    val earned = c.prepareStatement("INSERT OR IGNORE INTO koth_challenge_awards VALUES(?,?,?,?)").use { s ->
                        s.setString(1,p.player.toString()); s.setString(2,rule.id); s.setLong(3,match.at.toEpochMilli())
                        s.setString(4,match.eventId.toString()); s.executeUpdate() == 1
                    }
                    if (earned) {
                        val owns = !rule.exclusive || c.prepareStatement("INSERT OR IGNORE INTO koth_exclusive_owners VALUES(?,?,?,?)").use { s ->
                            s.setString(1,rule.id); s.setString(2,p.player.toString()); s.setString(3,match.eventId.toString())
                            s.setLong(4,match.at.toEpochMilli()); s.executeUpdate() == 1
                        }
                        // A title is earned individually; the physical relic has one lifetime owner.
                        if (rule.tag.isNotBlank()) claim(c,"challenge:${rule.id}:${p.player}:tag",p.player,"TAG",rule.tag,1)
                        if (owns && rule.loreDefinition.isNotBlank()) claim(c,"challenge:${rule.id}:${p.player}:relic",p.player,"LORE",rule.loreDefinition,1)
                    }
                }
            }
            if (cfg.guildXpCommand.isNotBlank() && match.winner!!.startsWith("guild:")) {
                claim(c,"event:${match.eventId}:guild-xp",UUID.fromString(match.winner.substringAfter(':')),
                    "GUILD_XP",cfg.guildXpCommand.replace("{GUILD_UUID}",match.winner.substringAfter(':')),1)
            }
        }
        revision.incrementAndGet()
    }

    private fun claim(c: Connection,id:String,player:UUID,kind:String,value:String,amount:Long) {
        c.prepareStatement("INSERT OR IGNORE INTO koth_claims VALUES(?,?,?,?,?,'PENDING','')").use { s ->
            s.setString(1,id); s.setString(2,player.toString()); s.setString(3,kind); s.setString(4,value); s.setLong(5,amount); s.executeUpdate()
        }
    }

    /** Frozen package options; caller cannot supply prices, definitions or recipients. */
    fun choose(id:String,player:UUID,packageId:String):Boolean = transaction { c ->
        val open = c.prepareStatement("SELECT 1 FROM koth_claims WHERE id=? AND player=? AND kind='CHOICE' AND status='PENDING'").use { s ->
            s.setString(1,id); s.setString(2,player.toString()); s.executeQuery().use { it.next() }
        }
        if (!open) return@transaction false
        c.prepareStatement("SELECT cents,definition,units FROM koth_package_options WHERE claim=? AND package=?").use { s ->
            s.setString(1,id); s.setString(2,packageId)
            s.executeQuery().use { r ->
                if (!r.next()) return@transaction false
                if (r.getLong(1)>0) claim(c,"$id:money",player,"MONEY","",r.getLong(1))
                repeat(r.getInt(3)) { index -> claim(c,"$id:item:$index",player,"LORE",r.getString(2),1) }
            }
        }
        c.prepareStatement("UPDATE koth_claims SET status='CHOSEN',value=? WHERE id=?").use { s ->
            s.setString(1,packageId); s.setString(2,id); s.executeUpdate()
        }; true
    }

    fun options(id:String,player:UUID):List<String> = source.connection.use { c ->
        c.prepareStatement("SELECT o.package FROM koth_package_options o JOIN koth_claims p ON p.id=o.claim WHERE o.claim=? AND p.player=? ORDER BY o.package").use { s ->
            s.setString(1,id); s.setString(2,player.toString()); s.executeQuery().use { r -> buildList { while(r.next()) add(r.getString(1)) } }
        }
    }

    fun claims(player:UUID):List<ProgressionClaim> = source.connection.use { c ->
        c.prepareStatement("SELECT * FROM koth_claims WHERE player=? ORDER BY rowid DESC LIMIT 200").use { s ->
            s.setString(1,player.toString()); s.executeQuery().use { r -> buildList { while(r.next()) add(ProgressionClaim(r.getString("id"),player,r.getString("kind"),r.getString("value"),r.getLong("amount"),r.getString("status"),r.getString("detail"))) } }
        }
    }
    fun transition(id:String,player:UUID,from:String,to:String,detail:String=""):Boolean = source.connection.use { c ->
        c.prepareStatement("UPDATE koth_claims SET status=?,detail=? WHERE id=? AND player=? AND status=?").use { s ->
            s.setString(1,to); s.setString(2,detail.take(1000)); s.setString(3,id); s.setString(4,player.toString()); s.setString(5,from); s.executeUpdate()==1
        }
    }
    fun recoverIdempotentClaims() = source.connection.use { c -> c.createStatement().use {
        it.executeUpdate("UPDATE koth_claims SET status='PENDING',detail='Retry using original operation id' WHERE status='SENDING' AND kind IN ('LORE','TAG')")
    } }
    fun pendingGuildClaims(): List<ProgressionClaim> = source.connection.use { c -> c.createStatement().use { s ->
        s.executeQuery("SELECT * FROM koth_claims WHERE kind='GUILD_XP' AND status='PENDING' LIMIT 20").use { r -> buildList {
            while(r.next()) add(ProgressionClaim(r.getString("id"),UUID.fromString(r.getString("player")),r.getString("kind"),r.getString("value"),1,r.getString("status"),r.getString("detail")))
        } }
    } }
    fun reviewClaims(): List<String> = source.connection.use { c -> c.createStatement().use { s ->
        s.executeQuery("SELECT id,player,kind,detail FROM koth_claims WHERE status='REVIEW' LIMIT 100").use { r -> buildList {
            while(r.next()) add("${r.getString(1)} owner=${r.getString(2)} ${r.getString(3)}: ${r.getString(4)}")
        } }
    } }
    /** Staff must reconcile the external provider first; no creation or ownership transfer. */
    fun reconcile(id:String,actor:String,paid:Boolean,reason:String):Boolean = transaction { c ->
        require(reason.isNotBlank())
        val changed=c.prepareStatement("UPDATE koth_claims SET status=?,detail=? WHERE id=? AND status='REVIEW' AND kind IN ('MONEY','GUILD_XP')").use { s ->
            s.setString(1,if(paid) "RECONCILED" else "PENDING"); s.setString(2,reason.take(1000)); s.setString(3,id); s.executeUpdate()==1
        }
        if(changed) c.prepareStatement("INSERT INTO koth_claim_reviews(claim,actor,at,action,reason) VALUES(?,?,?,?,?)").use { s ->
            s.setString(1,id); s.setString(2,actor); s.setLong(3,Instant.now().toEpochMilli()); s.setString(4,if(paid) "CONFIRMED_PAID" else "CONFIRMED_UNPAID_RETRY"); s.setString(5,reason.take(1000)); s.executeUpdate()
        }
        changed
    }
    fun exclusiveOwner(id:String):UUID? = source.connection.use { c -> c.prepareStatement("SELECT player FROM koth_exclusive_owners WHERE reward=?").use { s ->
        s.setString(1,id); s.executeQuery().use { if(it.next()) UUID.fromString(it.getString(1)) else null }
    } }
    fun history(limit:Int=28):List<MatchReport> = source.connection.use { c -> c.prepareStatement("SELECT * FROM koth_matches ORDER BY at DESC,id LIMIT ?").use { s ->
        s.setInt(1,limit.coerceIn(1,100)); s.executeQuery().use { r -> buildList { while(r.next()) add(MatchReport(r.getString("id"),r.getString("arena"),Instant.ofEpochMilli(r.getLong("at")),r.getString("winner"),r.getString("detail"))) } }
    } }
    fun report(id:String):List<String> = source.connection.use { c ->
        buildList {
            c.prepareStatement("SELECT player,guild,seconds,eligible FROM koth_match_players WHERE event=? ORDER BY seconds DESC LIMIT 100").use { s ->
                s.setString(1,id); s.executeQuery().use { while(it.next()) add("${it.getString(1)} ${it.getString(2)} ${it.getLong(3)}s eligible=${it.getInt(4)==1}") }
            }
            c.prepareStatement("SELECT id,kind,status,detail FROM koth_claims WHERE id LIKE ? LIMIT 100").use { s ->
                s.setString(1,"event:$id:%"); s.executeQuery().use { while(it.next()) add("${it.getString(1)} ${it.getString(2)} ${it.getString(3)} ${it.getString(4)}") }
            }
            // Flags are evidence prompts, not an accusation or automatic punishment.
            c.prepareStatement("SELECT COUNT(DISTINCT b.event) FROM koth_match_sides a JOIN koth_match_sides b ON a.side=b.side WHERE a.event=? AND b.event<>a.event").use { s ->
                s.setString(1,id); s.executeQuery().use { if(it.next() && it.getInt(1)>=3) add("FLAG: repeated opponent group; inspect activity and winner alternation") }
            }
            c.prepareStatement("SELECT detail FROM koth_matches WHERE id=?").use { s -> s.setString(1,id); s.executeQuery().use { r ->
                if(r.next() && ("allianceChanged=true" in r.getString(1) || "invalidPlayers=[]" !in r.getString(1))) add("FLAG: inspect relation or roster instability; no automatic punishment")
            } }
            c.prepareStatement("SELECT COUNT(*) FROM koth_match_players WHERE event=? AND eligible=1").use { s -> s.setString(1,id); s.executeQuery().use { r ->
                if(r.next() && r.getInt(1)<=1) add("FLAG: zero or single eligible recipient; compare match activity")
            } }
        }
    }
    fun change(player:UUID?,guild:UUID,kind:String,at:Instant=Instant.now()) = source.connection.use { c -> c.prepareStatement("INSERT INTO koth_guild_changes(at,player,guild,kind) VALUES(?,?,?,?)").use { s ->
        s.setLong(1,at.toEpochMilli()); s.setString(2,player?.toString()); s.setString(3,guild.toString()); s.setString(4,kind); s.executeUpdate()
    } }
    fun rosterAge(player:UUID,now:Instant):Long? = source.connection.use { rosterAge(it,player,now) }
    private fun rosterAge(c:Connection,player:UUID,now:Instant):Long? = c.prepareStatement("SELECT MAX(at) FROM koth_guild_changes WHERE player=?").use { s ->
        s.setString(1,player.toString()); s.executeQuery().use { r -> if(r.next()) r.getLong(1).takeUnless { r.wasNull() }?.let { (now.toEpochMilli()-it)/1000 } else null }
    }
    @Synchronized override fun progress(player:UUID):Map<String,Int>? = runCatching {
        val cfg=policy()
        if(!cfg.enabled) return null
        val currentRevision=revision.get()
        progressCache[player]?.takeIf { it.policy==cfg && it.revision==currentRevision }?.let { return it.value.toMap() }
        source.connection.use { c ->
            val totals=totals(c,player)
            cfg.challenges.associate { rule ->
                val earned=c.prepareStatement("SELECT 1 FROM koth_challenge_awards WHERE player=? AND challenge=?").use { s ->
                    s.setString(1,player.toString()); s.setString(2,rule.id); s.executeQuery().use { it.next() }
                }
                rule.id to if(earned) 100 else minOf(99,totals.progress(rule))
            }.also { progressCache[player]=Cached(cfg,currentRevision,it) }.toMap()
        }
    }.getOrNull()
    private fun totals(c:Connection,player:UUID):ChallengeTotals {
        var wins=0; var seconds=0L
        val days=mutableSetOf<String>(); val arenas=mutableSetOf<String>(); val groups=mutableListOf<MutableSet<String>>()
        c.prepareStatement("SELECT m.id,m.at,m.arena,p.seconds FROM koth_matches m JOIN koth_match_players p ON p.event=m.id WHERE p.player=? AND p.eligible=1 ORDER BY m.at LIMIT 10000").use { s ->
            s.setString(1,player.toString()); s.executeQuery().use { r -> while(r.next()) {
                wins++; seconds+=r.getLong(4); days.add(Instant.ofEpochMilli(r.getLong(2)).atZone(ZoneOffset.UTC).toLocalDate().toString()); arenas.add(r.getString(3))
                c.prepareStatement("SELECT side FROM koth_match_sides WHERE event=?").use { q -> q.setString(1,r.getString(1)); q.executeQuery().use { sides -> while(sides.next()) {
                    val next=sides.getString(1).split(',').toMutableSet()
                    val overlaps=groups.filter { group -> group.any { it in next } }
                    overlaps.forEach { next.addAll(it) }; groups.removeAll(overlaps.toSet()); groups.add(next)
                } } }
            } }
        }
        return ChallengeTotals(wins,groups.size,arenas.size,days.size,seconds)
    }
    @Synchronized private fun <T> transaction(action:(Connection)->T):T = source.connection.use { c ->
        c.autoCommit=false
        try {
            // Acquire SQLite writer lock before any eligibility/ownership read.
            c.createStatement().use { it.executeUpdate("UPDATE koth_claims SET status=status WHERE id='__lock__'") }
            val value=action(c); c.commit(); value
        } catch(error:Throwable) { runCatching { c.rollback() }; throw error }
    }
}
