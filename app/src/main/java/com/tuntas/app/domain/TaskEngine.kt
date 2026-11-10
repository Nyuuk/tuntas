package com.tuntas.app.domain

import com.tuntas.app.platform.scheduling.ClockProvider
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/** One durable row per task; completedThrough is a civil-time high-water mark, not an event backlog. */
data class TaskState(
    val task: Task,
    val pending: Occurrence? = null,
    val coveredThrough: LocalDateTime? = null,
    val completedThrough: LocalDateTime? = null
)
data class TaskSnapshot(val tasks: Map<String, TaskState> = emptyMap(), val allowlist: Set<String> = emptySet()) {
    val blocking: Boolean get() = tasks.values.any { it.pending != null }
}
interface TaskRepository {
    fun read(): TaskSnapshot
    /** Atomic durable read/modify/write. A failed write must throw, never report successful completion. */
    fun update(transform: (TaskSnapshot) -> TaskSnapshot): TaskSnapshot
}

/** A refused edit still persists reconciliation, never the requested mutation. */
class TaskMutationLockedException : IllegalStateException("Task mutations are locked")

class TaskEngine(private val repository: TaskRepository, private val clock: ClockProvider) {
    fun snapshot(): TaskSnapshot = repository.read()

    fun saveTask(task: Task, requireUnlocked: Boolean = false) {
        val frozen = task.copy(recurrence = when (val r = task.recurrence) {
            is Recurrence.Weekly -> r.copy(days = r.days.toSet())
            else -> r
        })
        updateForEdit(requireUnlocked) { old ->
            val row = old.tasks[task.id]?.copy(task = frozen) ?: TaskState(frozen)
            reconcile(old.copy(tasks = old.tasks + (task.id to row)), now(), clock.currentZoneId())
        }
    }

    fun deleteTask(id: String, requireUnlocked: Boolean = false) { updateForEdit(requireUnlocked) { it.copy(tasks = it.tasks - id) } }
    fun setAllowlist(packages: Set<String>, requireUnlocked: Boolean = false) {
        require(packages.none { it.isBlank() })
        updateForEdit(requireUnlocked) { it.copy(allowlist = packages.toSet()) }
    }

    /** Authorization samples the injected clock on the persisted read inside the edit transaction.
     * Default remains the domain API's existing behavior; application commands always opt in.
     * A due row discovered here must survive refusal (throw only AFTER the durable update).
     */
    private fun updateForEdit(requireUnlocked: Boolean, edit: (TaskSnapshot) -> TaskSnapshot) {
        var locked = false
        repository.update { stored ->
            val current = if (requireUnlocked) reconcile(stored, now(), clock.currentZoneId()) else stored
            locked = requireUnlocked && current.blocking
            if (locked) current else edit(current)
        }
        if (locked) throw TaskMutationLockedException()
    }
    fun reconcile(): TaskSnapshot = repository.update { reconcile(it, now(), clock.currentZoneId()) }

    /** Pass the exact pending token shown in the dialog; stale dialogs cannot complete a later occurrence. */
    fun complete(id: String, expected: Occurrence, confirmed: Boolean): Boolean {
        if (!confirmed) return false // Cancel is strictly read/write free.
        var completed = false
        repository.update { stored ->
            val state = reconcile(stored, now(), clock.currentZoneId())
            val row = state.tasks[id]
            if (row?.pending != expected) state else {
                completed = true
                val watermark = listOfNotNull(row.completedThrough, row.coveredThrough, expected.localStart).maxOrNull()
                state.copy(tasks = state.tasks + (id to row.copy(pending = null, coveredThrough = null, completedThrough = watermark)))
            }
        }
        return completed
    }

    fun nextTrigger(): Occurrence? = nextTrigger(now(), clock.currentZoneId())

    /** Called after reconciliation; pending rows still schedule their next future occurrence for coalescing. */
    fun nextTrigger(now: Instant, zone: ZoneId): Occurrence? = repository.read().tasks.values.mapNotNull { row ->
        val due = Recurrences.latestDue(row.task, now, zone)?.localStart
        Recurrences.next(row.task, listOfNotNull(row.completedThrough, row.coveredThrough, due).maxOrNull(), zone)
    }.minByOrNull { it.lockAt }

    private fun now() = Instant.ofEpochMilli(clock.nowEpochMillis())
    private fun reconcile(state: TaskSnapshot, now: Instant, zone: ZoneId): TaskSnapshot = state.copy(
        tasks = state.tasks.mapValues { (_, row) ->
            val due = Recurrences.latestDue(row.task, now, zone)
            if (due == null || (row.completedThrough != null && due.localStart <= row.completedThrough)) row
            else row.copy(
                pending = row.pending ?: due,
                coveredThrough = maxOf(row.coveredThrough ?: due.localStart, due.localStart)
            )
        }
    )
}
