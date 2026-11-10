package com.tuntas.app.platform.scheduling

import android.app.AlarmManager
import android.content.Intent
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScheduleCoordinatorTest {

    private class FakeClockProvider(
        var currentMillis: Long = 1_700_000_000_000L,
        var zone: ZoneId = ZoneId.of("Asia/Jakarta")
    ) : ClockProvider {
        override fun nowEpochMillis(): Long = currentMillis
        override fun currentZoneId(): ZoneId = zone
    }

    private class FakePlatformAlarmGateway(
        var canExact: Boolean = true,
        var throwSecurityExceptionOnExact: Boolean = false
    ) : PlatformAlarmGateway {
        var exactScheduledCount: Int = 0
        var inexactFallbackScheduledCount: Int = 0
        var cancelCount: Int = 0
        var lastScheduledTriggerAt: Long? = null
        var lastScheduledTriggerId: String? = null

        override fun canScheduleExactAlarms(): Boolean = canExact

        override fun setExactAndAllowWhileIdle(
            triggerAtEpochMillis: Long,
            triggerId: String,
            label: String
        ): Boolean {
            lastScheduledTriggerAt = triggerAtEpochMillis
            lastScheduledTriggerId = triggerId
            return if (throwSecurityExceptionOnExact) {
                inexactFallbackScheduledCount += 1
                false
            } else {
                exactScheduledCount += 1
                true
            }
        }

        override fun setAndAllowWhileIdle(
            triggerAtEpochMillis: Long,
            triggerId: String,
            label: String
        ) {
            inexactFallbackScheduledCount += 1
            lastScheduledTriggerAt = triggerAtEpochMillis
            lastScheduledTriggerId = triggerId
        }

        override fun cancelPendingAlarm() {
            cancelCount += 1
            lastScheduledTriggerAt = null
            lastScheduledTriggerId = null
        }
    }

    @Test
    fun scheduleTrigger_whenExactAlarmPermissionGranted_usesExactAllowWhileIdle() {
        val store = InMemoryTriggerStore()
        val gateway = FakePlatformAlarmGateway(canExact = true)
        val clock = FakeClockProvider()
        val firedRecords = mutableListOf<FiredTriggerRecord>()

        val coordinator = DefaultScheduleCoordinator(
            store = store,
            alarmGateway = gateway,
            clockProvider = clock,
            onTriggerFired = { firedRecords.add(it) }
        )

        val targetTime = clock.currentMillis + 30_000L
        val persisted = coordinator.scheduleTrigger(
            triggerId = "demo_30s",
            label = "Demo 30s",
            triggerAtEpochMillis = targetTime
        )

        assertEquals(ScheduleDeliveryMode.EXACT_ALLOW_WHILE_IDLE, persisted.deliveryMode)
        assertEquals(1, gateway.exactScheduledCount)
        assertEquals(0, gateway.inexactFallbackScheduledCount)
        assertEquals(targetTime, gateway.lastScheduledTriggerAt)
        assertEquals(ScheduleReadiness.READY_EXACT, coordinator.state.value.readiness)
        assertFalse(coordinator.state.value.isDegraded)
        assertTrue(firedRecords.isEmpty())
    }

    @Test
    fun scheduleTrigger_whenExactAlarmPermissionDenied_fallsBackHonestlyToInexactAndReportsDegraded() {
        val store = InMemoryTriggerStore()
        val gateway = FakePlatformAlarmGateway(canExact = false)
        val clock = FakeClockProvider()

        val coordinator = DefaultScheduleCoordinator(
            store = store,
            alarmGateway = gateway,
            clockProvider = clock,
            onTriggerFired = {}
        )

        val targetTime = clock.currentMillis + 60_000L
        val persisted = coordinator.scheduleTrigger(
            triggerId = "fallback_60s",
            label = "Fallback 60s",
            triggerAtEpochMillis = targetTime
        )

        assertEquals(ScheduleDeliveryMode.INEXACT_FALLBACK_ALLOW_WHILE_IDLE, persisted.deliveryMode)
        assertEquals(0, gateway.exactScheduledCount)
        assertEquals(1, gateway.inexactFallbackScheduledCount)
        assertEquals(
            ScheduleReadiness.DEGRADED_INEXACT_FALLBACK,
            coordinator.state.value.readiness
        )
        assertTrue(coordinator.state.value.isDegraded)
    }

    @Test
    fun scheduleTrigger_whenExactAlarmThrowsSecurityException_fallsBackToInexactHonestly() {
        val store = InMemoryTriggerStore()
        val gateway = FakePlatformAlarmGateway(
            canExact = true,
            throwSecurityExceptionOnExact = true
        )
        val clock = FakeClockProvider()

        val coordinator = DefaultScheduleCoordinator(
            store = store,
            alarmGateway = gateway,
            clockProvider = clock,
            onTriggerFired = {}
        )

        val persisted = coordinator.scheduleDemoTriggerAfterSeconds(10L)
        assertEquals(ScheduleDeliveryMode.INEXACT_FALLBACK_ALLOW_WHILE_IDLE, persisted.deliveryMode)
        assertEquals(1, gateway.inexactFallbackScheduledCount)
        assertTrue(coordinator.state.value.isDegraded)
        assertEquals(ScheduleReadiness.DEGRADED_INEXACT_FALLBACK, coordinator.state.value.readiness)
    }

    @Test
    fun onAlarmBroadcastReceived_clearsPendingTrigger_andActivatesBlockerLock() {
        val store = InMemoryTriggerStore()
        val gateway = FakePlatformAlarmGateway(canExact = true)
        val clock = FakeClockProvider()
        val firedRecords = mutableListOf<FiredTriggerRecord>()

        val coordinator = DefaultScheduleCoordinator(
            store = store,
            alarmGateway = gateway,
            clockProvider = clock,
            onTriggerFired = { firedRecords.add(it) }
        )

        val persisted = coordinator.scheduleDemoTriggerAfterSeconds(15L, "Focus Task Lock")
        assertNotNull(coordinator.state.value.pendingTrigger)

        clock.currentMillis += 15_000L
        coordinator.onAlarmBroadcastReceived(
            triggerId = persisted.triggerId,
            label = persisted.label
        )

        assertNull(coordinator.state.value.pendingTrigger)
        assertEquals(1, firedRecords.size)
        assertEquals("Focus Task Lock", firedRecords.first().label)
        assertEquals("ALARM_BROADCAST", firedRecords.first().triggerSource)
    }

    @Test
    fun onAlarmBroadcastReceived_ignoresStaleDuplicateOrMismatchedBroadcasts() {
        val store = InMemoryTriggerStore()
        val gateway = FakePlatformAlarmGateway(canExact = true)
        val clock = FakeClockProvider()
        val firedRecords = mutableListOf<FiredTriggerRecord>()

        val coordinator = DefaultScheduleCoordinator(
            store = store,
            alarmGateway = gateway,
            clockProvider = clock,
            onTriggerFired = { firedRecords.add(it) }
        )

        // 1. Broadcast arrives when no pending trigger is persisted -> ignored
        coordinator.onAlarmBroadcastReceived(triggerId = "stale_unpersisted_id", label = "Stale")
        assertTrue(firedRecords.isEmpty())
        assertNull(coordinator.state.value.lastFiredTrigger)

        // 2. Schedule a valid trigger, then receive a mismatched triggerId broadcast -> ignored, pending preserved
        val activePending = coordinator.scheduleDemoTriggerAfterSeconds(20L, "Active Task")
        coordinator.onAlarmBroadcastReceived(triggerId = "wrong_trigger_id", label = "Wrong")
        coordinator.onAlarmBroadcastReceived(triggerId = null, label = "NullId")
        coordinator.onAlarmBroadcastReceived(triggerId = "   ", label = "BlankId")
        assertTrue(firedRecords.isEmpty())
        assertEquals(activePending.triggerId, coordinator.state.value.pendingTrigger?.triggerId)

        // 3. Cancel the pending trigger, then receive the delayed broadcast for the cancelled trigger -> ignored
        coordinator.cancelPendingTrigger()
        assertNull(coordinator.state.value.pendingTrigger)
        coordinator.onAlarmBroadcastReceived(
            triggerId = activePending.triggerId,
            label = activePending.label
        )
        assertTrue(firedRecords.isEmpty())
        assertNull(coordinator.state.value.lastFiredTrigger)

        // 4. Schedule and fire a valid trigger, then receive a duplicate broadcast -> second is ignored
        val secondPending = coordinator.scheduleDemoTriggerAfterSeconds(10L, "Valid Task")
        clock.currentMillis += 10_000L
        coordinator.onAlarmBroadcastReceived(
            triggerId = secondPending.triggerId,
            label = null
        )
        assertEquals(1, firedRecords.size)
        assertEquals("Valid Task", firedRecords.single().label)

        // Duplicate delivery of the same alarm broadcast
        clock.currentMillis += 1_000L
        coordinator.onAlarmBroadcastReceived(
            triggerId = secondPending.triggerId,
            label = secondPending.label
        )
        assertEquals(1, firedRecords.size)
    }

    @Test
    fun reconcileOnBoot_whenPendingTriggerInFuture_reschedulesWithAlarmManager() {
        val initialTime = 1_700_000_000_000L
        val futureTarget = initialTime + 120_000L
        val store = InMemoryTriggerStore(
            initialPending = PersistedTrigger(
                triggerId = "persisted_boot_trigger",
                label = "Morning Focus",
                triggerAtEpochMillis = futureTarget,
                deliveryMode = ScheduleDeliveryMode.EXACT_ALLOW_WHILE_IDLE,
                scheduledZoneId = "Asia/Jakarta",
                createdAtEpochMillis = initialTime
            )
        )
        val gateway = FakePlatformAlarmGateway(canExact = true)
        val clock = FakeClockProvider(currentMillis = initialTime + 30_000L)
        val firedRecords = mutableListOf<FiredTriggerRecord>()

        val coordinator = DefaultScheduleCoordinator(
            store = store,
            alarmGateway = gateway,
            clockProvider = clock,
            onTriggerFired = { firedRecords.add(it) }
        )

        val recon = coordinator.reconcile(ReconciliationReason.BOOT_COMPLETED)
        assertEquals(ReconciliationOutcome.RESCHEDULED_FUTURE_TRIGGER, recon.outcome)
        assertEquals(ScheduleDeliveryMode.EXACT_ALLOW_WHILE_IDLE, recon.deliveryModeUsed)
        assertEquals(1, gateway.exactScheduledCount)
        assertEquals(futureTarget, gateway.lastScheduledTriggerAt)
        assertTrue(firedRecords.isEmpty())
    }

    @Test
    fun reconcileOnBootOrTimeChange_whenPendingTriggerOverdue_firesImmediatelyAndActivatesLock() {
        val initialTime = 1_700_000_000_000L
        val targetTime = initialTime + 60_000L
        val store = InMemoryTriggerStore(
            initialPending = PersistedTrigger(
                triggerId = "missed_while_off",
                label = "Overdue Lock",
                triggerAtEpochMillis = targetTime,
                deliveryMode = ScheduleDeliveryMode.EXACT_ALLOW_WHILE_IDLE,
                scheduledZoneId = "Asia/Jakarta",
                createdAtEpochMillis = initialTime
            )
        )
        val gateway = FakePlatformAlarmGateway(canExact = true)
        // Device boots 5 minutes after targetTime
        val clock = FakeClockProvider(currentMillis = targetTime + 300_000L)
        val firedRecords = mutableListOf<FiredTriggerRecord>()

        val coordinator = DefaultScheduleCoordinator(
            store = store,
            alarmGateway = gateway,
            clockProvider = clock,
            onTriggerFired = { firedRecords.add(it) }
        )

        val recon = coordinator.reconcile(ReconciliationReason.BOOT_COMPLETED)
        assertEquals(ReconciliationOutcome.FIRED_OVERDUE_TRIGGER, recon.outcome)
        assertEquals(ScheduleDeliveryMode.IMMEDIATE_OVERDUE_EXECUTION, recon.deliveryModeUsed)
        assertNull(coordinator.state.value.pendingTrigger)
        assertEquals(1, firedRecords.size)
        assertEquals("missed_while_off", firedRecords.first().triggerId)
        assertEquals("RECONCILIATION_BOOT_COMPLETED", firedRecords.first().triggerSource)
    }

    @Test
    fun reconcileOnTimezoneChange_updatesZoneIdAndReschedulesFutureTrigger() {
        val store = InMemoryTriggerStore()
        val gateway = FakePlatformAlarmGateway(canExact = true)
        val clock = FakeClockProvider(
            currentMillis = 1_700_000_000_000L,
            zone = ZoneId.of("Asia/Jakarta")
        )

        val coordinator = DefaultScheduleCoordinator(
            store = store,
            alarmGateway = gateway,
            clockProvider = clock,
            onTriggerFired = {}
        )

        coordinator.scheduleDemoTriggerAfterSeconds(300L, "Timezone Test")
        assertEquals("Asia/Jakarta", coordinator.state.value.pendingTrigger?.scheduledZoneId)

        // Device timezone changes to Asia/Tokyo
        clock.zone = ZoneId.of("Asia/Tokyo")
        val recon = coordinator.reconcile(ReconciliationReason.TIMEZONE_CHANGED)

        assertEquals(ReconciliationOutcome.RESCHEDULED_FUTURE_TRIGGER, recon.outcome)
        assertEquals("Asia/Tokyo", recon.zoneId)
        assertEquals("Asia/Tokyo", coordinator.state.value.currentZoneId)
        assertEquals("Asia/Tokyo", coordinator.state.value.pendingTrigger?.scheduledZoneId)
    }

    @Test
    fun reconcileOnExactAlarmPermissionChange_upgradesFallbackTriggerToExactWhenGranted() {
        val store = InMemoryTriggerStore()
        val gateway = FakePlatformAlarmGateway(canExact = false)
        val clock = FakeClockProvider()

        val coordinator = DefaultScheduleCoordinator(
            store = store,
            alarmGateway = gateway,
            clockProvider = clock,
            onTriggerFired = {}
        )

        val initial = coordinator.scheduleDemoTriggerAfterSeconds(120L, "Upgrade Test")
        assertEquals(ScheduleDeliveryMode.INEXACT_FALLBACK_ALLOW_WHILE_IDLE, initial.deliveryMode)
        assertEquals(ScheduleReadiness.DEGRADED_INEXACT_FALLBACK, coordinator.state.value.readiness)

        // User grants exact alarm permission in system settings
        gateway.canExact = true
        val recon = coordinator.reconcile(ReconciliationReason.EXACT_ALARM_PERMISSION_CHANGED)

        assertEquals(ReconciliationOutcome.RESCHEDULED_FUTURE_TRIGGER, recon.outcome)
        assertEquals(ScheduleDeliveryMode.EXACT_ALLOW_WHILE_IDLE, recon.deliveryModeUsed)
        assertEquals(ScheduleReadiness.READY_EXACT, coordinator.state.value.readiness)
        assertEquals(
            ScheduleDeliveryMode.EXACT_ALLOW_WHILE_IDLE,
            coordinator.state.value.pendingTrigger?.deliveryMode
        )
    }

    @Test
    fun reconcileAndBroadcastMapping_mapsAllSupportedPostUnlockSystemBroadcastActions() {
        assertEquals(
            ReconciliationReason.BOOT_COMPLETED,
            ScheduleReconciliationReceiver.mapActionToReason(Intent.ACTION_BOOT_COMPLETED)
        )
        // LOCKED_BOOT_COMPLETED must NOT be mapped because storage is credential-protected
        // and the app is not directBootAware.
        assertNull(
            ScheduleReconciliationReceiver.mapActionToReason(Intent.ACTION_LOCKED_BOOT_COMPLETED)
        )
        assertEquals(
            ReconciliationReason.PACKAGE_REPLACED,
            ScheduleReconciliationReceiver.mapActionToReason(Intent.ACTION_MY_PACKAGE_REPLACED)
        )
        assertEquals(
            ReconciliationReason.TIME_SET,
            ScheduleReconciliationReceiver.mapActionToReason(Intent.ACTION_TIME_CHANGED)
        )
        assertEquals(
            ReconciliationReason.TIMEZONE_CHANGED,
            ScheduleReconciliationReceiver.mapActionToReason(Intent.ACTION_TIMEZONE_CHANGED)
        )
        assertEquals(
            ReconciliationReason.EXACT_ALARM_PERMISSION_CHANGED,
            ScheduleReconciliationReceiver.mapActionToReason(
                AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED
            )
        )
        assertNull(ScheduleReconciliationReceiver.mapActionToReason("com.unknown.ACTION"))
    }

    @Test
    fun nextTriggerProviderHook_allowsFutureRecurrenceSliceToSupplyNextCandidate() {
        val store = InMemoryTriggerStore()
        val gateway = FakePlatformAlarmGateway(canExact = true)
        val clock = FakeClockProvider()

        val coordinator = DefaultScheduleCoordinator(
            store = store,
            alarmGateway = gateway,
            clockProvider = clock,
            onTriggerFired = {}
        )

        val expectedTarget = clock.currentMillis + 90_000L
        coordinator.setNextTriggerProvider { _, zone ->
            TriggerCandidate(
                triggerId = "slice2_task_occ_1",
                label = "Slice 2 Provided Trigger (${zone.id})",
                triggerAtEpochMillis = expectedTarget
            )
        }

        val recon = coordinator.reconcile(ReconciliationReason.APP_STARTUP)
        assertEquals(ReconciliationOutcome.RESCHEDULED_FUTURE_TRIGGER, recon.outcome)
        assertEquals("slice2_task_occ_1", coordinator.state.value.pendingTrigger?.triggerId)
        assertEquals(expectedTarget, coordinator.state.value.pendingTrigger?.triggerAtEpochMillis)
    }
}
