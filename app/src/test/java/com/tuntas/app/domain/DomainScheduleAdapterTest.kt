package com.tuntas.app.domain

import com.tuntas.app.platform.blocker.*
import com.tuntas.app.platform.scheduling.*
import java.nio.file.Files
import java.time.*
import org.junit.Assert.*
import org.junit.Test

class DomainScheduleAdapterTest {
    private class Gateway : PlatformAlarmGateway {
        var exact = true
        override fun canScheduleExactAlarms() = exact
        override fun setExactAndAllowWhileIdle(triggerAtEpochMillis: Long, triggerId: String, label: String) = true
        override fun setAndAllowWhileIdle(triggerAtEpochMillis: Long, triggerId: String, label: String) {}
        override fun cancelPendingAlarm() {}
    }
    @Test fun bootTimeTimezonePermissionAndAlarmReconcileDurableDomainBeforeScheduling() {
        val time = TaskEngineTest.Time(Instant.parse("2024-01-01T00:00:00Z"))
        val engine = TaskEngine(FileTaskRepository(domainTempDirectory("adapter").resolve("tasks").toFile()), time)
        val gateway = Gateway()
        val blocker = DefaultBlockerController("com.tuntas.app", InMemoryBlockerStore(), object : BlockerEnvironmentProvider {
            override fun isAccessibilityServiceEnabled() = false
            override fun resolveDynamicSystemSafePackages() = emptySet<String>()
            override fun nowEpochMillis() = time.nowEpochMillis()
        })
        lateinit var adapter: DomainScheduleAdapter
        val coordinator = DefaultScheduleCoordinator(InMemoryTriggerStore(), gateway, time) { adapter.onAlarmFired() }
        adapter = DomainScheduleAdapter(engine, coordinator, blocker)
        engine.saveTask(Task("a", "Task", LocalDate.of(2024, 1, 1), LocalTime.of(9, 0), Recurrence.Daily))
        engine.setAllowlist(setOf("org.example.phone"))
        for (reason in ReconciliationReason.values()) {
            adapter.reconcile(reason)
            assertEquals(Instant.parse("2024-01-01T09:00:00Z").toEpochMilli(), coordinator.state.value.pendingTrigger!!.triggerAtEpochMillis)
        }
        gateway.exact = false
        time.zone = ZoneId.of("Asia/Jakarta")
        adapter.reconcile(ReconciliationReason.EXACT_ALARM_PERMISSION_CHANGED)
        assertTrue(coordinator.state.value.isDegraded)
        assertEquals(Instant.parse("2024-01-01T02:00:00Z").toEpochMilli(), coordinator.state.value.pendingTrigger!!.triggerAtEpochMillis)
        val trigger = coordinator.state.value.pendingTrigger!!
        time.now = Instant.parse("2024-01-01T02:00:00Z")
        coordinator.onAlarmBroadcastReceived(trigger.triggerId, trigger.label)
        assertTrue(engine.snapshot().blocking)
        assertTrue(blocker.state.value.lockActive)
        assertTrue(blocker.state.value.isDegraded)
        assertEquals(setOf("org.example.phone"), blocker.state.value.userAllowlistedPackages)
        assertEquals(Instant.parse("2024-01-02T02:00:00Z").toEpochMilli(), coordinator.state.value.pendingTrigger!!.triggerAtEpochMillis)
        val pending = engine.snapshot().tasks.getValue("a").pending!!
        assertFalse(adapter.complete("a", pending, false))
        assertTrue(blocker.state.value.lockActive)
        assertTrue(adapter.complete("a", pending, true))
        assertFalse(blocker.state.value.lockActive)
        engine.deleteTask("a")
        adapter.reconcile(ReconciliationReason.MANUAL_DIAGNOSTIC)
        assertNull(coordinator.state.value.pendingTrigger)
        coordinator.onAlarmBroadcastReceived(trigger.triggerId, trigger.label)
        assertFalse(blocker.state.value.lockActive)
    }
}
