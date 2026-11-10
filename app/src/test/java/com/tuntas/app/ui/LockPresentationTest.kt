package com.tuntas.app.ui

import com.tuntas.app.domain.*
import org.junit.Assert.*
import org.junit.Test
import java.time.*

class LockPresentationTest {
    private val task = Task("a", "Fokus", LocalDate.of(2026, 11, 9), LocalTime.NOON, Recurrence.Daily)
    private val token = Occurrence(task.firstDate.atTime(task.startTime), Instant.parse("2026-11-09T12:00:00Z"))
    private fun locked() = TaskUiState(TaskSnapshot(mapOf("a" to TaskState(task, pending = token))), busy = false, loaded = true)

    @Test fun hydrationNeverRestoresEditableDestinations() {
        for (tab in listOf("Task", "Fokus", "Darurat", "Izin", "unknown")) {
            assertNull(normalDestination(TaskUiState(), tab, "restored-editor"))
        }
        assertEquals(PresentationMode.HYDRATING, presentationMode(TaskUiState(error = "disk failed")))
    }
    @Test fun pendingOverridesTabsEditorRestoreAndBackDestination() {
        for (tab in listOf("Task", "Fokus", "Darurat", "Izin")) {
            assertNull(normalDestination(locked(), tab, "open-editor"))
            assertNull(normalDestination(locked(), tab, null))
        }
        assertEquals(PresentationMode.LOCKED, presentationMode(locked()))
        assertEquals(PresentationMode.LOCKED, presentationMode(locked().copy(busy = true, error = "refused")))
    }
    @Test fun onlyLastConfirmedOccurrenceRestoresNormalNavigation() {
        val firstDone = locked().copy(snapshot = TaskSnapshot(mapOf("b" to TaskState(task.copy(id = "b"), pending = token))))
        assertEquals(PresentationMode.LOCKED, presentationMode(firstDone))
        val lastDone = firstDone.copy(snapshot = TaskSnapshot(mapOf("b" to TaskState(task.copy(id = "b"), completedThrough = token.localStart))))
        assertEquals(NormalDestination("Izin", null), normalDestination(lastDone, "Izin", null))
    }
    @Test fun readOnlyAppsNeverAddMandatoryPackagesOrChangeSelection() {
        val selected = setOf("safe.app", "uninstalled.app")
        val before = selected.toSet()
        val choices = lockAppOptions(listOf(AppOption("safe.app", "Teman"), AppOption("settings", "Settings"), AppOption("other", "Other")), selected)
        assertEquals(before, selected)
        assertEquals(selected, choices.map { it.app.packageName }.toSet())
        assertTrue(choices.single { it.app.packageName == "safe.app" }.launchable)
        assertEquals("Teman", choices.single { it.app.packageName == "safe.app" }.app.label)
        assertFalse(choices.single { it.app.packageName == "uninstalled.app" }.launchable)
    }
}
