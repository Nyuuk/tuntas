package com.tuntas.app.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.tuntas.app.domain.*
import org.junit.Assert.*
import org.junit.Test
import java.time.*

class TaskPresentationTest {
    private val start = LocalDateTime.of(2026, 11, 9, 9, 0)
    private fun task(rule: Recurrence = Recurrence.Once) = Task("owned", "Belajar", start.toLocalDate(), start.toLocalTime(), rule)

    @Test fun pendingBadgeWinsEvenWhenPreviousOccurrenceWasCompleted() {
        val row = TaskState(task(Recurrence.Daily), pending = Occurrence(start, start.toInstant(ZoneOffset.UTC)), completedThrough = start.minusDays(1))
        assertEquals("Tertunda", taskStatusLabel(row))
    }
    @Test fun completedOnceIsNotPresentedAsScheduled() {
        assertEquals("Selesai", taskStatusLabel(TaskState(task(), completedThrough = start)))
    }
    @Test fun completedRecurringTaskRemainsScheduled() {
        assertEquals("Terjadwal", taskStatusLabel(TaskState(task(Recurrence.Daily), completedThrough = start)))
        assertEquals("Terjadwal", taskStatusLabel(TaskState(task())))
    }
    @Test fun weekdayLabelsAreIndonesianAndCalendarOrdered() {
        assertEquals("Senin, Jumat, Minggu", recurrenceLabel(Recurrence.Weekly(setOf(DayOfWeek.SUNDAY, DayOfWeek.FRIDAY, DayOfWeek.MONDAY))))
    }
    @Test fun monthlyLabelRetainsAbsentDateExplanation() {
        assertEquals("Tanggal 31 setiap bulan (bulan tanpa tanggal tersebut dilewati)", recurrenceLabel(Recurrence.Monthly(31)))
        assertEquals("Sekali", recurrenceLabel(Recurrence.Once))
        assertEquals("Setiap hari", recurrenceLabel(Recurrence.Daily))
    }
    @Test fun darkPaletteTextPairsMeetNormalTextContrast() {
        val colors = TuntasDarkColors
        val pairs = listOf(
            colors.onBackground to colors.background, colors.onSurface to colors.surface,
            colors.onSurfaceVariant to colors.surface, colors.onSurfaceVariant to colors.surfaceVariant,
            colors.onPrimary to colors.primary, colors.onPrimaryContainer to colors.primaryContainer,
            colors.onSecondaryContainer to colors.secondaryContainer,
            colors.error to colors.background, colors.onErrorContainer to colors.errorContainer
        )
        pairs.forEach { (text, background) ->
            assertTrue("Contrast below 4.5:1: $text / $background", contrast(text, background) >= 4.5)
        }
        assertTrue("Default background must be dark", colors.background.luminance() < 0.02)
    }
    private fun contrast(a: Color, b: Color): Double {
        val x = a.luminance().toDouble()
        val y = b.luminance().toDouble()
        return (maxOf(x, y) + 0.05) / (minOf(x, y) + 0.05)
    }
}
