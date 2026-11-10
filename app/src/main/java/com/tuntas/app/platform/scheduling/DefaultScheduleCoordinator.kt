package com.tuntas.app.platform.scheduling

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Default implementation of [ScheduleCoordinator].
 *
 * Implements:
 * - Exact allow-while-idle scheduling when `AlarmManager.canScheduleExactAlarms()` is true.
 * - Honest fallback to inexact `setAndAllowWhileIdle` and [ScheduleReadiness.DEGRADED_INEXACT_FALLBACK]
 *   status when exact alarm permission is denied/revoked.
 * - Persisted next trigger storage across process death and reboot ([TriggerStore]).
 * - Boot, time, timezone, and exact-alarm permission reconciliation adapter.
 * - Clean extension hook ([NextTriggerProvider]) for Slice 2's recurrence engine (no recurrence
 *   engine is included in this baseline slice).
 */
class DefaultScheduleCoordinator(
    private val store: TriggerStore,
    private val alarmGateway: PlatformAlarmGateway,
    private val clockProvider: ClockProvider,
    private val onTriggerFired: (FiredTriggerRecord) -> Unit
) : ScheduleCoordinator {

    private var nextTriggerProvider: NextTriggerProvider? = null

    private val _state = MutableStateFlow(buildCurrentState())
    override val state: StateFlow<SchedulingState> = _state.asStateFlow()

    override fun scheduleTrigger(
        triggerId: String,
        label: String,
        triggerAtEpochMillis: Long
    ): PersistedTrigger {
        val normalizedId = triggerId.trim().ifEmpty { "trigger_${clockProvider.nowEpochMillis()}" }
        val normalizedLabel = label.trim().ifEmpty { "Scheduled Lock" }
        val now = clockProvider.nowEpochMillis()
        val zoneId = clockProvider.currentZoneId().id

        if (triggerAtEpochMillis <= now) {
            alarmGateway.cancelPendingAlarm()
            store.setPendingTrigger(null)
            val fired = FiredTriggerRecord(
                triggerId = normalizedId,
                label = normalizedLabel,
                scheduledAtEpochMillis = triggerAtEpochMillis,
                firedAtEpochMillis = now,
                triggerSource = "IMMEDIATE_OVERDUE"
            )
            store.setLastFiredTrigger(fired)
            refreshPermissionAndTimezoneState()
            onTriggerFired(fired)
            return PersistedTrigger(
                triggerId = normalizedId,
                label = normalizedLabel,
                triggerAtEpochMillis = triggerAtEpochMillis,
                deliveryMode = ScheduleDeliveryMode.IMMEDIATE_OVERDUE_EXECUTION,
                scheduledZoneId = zoneId,
                createdAtEpochMillis = now
            )
        }

        val deliveryMode = registerWithAlarmGateway(
            triggerId = normalizedId,
            label = normalizedLabel,
            triggerAtEpochMillis = triggerAtEpochMillis
        )
        val persisted = PersistedTrigger(
            triggerId = normalizedId,
            label = normalizedLabel,
            triggerAtEpochMillis = triggerAtEpochMillis,
            deliveryMode = deliveryMode,
            scheduledZoneId = zoneId,
            createdAtEpochMillis = now
        )
        store.setPendingTrigger(persisted)
        refreshPermissionAndTimezoneState()
        return persisted
    }

    override fun scheduleDemoTriggerAfterSeconds(
        delaySeconds: Long,
        label: String
    ): PersistedTrigger {
        val boundedSeconds = delaySeconds.coerceAtLeast(1L)
        val now = clockProvider.nowEpochMillis()
        val targetMillis = now + (boundedSeconds * 1_000L)
        return scheduleTrigger(
            triggerId = "demo_${now}_${boundedSeconds}s",
            label = label,
            triggerAtEpochMillis = targetMillis
        )
    }

    override fun cancelPendingTrigger() {
        alarmGateway.cancelPendingAlarm()
        store.setPendingTrigger(null)
        refreshPermissionAndTimezoneState()
    }

    override fun onAlarmBroadcastReceived(triggerId: String?, label: String?) {
        val persisted = store.getPendingTrigger()
        val normalizedIncomingId = triggerId?.trim()?.takeIf { it.isNotEmpty() }

        // Ignore stale, duplicate, or mismatched alarm broadcasts when no matching persisted
        // pending trigger exists.
        if (persisted == null || normalizedIncomingId == null || normalizedIncomingId != persisted.triggerId) {
            refreshPermissionAndTimezoneState()
            return
        }

        val now = clockProvider.nowEpochMillis()
        store.setPendingTrigger(null)
        val fired = FiredTriggerRecord(
            triggerId = persisted.triggerId,
            label = persisted.label,
            scheduledAtEpochMillis = persisted.triggerAtEpochMillis,
            firedAtEpochMillis = now,
            triggerSource = "ALARM_BROADCAST"
        )
        store.setLastFiredTrigger(fired)

        // If Slice 2 registered a NextTriggerProvider, compute and schedule the subsequent trigger.
        scheduleFromProviderIfPresent()

        refreshPermissionAndTimezoneState()
        onTriggerFired(fired)
    }

    override fun reconcile(reason: ReconciliationReason): ReconciliationRecord {
        val now = clockProvider.nowEpochMillis()
        val zone = clockProvider.currentZoneId()
        val zoneId = zone.id

        // Allow Slice 2's provider (when attached) to supply/override the next trigger candidate.
        val providerCandidate = nextTriggerProvider?.computeNextTrigger(now, zone)
        if (providerCandidate != null) {
            val currentPending = store.getPendingTrigger()
            if (currentPending == null ||
                currentPending.triggerId != providerCandidate.triggerId ||
                currentPending.triggerAtEpochMillis != providerCandidate.triggerAtEpochMillis
            ) {
                store.setPendingTrigger(
                    PersistedTrigger(
                        triggerId = providerCandidate.triggerId,
                        label = providerCandidate.label,
                        triggerAtEpochMillis = providerCandidate.triggerAtEpochMillis,
                        deliveryMode = currentDeliveryModeForPermission(),
                        scheduledZoneId = zoneId,
                        createdAtEpochMillis = now
                    )
                )
            }
        }

        val pending = store.getPendingTrigger()
        if (pending == null) {
            val record = ReconciliationRecord(
                reason = reason,
                outcome = ReconciliationOutcome.NO_PENDING_TRIGGER,
                timestampEpochMillis = now,
                zoneId = zoneId,
                deliveryModeUsed = ScheduleDeliveryMode.NONE
            )
            store.setLastReconciliation(record)
            refreshPermissionAndTimezoneState()
            return record
        }

        if (pending.triggerAtEpochMillis <= now) {
            alarmGateway.cancelPendingAlarm()
            store.setPendingTrigger(null)
            val fired = FiredTriggerRecord(
                triggerId = pending.triggerId,
                label = pending.label,
                scheduledAtEpochMillis = pending.triggerAtEpochMillis,
                firedAtEpochMillis = now,
                triggerSource = "RECONCILIATION_${reason.name}"
            )
            store.setLastFiredTrigger(fired)

            val record = ReconciliationRecord(
                reason = reason,
                outcome = ReconciliationOutcome.FIRED_OVERDUE_TRIGGER,
                timestampEpochMillis = now,
                zoneId = zoneId,
                deliveryModeUsed = ScheduleDeliveryMode.IMMEDIATE_OVERDUE_EXECUTION
            )
            store.setLastReconciliation(record)
            scheduleFromProviderIfPresent()
            refreshPermissionAndTimezoneState()
            onTriggerFired(fired)
            return record
        }

        // Pending trigger is still in the future: re-register with AlarmManager using current permission
        // and update local timezone metadata.
        val deliveryMode = registerWithAlarmGateway(
            triggerId = pending.triggerId,
            label = pending.label,
            triggerAtEpochMillis = pending.triggerAtEpochMillis
        )
        val updatedPending = pending.copy(
            deliveryMode = deliveryMode,
            scheduledZoneId = zoneId
        )
        store.setPendingTrigger(updatedPending)

        val record = ReconciliationRecord(
            reason = reason,
            outcome = ReconciliationOutcome.RESCHEDULED_FUTURE_TRIGGER,
            timestampEpochMillis = now,
            zoneId = zoneId,
            deliveryModeUsed = deliveryMode
        )
        store.setLastReconciliation(record)
        refreshPermissionAndTimezoneState()
        return record
    }

    override fun refreshPermissionAndTimezoneState(): SchedulingState {
        val next = buildCurrentState()
        _state.value = next
        return next
    }

    override fun setNextTriggerProvider(provider: NextTriggerProvider?) {
        nextTriggerProvider = provider
    }

    private fun scheduleFromProviderIfPresent() {
        val provider = nextTriggerProvider ?: return
        val now = clockProvider.nowEpochMillis()
        val zone = clockProvider.currentZoneId()
        val candidate = provider.computeNextTrigger(now, zone) ?: return
        if (candidate.triggerAtEpochMillis > now) {
            val mode = registerWithAlarmGateway(
                triggerId = candidate.triggerId,
                label = candidate.label,
                triggerAtEpochMillis = candidate.triggerAtEpochMillis
            )
            store.setPendingTrigger(
                PersistedTrigger(
                    triggerId = candidate.triggerId,
                    label = candidate.label,
                    triggerAtEpochMillis = candidate.triggerAtEpochMillis,
                    deliveryMode = mode,
                    scheduledZoneId = zone.id,
                    createdAtEpochMillis = now
                )
            )
        }
    }

    private fun registerWithAlarmGateway(
        triggerId: String,
        label: String,
        triggerAtEpochMillis: Long
    ): ScheduleDeliveryMode {
        return if (alarmGateway.canScheduleExactAlarms()) {
            val scheduledExact = alarmGateway.setExactAndAllowWhileIdle(
                triggerAtEpochMillis = triggerAtEpochMillis,
                triggerId = triggerId,
                label = label
            )
            if (scheduledExact) {
                ScheduleDeliveryMode.EXACT_ALLOW_WHILE_IDLE
            } else {
                ScheduleDeliveryMode.INEXACT_FALLBACK_ALLOW_WHILE_IDLE
            }
        } else {
            alarmGateway.setAndAllowWhileIdle(
                triggerAtEpochMillis = triggerAtEpochMillis,
                triggerId = triggerId,
                label = label
            )
            ScheduleDeliveryMode.INEXACT_FALLBACK_ALLOW_WHILE_IDLE
        }
    }

    private fun currentDeliveryModeForPermission(): ScheduleDeliveryMode =
        if (alarmGateway.canScheduleExactAlarms()) {
            ScheduleDeliveryMode.EXACT_ALLOW_WHILE_IDLE
        } else {
            ScheduleDeliveryMode.INEXACT_FALLBACK_ALLOW_WHILE_IDLE
        }

    private fun buildCurrentState(): SchedulingState {
        val canExact = alarmGateway.canScheduleExactAlarms()
        val pending = store.getPendingTrigger()
        // Permission can still read granted while the gateway has already fallen back after
        // SecurityException. Present the actual registered mode, not just the permission bit.
        val readiness = if (canExact && pending?.deliveryMode != ScheduleDeliveryMode.INEXACT_FALLBACK_ALLOW_WHILE_IDLE) {
            ScheduleReadiness.READY_EXACT
        } else {
            ScheduleReadiness.DEGRADED_INEXACT_FALLBACK
        }
        return SchedulingState(
            exactAlarmPermissionGranted = canExact,
            readiness = readiness,
            currentZoneId = clockProvider.currentZoneId().id,
            pendingTrigger = pending,
            lastFiredTrigger = store.getLastFiredTrigger(),
            lastReconciliation = store.getLastReconciliation()
        )
    }
}
