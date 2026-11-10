package com.tuntas.app.domain

import com.tuntas.app.platform.blocker.BlockerController
import com.tuntas.app.platform.scheduling.ReconciliationReason
import com.tuntas.app.platform.scheduling.ScheduleCoordinator

/** Integration seam, deliberately not NextTriggerProvider: baseline provider replacement runs before
 * fired callbacks and can hide overdue tasks. Domain is authoritative; recompute all due rows first,
 * then replace the sole platform wakeup with the earliest future lock time.
 * Wire receivers/startup/foreground to reconcile, and coordinator.onTriggerFired to onAlarmFired.
 * Do NOT also attach a NextTriggerProvider or retain the diagnostic unconditional activate callback.
 */
class DomainScheduleAdapter(
    private val engine: TaskEngine,
    private val coordinator: ScheduleCoordinator,
    private val blocker: BlockerController
) {
    @Synchronized
    fun reconcile(reason: ReconciliationReason): TaskSnapshot {
        val state = engine.reconcile() // durable transaction before any platform effect
        blocker.setUserAllowlist(state.allowlist)
        blocker.refreshPermissionState()
        if (state.blocking) blocker.activateLock("Pending tasks") else blocker.deactivateLock()
        coordinator.cancelPendingTrigger() // also clears stale trigger when no future task exists
        engine.nextTrigger()?.let { next ->
            coordinator.scheduleTrigger("domain_${next.lockAt.toEpochMilli()}", "Task wakeup", next.lockAt.toEpochMilli())
        }
        coordinator.reconcile(reason)
        return state
    }

    fun onAlarmFired() { reconcile(ReconciliationReason.MANUAL_DIAGNOSTIC) }

    @Synchronized
    fun complete(id: String, expected: Occurrence, confirmed: Boolean): Boolean {
        if (!confirmed) return false
        val result = engine.complete(id, expected, true)
        reconcile(ReconciliationReason.MANUAL_DIAGNOSTIC)
        return result
    }
}
