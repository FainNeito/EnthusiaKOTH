package net.badgersmc.ek.infrastructure.persistence

import java.util.UUID
import javax.sql.DataSource
import net.badgersmc.ek.application.CrownQuestRequirements
import net.badgersmc.ek.domain.CrownEventPurpose

/** One first-award intent per KOTH database. The verified match ID is the stable provider operation ID. */
data class CrownFirstAwardIntent(val occurrence: String, val match: UUID, val winner: UUID, val revision: String)

class SqlCrownFirstAward(private val source: DataSource) {
    /** Revalidate even on replay. A missing result means callers must leave provider custody alone. */
    fun prepare(occurrence: String, revision: String, rules: CrownQuestRequirements): CrownFirstAwardIntent? {
        if (!rules.enabled) return null
        return source.connection.use { c ->
            c.autoCommit = false
            try {
                // Start with a write so concurrent callers serialize before reading eligibility in SQLite.
                c.prepareStatement("""INSERT INTO koth_crown_first_award(singleton,occurrence_id,match_id,winner_id,quest_revision)
                    SELECT 1,o.occurrence_id,m.id,substr(m.winner,6),?
                    FROM koth_crown_occurrences o JOIN koth_matches m ON m.id=o.match_id
                    WHERE o.occurrence_id=? AND o.purpose='FIRST_CROWN_FINAL' AND m.winner LIKE 'solo:%'
                    ON CONFLICT(singleton) DO NOTHING""".trimIndent()).use { s ->
                    s.setString(1, revision)
                    s.setString(2, occurrence)
                    s.executeUpdate()
                }
                val observed = SqlCrownFinalObservation(source).read(c, occurrence, revision)
                val eligible = observed != null && observed.purpose == CrownEventPurpose.FIRST_CROWN_FINAL &&
                    observed.questTotals.wins >= rules.wins && observed.questTotals.scoringSeconds >= rules.scoringSeconds &&
                    observed.questTotals.distinctUtcDays >= rules.distinctDays
                val intent = if (eligible) {
                    c.prepareStatement("""SELECT 1 FROM koth_crown_first_award WHERE singleton=1 AND occurrence_id=?
                        AND match_id=? AND winner_id=? AND quest_revision=?
                        AND EXISTS (SELECT 1 FROM koth_crown_final_consent consent JOIN koth_crown_occurrences o
                            ON o.occurrence_id=consent.occurrence_id AND o.policy_revision=consent.policy_revision
                            WHERE consent.occurrence_id=koth_crown_first_award.occurrence_id
                            AND consent.player=koth_crown_first_award.winner_id)
                        AND NOT EXISTS (SELECT 1 FROM koth_exclusive_owners WHERE reward='sovereign_crown')
                        AND NOT EXISTS (SELECT 1 FROM koth_claims WHERE kind='LORE' AND id GLOB 'challenge:sovereign_crown:*:relic')""".trimIndent()).use { s ->
                        s.setString(1, occurrence)
                        s.setString(2, observed.eventId.toString())
                        s.setString(3, observed.winnerId.toString())
                        s.setString(4, revision)
                        s.executeQuery().use { r ->
                            if (r.next()) CrownFirstAwardIntent(occurrence, observed.eventId, observed.winnerId, revision) else null
                        }
                    }
                } else null
                if (intent == null) c.rollback() else c.commit()
                intent
            } catch (failure: Exception) {
                runCatching { c.rollback() }
                throw failure
            }
        }
    }
}
