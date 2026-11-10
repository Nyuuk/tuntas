package com.tuntas.app.domain

import com.tuntas.app.platform.scheduling.ClockProvider
import java.time.*
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

internal fun domainTempDirectory(prefix: String): java.nio.file.Path = Files.createTempDirectory(
    Files.createDirectories(java.nio.file.Paths.get("build", "domain-test-tmp")), prefix
)

class TaskEngineTest {
    class Time(var now: Instant = Instant.parse("2024-01-01T10:00:00Z"), var zone: ZoneId = ZoneOffset.UTC) : ClockProvider {
        override fun nowEpochMillis() = now.toEpochMilli()
        override fun currentZoneId() = zone
    }
    private fun task(id: String = "a") = Task(id, "Read", LocalDate.of(2024, 1, 1), LocalTime.of(9, 0), Recurrence.Daily)
    private fun engine(time: Time = Time()) = TaskEngine(FileTaskRepository(domainTempDirectory("tuntas-test").resolve("state").toFile()), time)

    @Test fun overlapRequiresAllConfirmedAndCancelDoesNotMutate() {
        val e = engine()
        e.saveTask(task()); e.saveTask(task("b"))
        val before = e.snapshot()
        assertTrue(before.blocking)
        val token = before.tasks.getValue("a").pending!!
        assertFalse(e.complete("a", token, confirmed = false))
        assertEquals(before, e.snapshot())
        assertTrue(e.complete("a", token, confirmed = true))
        assertTrue(e.snapshot().blocking)
        assertTrue(e.complete("b", e.snapshot().tasks.getValue("b").pending!!, true))
        assertFalse(e.snapshot().blocking)
    }
    @Test fun missedYearsCoalesceAndOverdueSurvivesMidnight() {
        val time = Time(); val e = engine(time); e.saveTask(task())
        val original = e.snapshot().tasks.getValue("a").pending
        time.now = Instant.parse("2034-01-02T00:00:00Z"); e.reconcile()
        assertEquals(1, e.snapshot().tasks.size)
        assertEquals(original, e.snapshot().tasks.getValue("a").pending)
        e.complete("a", original!!, true)
        assertFalse(e.snapshot().blocking)
        assertEquals(Instant.parse("2034-01-02T09:00:00Z"), e.nextTrigger()!!.lockAt)
    }
    @Test fun completionSurvivesRestartAndClockRollbackAndNextDayRepeats() {
        val time = Time(); val file = domainTempDirectory("tuntas-restart").resolve("state").toFile()
        var e = TaskEngine(FileTaskRepository(file), time)
        e.saveTask(task()); e.setAllowlist(setOf("org.example.phone"))
        e.complete("a", e.snapshot().tasks.getValue("a").pending!!, true)
        e = TaskEngine(FileTaskRepository(file), time)
        assertEquals(setOf("org.example.phone"), e.snapshot().allowlist)
        time.now = Instant.parse("2023-12-31T10:00:00Z"); e.reconcile()
        assertFalse(e.snapshot().blocking)
        time.now = Instant.parse("2024-01-01T11:00:00Z"); e.reconcile()
        assertFalse(e.snapshot().blocking)
        time.now = Instant.parse("2024-01-02T09:00:00Z"); e.reconcile()
        assertTrue(e.snapshot().blocking)
    }
    @Test fun pendingSurvivesRepositoryRestartAndTimezoneChange() {
        val time = Time(); val file = domainTempDirectory("tuntas-pending").resolve("state").toFile()
        val e = TaskEngine(FileTaskRepository(file), time); e.saveTask(task())
        val pending = e.snapshot().tasks.getValue("a").pending!!
        time.zone = ZoneId.of("Asia/Jakarta")
        val restarted = TaskEngine(FileTaskRepository(file), time)
        assertEquals(pending, restarted.snapshot().tasks.getValue("a").pending)
        assertEquals(Instant.parse("2024-01-02T02:00:00Z"), restarted.nextTrigger()!!.lockAt)
        assertTrue(restarted.complete("a", pending, true))
        assertFalse(restarted.complete("a", pending, true))
    }
    @Test fun futureTaskReconcilesUsingCurrentLocalTimezone() {
        val time = Time(Instant.parse("2024-01-01T00:00:00Z")); val e = engine(time); e.saveTask(task())
        assertEquals(Instant.parse("2024-01-01T09:00:00Z"), e.nextTrigger()!!.lockAt)
        time.zone = ZoneId.of("Asia/Jakarta"); e.reconcile()
        assertEquals(Instant.parse("2024-01-01T02:00:00Z"), e.nextTrigger()!!.lockAt)
    }
    @Test fun editingPendingTaskPreservesObligationAndStaleConfirmationCannotCompleteNextOccurrence() {
        val time = Time(); val e = engine(time); e.saveTask(task())
        val old = e.snapshot().tasks.getValue("a").pending!!
        e.saveTask(task().copy(title = "Updated", startTime = LocalTime.of(11, 0)))
        assertEquals(old, e.snapshot().tasks.getValue("a").pending)
        assertTrue(e.complete("a", old, true))
        time.now = Instant.parse("2024-01-02T12:00:00Z"); e.reconcile()
        assertFalse(e.complete("a", old, true))
        assertTrue(e.snapshot().blocking)
    }
}
