package net.badgersmc.ek.infrastructure.persistence

import net.badgersmc.ek.application.*
import java.io.*
import java.time.Instant
import java.util.Base64
import java.util.UUID

/** Versioned value-only encoding: no Java object deserialization or executable YAML. */
internal object MatchSnapshotCodec {
    fun encode(m: VerifiedMatch, p: ProgressionPolicy): String {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { o ->
            o.writeInt(1)
            listOf(m.eventId.toString(),m.arena,m.family,m.source,m.at.toString(),m.winner.orEmpty(),m.detail,m.arenaIdentity).forEach(o::writeUTF)
            fun strings(v: Collection<String>) { o.writeInt(v.size); v.sorted().forEach(o::writeUTF) }
            strings(m.opponents); strings(m.winnerSide)
            o.writeInt(m.contributions.size)
            m.contributions.forEach { o.writeUTF(it.player.toString()); o.writeUTF(it.guild); o.writeLong(it.seconds); o.writeBoolean(it.eligible) }
            o.writeInt(m.oppositionSeconds); o.writeBoolean(m.relationChanged)
            listOf(m.evidence.controlChanges,m.evidence.opposingScoreSeconds,m.evidence.reciprocalCombatSeconds,m.evidence.contestedSeconds).forEach(o::writeInt)
            o.writeBoolean(p.enabled); o.writeLong(p.poolCents); o.writeInt(p.packageUnits); o.writeDouble(p.contributionPercent)
            o.writeLong(p.minimumScoringSeconds); o.writeInt(p.minimumOppositionSeconds); o.writeLong(p.minimumRosterAgeSeconds); o.writeUTF(p.guildXpCommand)
            o.writeInt(p.packages.size); p.packages.forEach { o.writeUTF(it.id); o.writeLong(it.moneyCents); o.writeUTF(it.loreDefinition); o.writeInt(it.units) }
            o.writeInt(p.challenges.size); p.challenges.forEach {
                o.writeUTF(it.id); listOf(it.wins,it.opponents,it.arenas,it.days).forEach(o::writeInt); o.writeLong(it.seconds)
                o.writeUTF(it.tag); o.writeUTF(it.loreDefinition); o.writeBoolean(it.exclusive)
            }
            val i=p.integrity
            o.writeBoolean(i.requireContest); o.writeInt(i.minimumControlChanges); o.writeInt(i.minimumOpposingScoreSeconds); o.writeInt(i.minimumReciprocalCombatSeconds)
            o.writeBoolean(i.holdSuspicious); o.writeInt(i.patternWindowHours); o.writeInt(i.patternMinimumMatches)
            o.writeLong(i.playerDailyCents); o.writeInt(i.playerDailyUnits); o.writeLong(i.sideDailyCents); o.writeInt(i.sideDailyUnits)
            o.writeBoolean(i.requireReadiness); strings(i.acceptedArenas)
        }
        return Base64.getEncoder().encodeToString(bytes.toByteArray()).also { require(it.length<=2_000_000) }
    }
    fun decode(value: String): Pair<VerifiedMatch,ProgressionPolicy> {
        require(value.length <= 2_000_000)
        return DataInputStream(ByteArrayInputStream(Base64.getDecoder().decode(value))).use { d ->
            require(d.readInt()==1) { "Unsupported frozen match version" }
            val h=List(8) { d.readUTF() }
            fun count(max: Int): Int = d.readInt().also { require(it in 0..max) }
            fun strings(): Set<String> = List(count(2048)) { d.readUTF() }.toSet()
            val opponents=strings(); val side=strings()
            val contributions=List(count(2048)) { MatchContribution(UUID.fromString(d.readUTF()),d.readUTF(),d.readLong(),d.readBoolean()) }
            val opposition=d.readInt(); val changed=d.readBoolean()
            val evidence=ContestEvidence(d.readInt(),d.readInt(),d.readInt(),d.readInt())
            val enabled=d.readBoolean(); val cents=d.readLong(); val units=d.readInt(); val percent=d.readDouble()
            val scoring=d.readLong(); val minimumOpposition=d.readInt(); val roster=d.readLong(); val xp=d.readUTF()
            val packages=List(count(8)) { RewardPackage(d.readUTF(),d.readLong(),d.readUTF(),d.readInt()) }
            val challenges=List(count(32)) { ChallengeRule(d.readUTF(),d.readInt(),d.readInt(),d.readInt(),d.readInt(),d.readLong(),d.readUTF(),d.readUTF(),d.readBoolean()) }
            val integrity=IntegrityPolicy(d.readBoolean(),d.readInt(),d.readInt(),d.readInt(),d.readBoolean(),d.readInt(),d.readInt(),
                d.readLong(),d.readInt(),d.readLong(),d.readInt(),d.readBoolean(),strings())
            require(d.available()==0)
            val p=ProgressionPolicy(enabled,cents,units,percent,scoring,minimumOpposition,packages,challenges,xp,roster,integrity).also { it.validate() }
            VerifiedMatch(UUID.fromString(h[0]),h[1],h[2],h[3],Instant.parse(h[4]),h[5].ifBlank { null },opponents,contributions,opposition,changed,h[6],evidence,side,h[7]) to p
        }
    }
}
