package com.tuntas.app.ui

import com.tuntas.app.domain.*
import com.tuntas.app.platform.scheduling.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.Executor

data class TaskUiState(
    val snapshot: TaskSnapshot = TaskSnapshot(),
    val busy: Boolean = true,
    val loaded: Boolean = false,
    val error: String? = null,
    val saveVersion: Long = 0
)

/** All domain, alarm and reconciliation commands share one application-owned serial executor.
 * No optimistic success: a failure keeps the last published snapshot and exposes a retry message.
 */
class TaskController(
    private val engine: TaskEngine,
    private val adapter: DomainScheduleAdapter,
    private val coordinator: ScheduleCoordinator,
    private val executor: Executor
) {
    private val mutable = MutableStateFlow(TaskUiState())
    val state: StateFlow<TaskUiState> = mutable.asStateFlow()

    fun save(task: Task) = dispatch(saved = true) { engine.saveTask(task, requireUnlocked = true); adapter.reconcile(ReconciliationReason.MANUAL_DIAGNOSTIC) }
    fun delete(id: String) = dispatch { engine.deleteTask(id, requireUnlocked = true); adapter.reconcile(ReconciliationReason.MANUAL_DIAGNOSTIC) }
    fun setAllowlist(packages: Set<String>) = dispatch { engine.setAllowlist(packages, requireUnlocked = true); adapter.reconcile(ReconciliationReason.MANUAL_DIAGNOSTIC) }
    fun setAllowlisted(packageName: String, selected: Boolean) = dispatch {
        val current = engine.snapshot().allowlist
        engine.setAllowlist(if (selected) current + packageName else current - packageName, requireUnlocked = true)
        adapter.reconcile(ReconciliationReason.MANUAL_DIAGNOSTIC)
    }
    fun complete(id: String, token: Occurrence, confirmed: Boolean) {
        if (confirmed) dispatch { check(adapter.complete(id, token, true)) { "Stale confirmation" } }
    }
    // Window/IME events must not disable text fields: doing so drops input focus,
    // hides the keyboard and produces another window event. Serialization is unchanged.
    fun platformEvent(operation: () -> Unit) = dispatch(showBusy = false, verifiesDomain = false, operation = operation)
    fun refresh(reason: ReconciliationReason, finally: () -> Unit = {}) = dispatch(finally = finally) { adapter.reconcile(reason) }
    fun alarm(id: String?, label: String?, finally: () -> Unit) = dispatch(finally = finally) {
        coordinator.onAlarmBroadcastReceived(id, label)
        // Even a stale/ignored alarm must not mark uncertain domain hydration as verified.
        adapter.reconcile(ReconciliationReason.MANUAL_DIAGNOSTIC)
    }

    private fun dispatch(saved: Boolean = false, showBusy: Boolean = true, verifiesDomain: Boolean = true, finally: () -> Unit = {}, operation: () -> Unit) {
        executor.execute {
            if (showBusy) mutable.value = mutable.value.copy(busy = true, error = null)
            try {
                var refused = false
                try { operation() } catch (_: TaskMutationLockedException) {
                    refused = true
                    adapter.reconcile(ReconciliationReason.MANUAL_DIAGNOSTIC)
                }
                mutable.value = TaskUiState(engine.snapshot(), busy = false, loaded = verifiesDomain || mutable.value.loaded,
                    error = if (refused) "Mode terkunci: selesaikan semua task tertunda sebelum mengubah task atau aplikasi yang diizinkan." else if (!verifiesDomain) mutable.value.error else null,
                    saveVersion = mutable.value.saveVersion + if (saved && !refused) 1 else 0)
            } catch (_: Exception) {
                // Neither raw filesystem paths nor user content enter durable Android logs.
                mutable.value = mutable.value.copy(busy = false, loaded = false, error = "Operasi belum terverifikasi. Data atau penjadwalan gagal diproses. Muat ulang sebelum mencoba lagi; jangan hapus data aplikasi.")
            } finally { finally() }
        }
    }
}
