package com.tuntas.app.domain

import java.time.*
import org.junit.Assert.*
import org.junit.Test

class RecurrenceTest {
    private fun task(rule: Recurrence = Recurrence.Daily, lead: Int? = null) = Task("a", "Read", LocalDate.of(2024, 1, 1), LocalTime.of(0, 15), rule, lead)

    @Test fun requiredTitleAndNonnegativeLead() {
        assertThrows(IllegalArgumentException::class.java) { task().copy(title = "  ") }
        assertThrows(IllegalArgumentException::class.java) { task(lead = -1) }
        assertThrows(IllegalArgumentException::class.java) { Recurrence.Weekly(emptySet()) }
        assertThrows(IllegalArgumentException::class.java) { Recurrence.Monthly(32) }
        assertThrows(IllegalArgumentException::class.java) { Recurrence.Monthly(0) }
    }
    @Test fun leadCrossesMidnight() {
        val occurrence = Recurrences.next(task(lead = 30), null, ZoneOffset.UTC)!!
        assertEquals(Instant.parse("2023-12-31T23:45:00Z"), occurrence.lockAt)
    }
    @Test fun dueBoundaryIncludesLeadAndNeverStartsBeforeFirstDate() {
        val t = task(lead = 30)
        assertNull(Recurrences.latestDue(t, Instant.parse("2023-12-31T23:44:59Z"), ZoneOffset.UTC))
        assertEquals(t.firstDate.atTime(t.startTime), Recurrences.latestDue(t, Instant.parse("2023-12-31T23:45:00Z"), ZoneOffset.UTC)!!.localStart)
        assertEquals(LocalDate.of(2034, 1, 1), Recurrences.latestDue(t, Instant.parse("2034-01-01T10:00:00Z"), ZoneOffset.UTC)!!.localStart.toLocalDate())
    }
    @Test fun nonLeapFebruarySkipsDay29() {
        val t = task(Recurrence.Monthly(29))
        assertEquals(LocalDate.of(2025, 3, 29), Recurrences.next(t, LocalDateTime.of(2025, 1, 29, 0, 15), ZoneOffset.UTC)!!.localStart.toLocalDate())
    }
    @Test fun onceDoesNotRepeat() {
        val t = task(Recurrence.Once)
        assertNull(Recurrences.next(t, t.firstDate.atTime(t.startTime), ZoneOffset.UTC))
    }
    @Test fun selectedWeekdaysCrossYear() {
        val t = task(Recurrence.Weekly(setOf(DayOfWeek.MONDAY))).copy(firstDate = LocalDate.of(2024, 12, 31))
        assertEquals(LocalDate.of(2025, 1, 6), Recurrences.next(t, null, ZoneOffset.UTC)!!.localStart.toLocalDate())
    }
    @Test fun monthlySkipsAbsentDayAndIncludesLeapDay() {
        val t = task(Recurrence.Monthly(31))
        assertEquals(LocalDate.of(2024, 3, 31), Recurrences.next(t, LocalDateTime.of(2024, 1, 31, 0, 15), ZoneOffset.UTC)!!.localStart.toLocalDate())
        assertEquals(LocalDate.of(2024, 2, 29), Recurrences.next(task(Recurrence.Monthly(29)), LocalDateTime.of(2024, 1, 29, 0, 15), ZoneOffset.UTC)!!.localStart.toLocalDate())
    }
    @Test fun dstGapShiftsForwardAndOverlapUsesEarlierOffset() {
        val zone = ZoneId.of("America/New_York")
        val t = task().copy(firstDate = LocalDate.of(2024, 3, 10), startTime = LocalTime.of(2, 30))
        assertEquals(Instant.parse("2024-03-10T07:30:00Z"), Recurrences.next(t, null, zone)!!.lockAt)
        assertEquals(Instant.parse("2024-11-03T05:30:00Z"), Recurrences.next(t.copy(firstDate = LocalDate.of(2024, 11, 3), startTime = LocalTime.of(1, 30)), null, zone)!!.lockAt)
    }
}
