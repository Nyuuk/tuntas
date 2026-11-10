package com.tuntas.app.platform.scheduling

import java.time.ZoneId
import kotlinx.coroutines.flow.StateFlow

/**
 * Alarm delivery mode selected by the scheduling primitive.
 *
 * When `AlarmManager.canScheduleExactAlarms()` is false on Android 12+ (API 31+),
 * Tuntas falls back honestly to `setAndAllowWhileIdle` ([INEXACT_FALLBACK_ALLOW_WHILE_IDLE])
 * and reports degraded readiness ([ScheduleReadiness.DEGRADED_INEXACT_FALLBACK]).
 */
enum class ScheduleDeliveryMode {
    /** Scheduled via `AlarmManager.setExactAndAllowWhileIdle(RTC_WAKEUP, ...)`. */
    EXACT_ALLOW_WHILE_IDLE,

    /** Honest fallback via `AlarmManager.setAndAllowWhileIdle(RTC_WAKEUP, ...)` when exact permission is denied. */
    INEXACT_FALLBACK_ALLOW_WHILE_IDLE,

    /** Trigger timestamp was already due/overdue at schedule or reconciliation time and fired immediately. */
    IMMEDIATE_OVERDUE_EXECUTION,

    /** No pending trigger is currently scheduled. */
    NONE
}

/**
 * Readiness of exact alarm scheduling.
 */
enum class ScheduleReadiness {
    /** Exact alarm permission is granted; alarms use `setExactAndAllowWhileIdle`. */
    READY_EXACT,

    /** Exact alarm permission is denied/revoked; alarms use inexact `setAndAllowWhileIdle` fallback. */
    DEGRADED_INEXACT_FALLBACK
}

/**
 * System or user event that triggered schedule reconciliation.
 *
 * Note: Boot reconciliation runs post-unlock (`ACTION_BOOT_COMPLETED`) only because persistence
 * uses credential-protected storage (`Context.MODE_PRIVATE` SharedPreferences) and the app is
 * not `directBootAware`.
 */
enum class ReconciliationReason {
    APP_STARTUP,
    MANUAL_DIAGNOSTIC,
    BOOT_COMPLETED,
    PACKAGE_REPLACED,
    TIME_SET,
    TIMEZONE_CHANGED,
    EXACT_ALARM_PERMISSION_CHANGED
}

/**
 * Outcome of a schedule reconciliation pass.
 */
enum class ReconciliationOutcome {
    NO_PENDING_TRIGGER,
    RESCHEDULED_FUTURE_TRIGGER,
    FIRED_OVERDUE_TRIGGER
}

/**
 * Persisted next trigger representation stored across process restarts and reboots.
 */
data class PersistedTrigger(
    val triggerId: String,
    val label: String,
    val triggerAtEpochMillis: Long,
    val deliveryMode: ScheduleDeliveryMode,
    val scheduledZoneId: String,
    val createdAtEpochMillis: Long
)

/**
 * Record of the most recent fired schedule trigger.
 */
data class FiredTriggerRecord(
    val triggerId: String,
    val label: String,
    val scheduledAtEpochMillis: Long,
    val firedAtEpochMillis: Long,
    val triggerSource: String
)

/**
 * Record of the most recent boot/time/timezone/permission reconciliation pass.
 */
data class ReconciliationRecord(
    val reason: ReconciliationReason,
    val outcome: ReconciliationOutcome,
    val timestampEpochMillis: Long,
    val zoneId: String,
    val deliveryModeUsed: ScheduleDeliveryMode
)

/**
 * Snapshot of scheduling readiness, persisted next trigger, and diagnostic history.
 */
data class SchedulingState(
    val exactAlarmPermissionGranted: Boolean,
    val readiness: ScheduleReadiness,
    val currentZoneId: String,
    val pendingTrigger: PersistedTrigger? = null,
    val lastFiredTrigger: FiredTriggerRecord? = null,
    val lastReconciliation: ReconciliationRecord? = null
) {
    val isDegraded: Boolean
        get() = readiness == ScheduleReadiness.DEGRADED_INEXACT_FALLBACK
}

/**
 * Candidate next trigger specification that Slice 2's recurrence/task engine can supply.
 */
data class TriggerCandidate(
    val triggerId: String,
    val label: String,
    val triggerAtEpochMillis: Long
)

/**
 * Extension point for Slice 2 (recurrence & task persistence slice) to plug into
 * [ScheduleCoordinator] without Slice 1 implementing any recurrence engine.
 */
fun interface NextTriggerProvider {
    fun computeNextTrigger(nowEpochMillis: Long, zoneId: ZoneId): TriggerCandidate?
}

/**
 * Clock and timezone provider abstraction for deterministic unit testing across time/timezone changes.
 */
interface ClockProvider {
    fun nowEpochMillis(): Long
    fun currentZoneId(): ZoneId
}

/**
 * Platform gateway wrapping `android.app.AlarmManager`.
 */
interface PlatformAlarmGateway {
    /** Returns true if exact alarms can be scheduled (`AlarmManager.canScheduleExactAlarms()` on API 31+). */
    fun canScheduleExactAlarms(): Boolean

    /**
     * Attempts `AlarmManager.setExactAndAllowWhileIdle(RTC_WAKEUP, ...)`.
     * Returns true if scheduled as exact, or false if OS threw SecurityException and fell back to inexact.
     */
    fun setExactAndAllowWhileIdle(
        triggerAtEpochMillis: Long,
        triggerId: String,
        label: String
    ): Boolean

    /** Schedules via `AlarmManager.setAndAllowWhileIdle(RTC_WAKEUP, ...)`. */
    fun setAndAllowWhileIdle(
        triggerAtEpochMillis: Long,
        triggerId: String,
        label: String
    )

    /** Cancels any currently registered `PendingIntent` on `AlarmManager`. */
    fun cancelPendingAlarm()
}

/**
 * Persistence contract for the scheduled next trigger and diagnostic history.
 */
interface TriggerStore {
    fun getPendingTrigger(): PersistedTrigger?
    fun setPendingTrigger(trigger: PersistedTrigger?)
    fun getLastFiredTrigger(): FiredTriggerRecord?
    fun setLastFiredTrigger(record: FiredTriggerRecord)
    fun getLastReconciliation(): ReconciliationRecord?
    fun setLastReconciliation(record: ReconciliationRecord)
}

/**
 * Stable scheduling coordinator interface consumed by Slice 2 (recurrence/domain) and Slice 3 (UI).
 */
interface ScheduleCoordinator {
    val state: StateFlow<SchedulingState>

    /**
     * Schedules (and persists) the next lock trigger at [triggerAtEpochMillis].
     * Uses `setExactAndAllowWhileIdle` when permitted, or `setAndAllowWhileIdle` fallback when denied.
     */
    fun scheduleTrigger(
        triggerId: String,
        label: String,
        triggerAtEpochMillis: Long
    ): PersistedTrigger

    /** Convenience method for scheduling a short diagnostic demo trigger relative to now. */
    fun scheduleDemoTriggerAfterSeconds(
        delaySeconds: Long,
        label: String = "Demo Lock (${delaySeconds}s)"
    ): PersistedTrigger

    /** Cancels any pending trigger in both [TriggerStore] and `AlarmManager`. */
    fun cancelPendingTrigger()

    /** Invoked by [ScheduleAlarmReceiver] when the OS alarm broadcast arrives. */
    fun onAlarmBroadcastReceived(triggerId: String?, label: String?)

    /**
     * Reconciles persisted schedule state after boot, time change, timezone change, or exact-alarm
     * permission state change using the current device local timezone.
     */
    fun reconcile(reason: ReconciliationReason): ReconciliationRecord

    /** Re-queries exact alarm permission and current timezone without modifying triggers. */
    fun refreshPermissionAndTimezoneState(): SchedulingState

    /** Registers an optional [NextTriggerProvider] from future recurrence/domain slices. */
    fun setNextTriggerProvider(provider: NextTriggerProvider?)
}
