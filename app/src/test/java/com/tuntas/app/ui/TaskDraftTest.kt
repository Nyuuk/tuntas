package com.tuntas.app.ui

import com.tuntas.app.domain.Recurrence
import org.junit.Assert.*
import org.junit.Test
import java.time.DayOfWeek

class TaskDraftTest {
    @Test fun requiredTitleDateTimeAndNonnegativeLeadHaveIndonesianErrors() {
        val invalid = listOf(
            TaskDraft(title = " ", date = "2026-11-09", time = "09:00"),
            TaskDraft(title = "Belajar", date = "2026-02-30", time = "09:00"),
            TaskDraft(title = "Belajar", date = "2026-11-09", time = ""),
            TaskDraft(title = "Belajar", date = "2026-11-09", time = "25:00"),
            TaskDraft(title = "Belajar", date = "2026-11-09", time = "09:00", lead = "-1"),
            TaskDraft(title = "Belajar", date = "2026-11-09", time = "09:00", lead = "999999999999")
        )
        invalid.forEach { assertNotNull(it.validate("id").error); assertNull(it.validate("id").task) }
    }
    @Test fun onceDailyWeeklyMonthlyMapWithoutChangingDomainSemantics() {
        val base = TaskDraft(title = " Belajar ", date = "2026-11-09", time = "09:00")
        assertEquals(Recurrence.Once, base.validate("id").task!!.recurrence)
        assertNull(base.validate("id").task!!.leadMinutes)
        assertEquals("Belajar", base.validate("id").task!!.title)
        assertEquals(Recurrence.Daily, base.copy(kind = RepeatKind.DAILY).validate("id").task!!.recurrence)
        assertNotNull(base.copy(kind = RepeatKind.WEEKLY, weekdays = emptySet()).validate("id").error)
        assertEquals(Recurrence.Weekly(setOf(DayOfWeek.FRIDAY)), base.copy(kind = RepeatKind.WEEKLY, weekdays = setOf(DayOfWeek.FRIDAY)).validate("id").task!!.recurrence)
        assertNotNull(base.copy(kind = RepeatKind.MONTHLY, monthDay = "32").validate("id").error)
        val monthly = base.copy(kind = RepeatKind.MONTHLY, monthDay = "31", lead = "20").validate("id").task!!
        assertEquals(Recurrence.Monthly(31), monthly.recurrence)
        assertEquals(20, monthly.leadMinutes)
        assertEquals(monthly, TaskDraft.from(monthly).validate("id").task)
    }
}
