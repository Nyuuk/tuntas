package com.tuntas.app.platform.scheduling

import android.content.SharedPreferences

/**
 * SharedPreferences-backed implementation of [TriggerStore] so the next scheduled trigger
 * and reconciliation state survive process death and device reboot.
 */
class SharedPreferencesTriggerStore(
    private val prefs: SharedPreferences
) : TriggerStore {

    override fun getPendingTrigger(): PersistedTrigger? {
        val triggerId = prefs.getString(KEY_PENDING_ID, null) ?: return null
        val label = prefs.getString(KEY_PENDING_LABEL, null) ?: "Scheduled Lock"
        val triggerAt = prefs.getLong(KEY_PENDING_AT, 0L)
        val modeName = prefs.getString(KEY_PENDING_MODE, null)
        val mode = modeName?.let {
            runCatching { ScheduleDeliveryMode.valueOf(it) }.getOrNull()
        } ?: ScheduleDeliveryMode.EXACT_ALLOW_WHILE_IDLE
        val zoneId = prefs.getString(KEY_PENDING_ZONE, "UTC") ?: "UTC"
        val createdAt = prefs.getLong(KEY_PENDING_CREATED_AT, triggerAt)

        return PersistedTrigger(
            triggerId = triggerId,
            label = label,
            triggerAtEpochMillis = triggerAt,
            deliveryMode = mode,
            scheduledZoneId = zoneId,
            createdAtEpochMillis = createdAt
        )
    }

    override fun setPendingTrigger(trigger: PersistedTrigger?) {
        prefs.edit().apply {
            if (trigger == null) {
                remove(KEY_PENDING_ID)
                remove(KEY_PENDING_LABEL)
                remove(KEY_PENDING_AT)
                remove(KEY_PENDING_MODE)
                remove(KEY_PENDING_ZONE)
                remove(KEY_PENDING_CREATED_AT)
            } else {
                putString(KEY_PENDING_ID, trigger.triggerId)
                putString(KEY_PENDING_LABEL, trigger.label)
                putLong(KEY_PENDING_AT, trigger.triggerAtEpochMillis)
                putString(KEY_PENDING_MODE, trigger.deliveryMode.name)
                putString(KEY_PENDING_ZONE, trigger.scheduledZoneId)
                putLong(KEY_PENDING_CREATED_AT, trigger.createdAtEpochMillis)
            }
        }.apply()
    }

    override fun getLastFiredTrigger(): FiredTriggerRecord? {
        val triggerId = prefs.getString(KEY_FIRED_ID, null) ?: return null
        val label = prefs.getString(KEY_FIRED_LABEL, null) ?: "Scheduled Lock"
        val scheduledAt = prefs.getLong(KEY_FIRED_SCHEDULED_AT, 0L)
        val firedAt = prefs.getLong(KEY_FIRED_AT, 0L)
        val source = prefs.getString(KEY_FIRED_SOURCE, "ALARM_BROADCAST") ?: "ALARM_BROADCAST"
        return FiredTriggerRecord(
            triggerId = triggerId,
            label = label,
            scheduledAtEpochMillis = scheduledAt,
            firedAtEpochMillis = firedAt,
            triggerSource = source
        )
    }

    override fun setLastFiredTrigger(record: FiredTriggerRecord) {
        prefs.edit()
            .putString(KEY_FIRED_ID, record.triggerId)
            .putString(KEY_FIRED_LABEL, record.label)
            .putLong(KEY_FIRED_SCHEDULED_AT, record.scheduledAtEpochMillis)
            .putLong(KEY_FIRED_AT, record.firedAtEpochMillis)
            .putString(KEY_FIRED_SOURCE, record.triggerSource)
            .apply()
    }

    override fun getLastReconciliation(): ReconciliationRecord? {
        val reasonName = prefs.getString(KEY_RECON_REASON, null) ?: return null
        val outcomeName = prefs.getString(KEY_RECON_OUTCOME, null) ?: return null
        val reason = runCatching { ReconciliationReason.valueOf(reasonName) }.getOrNull() ?: return null
        val outcome = runCatching { ReconciliationOutcome.valueOf(outcomeName) }.getOrNull() ?: return null
        val timestamp = prefs.getLong(KEY_RECON_AT, 0L)
        val zoneId = prefs.getString(KEY_RECON_ZONE, "UTC") ?: "UTC"
        val modeName = prefs.getString(KEY_RECON_MODE, null)
        val mode = modeName?.let {
            runCatching { ScheduleDeliveryMode.valueOf(it) }.getOrNull()
        } ?: ScheduleDeliveryMode.NONE
        return ReconciliationRecord(
            reason = reason,
            outcome = outcome,
            timestampEpochMillis = timestamp,
            zoneId = zoneId,
            deliveryModeUsed = mode
        )
    }

    override fun setLastReconciliation(record: ReconciliationRecord) {
        prefs.edit()
            .putString(KEY_RECON_REASON, record.reason.name)
            .putString(KEY_RECON_OUTCOME, record.outcome.name)
            .putLong(KEY_RECON_AT, record.timestampEpochMillis)
            .putString(KEY_RECON_ZONE, record.zoneId)
            .putString(KEY_RECON_MODE, record.deliveryModeUsed.name)
            .apply()
    }

    companion object {
        private const val KEY_PENDING_ID = "sched_pending_id"
        private const val KEY_PENDING_LABEL = "sched_pending_label"
        private const val KEY_PENDING_AT = "sched_pending_at"
        private const val KEY_PENDING_MODE = "sched_pending_mode"
        private const val KEY_PENDING_ZONE = "sched_pending_zone"
        private const val KEY_PENDING_CREATED_AT = "sched_pending_created_at"

        private const val KEY_FIRED_ID = "sched_fired_id"
        private const val KEY_FIRED_LABEL = "sched_fired_label"
        private const val KEY_FIRED_SCHEDULED_AT = "sched_fired_scheduled_at"
        private const val KEY_FIRED_AT = "sched_fired_at"
        private const val KEY_FIRED_SOURCE = "sched_fired_source"

        private const val KEY_RECON_REASON = "sched_recon_reason"
        private const val KEY_RECON_OUTCOME = "sched_recon_outcome"
        private const val KEY_RECON_AT = "sched_recon_at"
        private const val KEY_RECON_ZONE = "sched_recon_zone"
        private const val KEY_RECON_MODE = "sched_recon_mode"
    }
}

/**
 * In-memory implementation of [TriggerStore] for fast deterministic unit tests.
 */
class InMemoryTriggerStore(
    initialPending: PersistedTrigger? = null
) : TriggerStore {
    private var pendingTrigger: PersistedTrigger? = initialPending
    private var lastFiredTrigger: FiredTriggerRecord? = null
    private var lastReconciliation: ReconciliationRecord? = null

    override fun getPendingTrigger(): PersistedTrigger? = pendingTrigger

    override fun setPendingTrigger(trigger: PersistedTrigger?) {
        pendingTrigger = trigger
    }

    override fun getLastFiredTrigger(): FiredTriggerRecord? = lastFiredTrigger

    override fun setLastFiredTrigger(record: FiredTriggerRecord) {
        lastFiredTrigger = record
    }

    override fun getLastReconciliation(): ReconciliationRecord? = lastReconciliation

    override fun setLastReconciliation(record: ReconciliationRecord) {
        lastReconciliation = record
    }
}
