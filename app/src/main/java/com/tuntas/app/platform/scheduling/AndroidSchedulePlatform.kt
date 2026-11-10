package com.tuntas.app.platform.scheduling

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.tuntas.app.TuntasApplication
import java.time.ZoneId

/**
 * System clock and timezone provider using `System.currentTimeMillis()` and `ZoneId.systemDefault()`.
 */
class SystemClockProvider : ClockProvider {
    override fun nowEpochMillis(): Long = System.currentTimeMillis()
    override fun currentZoneId(): ZoneId = ZoneId.systemDefault()
}

/**
 * Android framework implementation of [PlatformAlarmGateway] using [AlarmManager].
 *
 * Checks `AlarmManager.canScheduleExactAlarms()` on Android 12+ (API 31+).
 * Uses `setExactAndAllowWhileIdle(RTC_WAKEUP, ...)` when permitted, and falls back honestly
 * to `setAndAllowWhileIdle(RTC_WAKEUP, ...)` when denied or if a runtime SecurityException occurs.
 */
class AndroidPlatformAlarmGateway(
    private val context: Context
) : PlatformAlarmGateway {

    private val alarmManager: AlarmManager?
        get() = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager

    override fun canScheduleExactAlarms(): Boolean {
        val manager = alarmManager ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            manager.canScheduleExactAlarms()
        } else {
            true
        }
    }

    override fun setExactAndAllowWhileIdle(
        triggerAtEpochMillis: Long,
        triggerId: String,
        label: String
    ): Boolean {
        val manager = alarmManager ?: return false
        val pendingIntent = buildAlarmPendingIntent(
            triggerId = triggerId,
            label = label,
            flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        ) ?: return false

        return try {
            manager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                triggerAtEpochMillis,
                pendingIntent
            )
            Log.i(
                TAG,
                "Scheduled EXACT_ALLOW_WHILE_IDLE alarm id=$triggerId at=$triggerAtEpochMillis"
            )
            true
        } catch (se: SecurityException) {
            Log.w(
                TAG,
                "Exact alarm rejected by OS SecurityException; falling back to setAndAllowWhileIdle",
                se
            )
            manager.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                triggerAtEpochMillis,
                pendingIntent
            )
            false
        }
    }

    override fun setAndAllowWhileIdle(
        triggerAtEpochMillis: Long,
        triggerId: String,
        label: String
    ) {
        val manager = alarmManager ?: return
        val pendingIntent = buildAlarmPendingIntent(
            triggerId = triggerId,
            label = label,
            flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        ) ?: return

        manager.setAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            triggerAtEpochMillis,
            pendingIntent
        )
        Log.i(
            TAG,
            "Scheduled INEXACT_FALLBACK_ALLOW_WHILE_IDLE alarm id=$triggerId at=$triggerAtEpochMillis"
        )
    }

    override fun cancelPendingAlarm() {
        val manager = alarmManager ?: return
        val pendingIntent = buildAlarmPendingIntent(
            triggerId = "",
            label = "",
            flags = PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        if (pendingIntent != null) {
            manager.cancel(pendingIntent)
            pendingIntent.cancel()
            Log.i(TAG, "Cancelled pending alarm PendingIntent.")
        }
    }

    private fun buildAlarmPendingIntent(
        triggerId: String,
        label: String,
        flags: Int
    ): PendingIntent? {
        val intent = Intent(context, ScheduleAlarmReceiver::class.java).apply {
            action = ScheduleAlarmReceiver.ACTION_TRIGGER_LOCK_ALARM
            if (triggerId.isNotEmpty()) {
                putExtra(ScheduleAlarmReceiver.EXTRA_TRIGGER_ID, triggerId)
            }
        }
        return PendingIntent.getBroadcast(
            context,
            ALARM_REQUEST_CODE,
            intent,
            flags
        )
    }

    companion object {
        private const val TAG = "TuntasAlarmGateway"
        private const val ALARM_REQUEST_CODE = 1001
    }
}

/**
 * BroadcastReceiver that handles fired exact/inexact lock schedule alarms from [AlarmManager].
 *
 * Note: Task/trigger labels are never written to Android logcat.
 */
class ScheduleAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_TRIGGER_LOCK_ALARM) return
        val triggerId = intent.getStringExtra(EXTRA_TRIGGER_ID)
        val label = intent.getStringExtra(EXTRA_TRIGGER_LABEL)
        Log.i(
            TAG,
            "Received schedule alarm broadcast action=${intent.action} id=$triggerId"
        )
        val pending = goAsync()
        TuntasApplication.from(context).taskController.alarm(triggerId, label) { pending.finish() }
    }

    companion object {
        private const val TAG = "TuntasAlarmReceiver"
        const val ACTION_TRIGGER_LOCK_ALARM = "com.tuntas.app.action.TRIGGER_LOCK_ALARM"
        const val EXTRA_TRIGGER_ID = "extra_trigger_id"
        const val EXTRA_TRIGGER_LABEL = "extra_trigger_label"
    }
}

/**
 * BroadcastReceiver that reconciles persisted schedule triggers across:
 * - Post-unlock device boot (`ACTION_BOOT_COMPLETED` only; storage is credential-protected
 *   and the app is not `directBootAware`, so `LOCKED_BOOT_COMPLETED` is not supported)
 * - App update (`ACTION_MY_PACKAGE_REPLACED`)
 * - Manual/automatic clock change (`ACTION_TIME_CHANGED` / `android.intent.action.TIME_SET`)
 * - Timezone change (`ACTION_TIMEZONE_CHANGED`)
 * - Exact alarm permission grant/revoke (`AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED`)
 */
class ScheduleReconciliationReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        val reason = mapActionToReason(action) ?: return
        Log.i(TAG, "Reconciling schedule due to broadcast action=$action -> reason=$reason")
        val pending = goAsync()
        TuntasApplication.from(context).taskController.refresh(reason) { pending.finish() }
    }

    companion object {
        private const val TAG = "TuntasReconReceiver"

        /**
         * Pure helper mapping Android broadcast action strings to [ReconciliationReason].
         */
        fun mapActionToReason(action: String?): ReconciliationReason? = when (action) {
            Intent.ACTION_BOOT_COMPLETED -> ReconciliationReason.BOOT_COMPLETED
            Intent.ACTION_MY_PACKAGE_REPLACED -> ReconciliationReason.PACKAGE_REPLACED
            Intent.ACTION_TIME_CHANGED -> ReconciliationReason.TIME_SET
            Intent.ACTION_TIMEZONE_CHANGED -> ReconciliationReason.TIMEZONE_CHANGED
            AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED ->
                ReconciliationReason.EXACT_ALARM_PERMISSION_CHANGED
            else -> null
        }
    }
}
