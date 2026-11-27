package net.badgersmc.ek.infrastructure.persistence

import net.badgersmc.ek.application.MissedOccurrenceDecision
import net.badgersmc.ek.application.MissedOccurrencePolicy
import net.badgersmc.ek.application.QueuedEvent
import net.badgersmc.ek.application.ScheduleService
import net.badgersmc.ek.domain.EventKind
import net.badgersmc.ek.domain.TeamMode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import java.time.Instant

class OperationalEvidenceTest {
    @TempDir lateinit var temp: Path
    private val at = Instant.parse("2026-11-07T12:00:00Z")
    private val schedule get() = temp.resolve("schedule.dat").toFile()
    private val queue get() = temp.resolve("queue.dat").toFile()
    private fun store(failSchedule: () -> Boolean = { false }) = FileOperationalStateStore(schedule, queue, { _, _ -> },
        schedulePersistInterceptor = { if (failSchedule()) error("injected schedule write failure") })
    private fun row(id: String) = QueuedEvent("hill", EventKind.SCHEDULED, at, teamMode = TeamMode.SOLO, occurrenceId = id)

    private fun clean(): FileOperationalStateStore = store().also {
        it.setLastEvaluation(at)
        assertTrue(it.claim("start:pending", at, "hill", TeamMode.SOLO))
        it.save(listOf(row("queued")))
    }

    @Test fun `clean files produce exact durable evidence`() {
        val snapshot = clean().verifiedSnapshot()!!
        assertEquals(at, snapshot.evaluatedThrough)
        assertEquals(mapOf("start:pending" to ScheduleClaimStatus.PENDING), snapshot.startClaims)
        assertEquals(setOf("queued"), snapshot.queuedOccurrences)
        assertNotNull(store().verifiedSnapshot())
    }

    @Test fun `missing schedule or queue file is unknown rather than empty`() {
        store().also { it.save(emptyList()) }
        assertNull(store().verifiedSnapshot())
        queue.delete();store().setLastEvaluation(at)
        assertNull(store().verifiedSnapshot())
    }

    @Test fun `malformed claim taints schedule evidence across rewrite and restart`() {
        clean()
        schedule.appendText("claim=not-a-time|PENDING|x|-|-\n")
        val loaded = store()
        assertNull(loaded.verifiedSnapshot())
        assertTrue(loaded.claim("start:new", at, "hill", TeamMode.SOLO))
        assertTrue(schedule.readLines().any { it.startsWith("tainted=") })
        assertTrue(schedule.readLines().none { it.contains("not-a-time") })
        assertNull(store().verifiedSnapshot())
    }

    @Test fun `claim with unreadable metadata is kept for duplicate suppression but taints evidence`() {
        clean()
        schedule.appendText("claim=${at.toEpochMilli()}|PENDING|${b64("start:lossy")}|***|-\n")
        val loaded = store()
        assertEquals(ScheduleClaimStatus.PENDING, loaded.claimStatus("start:lossy"))
        assertNull(loaded.verifiedSnapshot())
    }

    @Test fun `malformed or lossy queue rows taint queue evidence while old readers ignore the marker`() {
        clean()
        queue.appendText("q|broken\n")
        val loaded = store()
        assertEquals(listOf("queued"), loaded.load().map { it.occurrenceId })
        assertNull(loaded.verifiedSnapshot())
        loaded.save(loaded.load())
        assertTrue(queue.readLines().any { it.startsWith("t|") })
        val restarted = store()
        assertEquals(listOf("queued"), restarted.load().map { it.occurrenceId })
        assertNull(restarted.verifiedSnapshot())

        File(temp.toFile(), "lossy").mkdirs()
        val lossy = FileOperationalStateStore(File(temp.toFile(), "lossy/s.dat"), File(temp.toFile(), "lossy/q.dat"), { _, _ -> })
        lossy.setLastEvaluation(at)
        File(temp.toFile(), "lossy/q.dat").writeText(
            "q|${b64("hill")}|SCHEDULED|${at.toEpochMilli()}|SOLO|READY|0|${at.toEpochMilli()}|***|-\n")
        assertEquals(listOf(null), lossy.load().map { it.occurrenceId })
        assertNull(lossy.verifiedSnapshot())
    }

    @Test fun `interrupted atomic write and external edits are unknown`() {
        val loaded = clean()
        File(schedule.parentFile, "${schedule.name}.tmp").writeText("partial")
        assertNull(loaded.verifiedSnapshot())
        File(schedule.parentFile, "${schedule.name}.tmp").delete()
        assertNotNull(loaded.verifiedSnapshot())
        schedule.writeText(schedule.readText().lines().filterNot { it.startsWith("claim=") }.joinToString("\n"))
        assertNull(loaded.verifiedSnapshot())
    }

    @Test fun `unflushed forward cursor and failed backwards cursor never overstate evaluation`() {
        var fail = false
        val loaded = store { fail }
        loaded.setLastEvaluation(at); loaded.save(emptyList())
        loaded.setLastEvaluation(at.plusSeconds(30))
        assertEquals(at, loaded.verifiedSnapshot()!!.evaluatedThrough)
        fail = true
        loaded.setLastEvaluation(at.minusSeconds(600))
        assertEquals(at.minusSeconds(600), loaded.verifiedSnapshot()!!.evaluatedThrough)
    }

    @Test fun `sub-millisecond system clock cursor still yields verified evidence after persisting`() {
        val loaded = store()
        loaded.save(emptyList())
        loaded.setLastEvaluation(at.plusNanos(123_456))
        assertEquals(at, loaded.verifiedSnapshot()!!.evaluatedThrough)
        loaded.setLastEvaluation(at.plusSeconds(61).plusNanos(789_000))
        assertEquals(at.plusSeconds(61), loaded.verifiedSnapshot()!!.evaluatedThrough)
        assertEquals(at.plusSeconds(61), store().verifiedSnapshot()!!.evaluatedThrough)
    }

    @Test fun `policy holds every uncertain branch and misses only after a durable closed window`() {
        val closes = at.plus(ScheduleService.MAX_CATCH_UP).plusSeconds(60)
        val after = closes.plusSeconds(1)
        fun snap(through: Instant = after, claims: Map<String, ScheduleClaimStatus> = emptyMap(), queued: Set<String> = emptySet()) =
            OperationalSnapshot(through, claims, queued)
        fun assess(now: Instant = after, snapshot: OperationalSnapshot? = snap(), live: Set<String>? = emptySet()) =
            MissedOccurrencePolicy.assess("slot", at, now, snapshot, live)
        assertEquals(MissedOccurrenceDecision.NOT_YET, assess(now = closes.minusMillis(1)))
        assertEquals(MissedOccurrenceDecision.HOLD, assess(snapshot = null))
        assertEquals(MissedOccurrenceDecision.HOLD, assess(live = null))
        assertEquals(MissedOccurrenceDecision.HOLD, assess(live = setOf("other")))
        assertEquals(MissedOccurrenceDecision.SCHEDULED, assess(snapshot = snap(queued = setOf("slot")), live = setOf("slot")))
        assertEquals(MissedOccurrenceDecision.SCHEDULED, assess(snapshot = snap(claims = mapOf("start:slot" to ScheduleClaimStatus.PENDING))))
        assertEquals(MissedOccurrenceDecision.HOLD, assess(snapshot = snap(claims = mapOf("start:slot" to ScheduleClaimStatus.COMMITTED))))
        assertEquals(MissedOccurrenceDecision.HOLD, assess(snapshot = snap(through = after.plusSeconds(5))))
        assertEquals(MissedOccurrenceDecision.NOT_YET, assess(snapshot = snap(through = closes.minusMillis(1))))
        assertEquals(MissedOccurrenceDecision.MISSED, assess())
    }

    private fun b64(value: String) = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray())
}
