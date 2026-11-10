package com.tuntas.app.ui

import com.tuntas.app.domain.*
import com.tuntas.app.platform.blocker.*
import com.tuntas.app.platform.scheduling.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import java.time.*
import java.util.concurrent.Executor

class TaskControllerTest {
    private class Queue : Executor {
        val jobs = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) { jobs.add(command) }
        fun drain() { while (jobs.isNotEmpty()) jobs.removeFirst().run() }
    }
    private var now = Instant.parse("2026-11-09T10:00:00Z").toEpochMilli()
    private val clock = object : ClockProvider {
        override fun nowEpochMillis() = now
        override fun currentZoneId() = ZoneId.of("UTC")
    }
    private fun controller(repo: TaskRepository, queue: Queue): Pair<TaskController, BlockerController> {
        val engine = TaskEngine(repo, clock)
        val blocker = DefaultBlockerController("com.tuntas.app", InMemoryBlockerStore(), object : BlockerEnvironmentProvider {
            override fun isAccessibilityServiceEnabled() = false
            override fun resolveDynamicSystemSafePackages() = emptySet<String>()
            override fun nowEpochMillis() = clock.nowEpochMillis()
        })
        val gateway = object : PlatformAlarmGateway {
            override fun canScheduleExactAlarms() = false
            override fun setExactAndAllowWhileIdle(triggerAtEpochMillis: Long, triggerId: String, label: String) = false
            override fun setAndAllowWhileIdle(triggerAtEpochMillis: Long, triggerId: String, label: String) {}
            override fun cancelPendingAlarm() {}
        }
        lateinit var adapter: DomainScheduleAdapter
        val coordinator = DefaultScheduleCoordinator(InMemoryTriggerStore(), gateway, clock) { adapter.onAlarmFired() }
        adapter = DomainScheduleAdapter(engine, coordinator, blocker)
        return TaskController(engine, adapter, coordinator, queue) to blocker
    }
    @Test fun queuedBeforeDueAllowlistMutationCannotRemoveEmergencyApp() {
        val queue = Queue()
        val directory = File("build/test-stores/locked-${System.nanoTime()}").apply { mkdirs() }
        val repo = FileTaskRepository(File(directory, "tasks"))
        val task = Task("a", "Belajar", LocalDate.of(2026, 11, 9), LocalTime.of(10, 1), Recurrence.Once)
        TaskEngine(repo, clock).apply { saveTask(task); setAllowlist(setOf("app.safe")) }
        val (controller, blocker) = controller(repo, queue)
        controller.refresh(ReconciliationReason.APP_STARTUP); queue.drain()
        controller.setAllowlisted("app.safe", false) // enqueued while editor/normal UI still open
        now += 60_000
        queue.drain()
        assertEquals(setOf("app.safe"), repo.read().allowlist)
        assertTrue(controller.state.value.snapshot.blocking)
        assertTrue(blocker.state.value.lockActive)
        assertTrue(controller.state.value.error!!.contains("terkunci"))
        directory.deleteRecursively()
    }
    @Test fun cancelAndConfirmFirstAndLastKeepLockThenRestoreNormalMutations() {
        val queue = Queue()
        val directory = File("build/test-stores/ui-${System.nanoTime()}").apply { mkdirs() }
        val (controller, blocker) = controller(FileTaskRepository(File(directory, "tasks")), queue)
        controller.refresh(ReconciliationReason.APP_STARTUP)
        queue.drain()
        val task = Task("a", "Belajar", LocalDate.of(2026, 11, 9), LocalTime.of(9, 0), Recurrence.Daily)
        now = Instant.parse("2026-11-09T08:00:00Z").toEpochMilli()
        controller.save(task)
        assertTrue(controller.state.value.snapshot.tasks.isEmpty()) // queued, never UI-thread disk I/O
        controller.save(task.copy(id = "b"))
        controller.setAllowlist(setOf("org.example.phone"))
        queue.drain()
        now = Instant.parse("2026-11-09T10:00:00Z").toEpochMilli()
        controller.refresh(ReconciliationReason.MANUAL_DIAGNOSTIC); queue.drain()
        assertEquals(2, controller.state.value.snapshot.tasks.size)
        assertTrue(blocker.state.value.lockActive)
        assertTrue(blocker.state.value.isDegraded)
        assertEquals(setOf("org.example.phone"), FileTaskRepository(File(directory, "tasks")).read().allowlist)
        val token = controller.state.value.snapshot.tasks.getValue("a").pending!!
        controller.complete("a", token, false); queue.drain()
        assertEquals(token, controller.state.value.snapshot.tasks.getValue("a").pending)
        controller.complete("a", token, true); queue.drain()
        assertTrue(blocker.state.value.lockActive)
        controller.save(task.copy(title = "Ditolak")); queue.drain()
        assertEquals("Belajar", controller.state.value.snapshot.tasks.getValue("a").task.title)
        val second = controller.state.value.snapshot.tasks.getValue("b").pending!!
        controller.complete("b", second, false); queue.drain()
        assertTrue(controller.state.value.snapshot.blocking)
        controller.complete("b", second, true); queue.drain()
        assertFalse(blocker.state.value.lockActive)
        controller.save(task.copy(title = "Judul baru")); queue.drain()
        assertEquals("Judul baru", controller.state.value.snapshot.tasks.getValue("a").task.title)
        assertNull(controller.state.value.snapshot.tasks.getValue("a").pending)
        controller.setAllowlisted("app.after", true); queue.drain()
        assertTrue(FileTaskRepository(File(directory, "tasks")).read().allowlist.contains("app.after"))
        controller.delete("b"); queue.drain()
        assertEquals(1, FileTaskRepository(File(directory, "tasks")).read().tasks.size)
        directory.deleteRecursively()
    }
    private fun queuedEditRefused(edit: (TaskController, Task) -> Unit) {
        val queue = Queue()
        val directory = File("build/test-stores/edit-${System.nanoTime()}").apply { mkdirs() }
        try {
            val repo = FileTaskRepository(File(directory, "tasks"))
            val task = Task("a", "Original", LocalDate.of(2026, 11, 9), LocalTime.of(10, 2), Recurrence.Daily, leadMinutes = 1)
            TaskEngine(repo, clock).apply { saveTask(task); saveTask(task.copy(id = "b")); setAllowlist(setOf("safe")) }
            val (controller, _) = controller(repo, queue)
            controller.refresh(ReconciliationReason.APP_STARTUP); queue.drain()
            assertFalse(controller.state.value.snapshot.blocking)
            edit(controller, task)
            now += 60_000 // lead due after editor opened / callback queued, before action executes
            queue.drain()
            assertEquals(setOf("a", "b"), repo.read().tasks.keys)
            assertEquals(task, repo.read().tasks.getValue("a").task)
            assertTrue(repo.read().tasks.values.all { it.pending != null })
            assertEquals(setOf("safe"), repo.read().allowlist)
            assertNotNull(controller.state.value.error)
            assertEquals(0L, controller.state.value.saveVersion)
        } finally { directory.deleteRecursively() }
    }
    @Test fun midEditorRescheduleQueuedBeforeLeadDueIsRefused() = queuedEditRefused { c, t -> c.save(t.copy(startTime = LocalTime.NOON)) }
    @Test fun addQueuedBeforeDueIsRefused() = queuedEditRefused { c, t -> c.save(t.copy(id = "new")) }
    @Test fun deleteQueuedBeforeDueCannotRemoveEitherPendingRow() = queuedEditRefused { c, _ -> c.delete("b") }
    @Test fun bulkAllowlistQueuedBeforeDueIsRefused() = queuedEditRefused { c, _ -> c.setAllowlist(setOf("changed")) }
    @Test fun addingAllowedAppQueuedBeforeDueIsRefused() = queuedEditRefused { c, _ -> c.setAllowlisted("changed", true) }

    @Test fun authorizationRechecksClockInsidePersistedUpdateNotEarlierRead() {
        val queue = Queue()
        val directory = File("build/test-stores/boundary-${System.nanoTime()}").apply { mkdirs() }
        try {
            val disk = FileTaskRepository(File(directory, "tasks"))
            var crossDeadlineOnUpdate = false
            val repo = object : TaskRepository {
                override fun read() = disk.read()
                override fun update(transform: (TaskSnapshot) -> TaskSnapshot): TaskSnapshot = disk.update { stored ->
                    if (crossDeadlineOnUpdate) { now += 60_000; crossDeadlineOnUpdate = false }
                    transform(stored)
                }
            }
            val task = Task("a", "Keep", LocalDate.of(2026, 11, 9), LocalTime.of(10, 1), Recurrence.Once)
            TaskEngine(repo, clock).saveTask(task)
            val (controller, _) = controller(repo, queue)
            controller.refresh(ReconciliationReason.APP_STARTUP); queue.drain()
            assertFalse(controller.state.value.snapshot.blocking)
            crossDeadlineOnUpdate = true
            controller.delete("a"); queue.drain()
            assertEquals(task, disk.read().tasks.getValue("a").task)
            assertNotNull(disk.read().tasks.getValue("a").pending)
            assertTrue(controller.state.value.snapshot.blocking)
            assertNotNull(controller.state.value.error)
        } finally { directory.deleteRecursively() }
    }

    @Test fun staleConfirmCannotCompleteNextDailyOccurrence() {
        val queue = Queue()
        val directory = File("build/test-stores/stale-${System.nanoTime()}").apply { mkdirs() }
        try {
            val repo = FileTaskRepository(File(directory, "tasks"))
            val task = Task("a", "Daily", LocalDate.of(2026, 11, 9), LocalTime.of(9, 0), Recurrence.Daily)
            TaskEngine(repo, clock).saveTask(task)
            val (controller, _) = controller(repo, queue)
            controller.refresh(ReconciliationReason.APP_STARTUP); queue.drain()
            val old = repo.read().tasks.getValue("a").pending!!
            controller.complete("a", old, true); queue.drain()
            now += 86_400_000
            controller.refresh(ReconciliationReason.MANUAL_DIAGNOSTIC); queue.drain()
            val next = repo.read().tasks.getValue("a").pending!!
            assertNotEquals(old, next)
            controller.complete("a", old, true); queue.drain()
            assertEquals(next, repo.read().tasks.getValue("a").pending)
            assertNotNull(controller.state.value.error)
            controller.refresh(ReconciliationReason.MANUAL_DIAGNOSTIC); queue.drain()
            assertTrue(controller.state.value.snapshot.blocking)
        } finally { directory.deleteRecursively() }
    }

    @Test fun failedRefreshAfterLoadedStateHidesEditableUiUntilRecovered() {
        val queue = Queue()
        var failing = false
        var snapshot = TaskSnapshot()
        val repo = object : TaskRepository {
            override fun read(): TaskSnapshot { if (failing) throw IOException("private"); return snapshot }
            override fun update(transform: (TaskSnapshot) -> TaskSnapshot): TaskSnapshot {
                if (failing) throw IOException("private")
                snapshot = transform(snapshot); return snapshot
            }
        }
        val (controller, _) = controller(repo, queue)
        controller.refresh(ReconciliationReason.APP_STARTUP); queue.drain()
        assertTrue(controller.state.value.loaded)
        failing = true
        controller.refresh(ReconciliationReason.MANUAL_DIAGNOSTIC); queue.drain()
        assertFalse(controller.state.value.loaded)
        failing = false
        controller.platformEvent {}; queue.drain()
        assertFalse(controller.state.value.loaded) // a window callback is not hydration/reconciliation
        controller.refresh(ReconciliationReason.MANUAL_DIAGNOSTIC); queue.drain()
        assertTrue(controller.state.value.loaded)
    }

    @Test fun rapidAllowlistEditsReadLatestSerializedSelectionAndReceiverAlwaysFinishes() {
        val queue = Queue()
        val directory = File("build/test-stores/selection-${System.nanoTime()}").apply { mkdirs() }
        val (controller, _) = controller(FileTaskRepository(File(directory, "tasks")), queue)
        controller.setAllowlisted("app.one", true)
        controller.setAllowlisted("app.two", true)
        queue.drain()
        assertEquals(setOf("app.one", "app.two"), controller.state.value.snapshot.allowlist)
        controller.setAllowlisted("app.one", false)
        var finished = 0
        controller.alarm("stale", null) { finished++ }
        queue.drain()
        assertEquals(setOf("app.two"), controller.state.value.snapshot.allowlist)
        assertEquals(1, finished)
        controller.platformEvent { throw IOException("private") }; queue.drain()
        assertNotNull(controller.state.value.error)
        controller.refresh(ReconciliationReason.BOOT_COMPLETED) { finished++ }; queue.drain()
        assertEquals(2, finished)
        assertNull(controller.state.value.error)
        directory.deleteRecursively()
    }
    @Test fun windowEventsKeepLoadedEditorEnabledWhileStillSerialized() {
        val queue = Queue()
        val directory = File("build/test-stores/window-${System.nanoTime()}").apply { mkdirs() }
        val (controller, _) = controller(FileTaskRepository(File(directory, "tasks")), queue)
        controller.refresh(ReconciliationReason.APP_STARTUP); queue.drain()
        var busyDuringWindowEvent: Boolean? = null
        controller.platformEvent { busyDuringWindowEvent = controller.state.value.busy }
        assertNull(busyDuringWindowEvent)
        queue.drain()
        assertEquals(false, busyDuringWindowEvent)
        assertTrue(controller.state.value.loaded)
        directory.deleteRecursively()
    }
    @Test fun ioFailureIsVisibleAndNeverPublishesSuccessfulSave() {
        val queue = Queue()
        val (controller, _) = controller(object : TaskRepository {
            override fun read(): TaskSnapshot = throw IOException("sensitive path")
            override fun update(transform: (TaskSnapshot) -> TaskSnapshot): TaskSnapshot = throw IOException("sensitive path")
        }, queue)
        controller.save(Task("a", "Belajar", LocalDate.of(2026, 11, 9), LocalTime.NOON, Recurrence.Once))
        queue.drain()
        assertNotNull(controller.state.value.error)
        assertFalse(controller.state.value.error!!.contains("sensitive"))
        assertEquals(0L, controller.state.value.saveVersion)
        assertFalse(controller.state.value.busy)
        assertFalse(controller.state.value.loaded)
    }
}
