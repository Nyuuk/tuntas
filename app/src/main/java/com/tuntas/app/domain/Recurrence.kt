package com.tuntas.app.domain

import java.time.*

sealed interface Recurrence {
    data object Once : Recurrence
    data object Daily : Recurrence
    data class Weekly(val days: Set<DayOfWeek>) : Recurrence {
        init { require(days.isNotEmpty()) { "Select at least one weekday" } }
    }
    data class Monthly(val day: Int) : Recurrence {
        init { require(day in 1..31) { "Monthly day must be 1–31; absent dates are skipped" } }
    }
}

data class Task(
    val id: String,
    val title: String,
    val firstDate: LocalDate,
    val startTime: LocalTime,
    val recurrence: Recurrence,
    val leadMinutes: Int? = null
) {
    init {
        require(id.isNotBlank())
        require(title.isNotBlank()) { "Title is required" }
        require(leadMinutes == null || leadMinutes >= 0) { "Lead time must be nonnegative" }
    }
}

data class Occurrence(val localStart: LocalDateTime, val lockAt: Instant)

/** Civil date identity survives timezone changes. DST gaps shift forward, overlaps use earlier offset. */
object Recurrences {
    fun next(task: Task, after: LocalDateTime?, zone: ZoneId): Occurrence? {
        var date = maxOf(task.firstDate, after?.toLocalDate() ?: task.firstDate)
        if (after != null && !date.atTime(task.startTime).isAfter(after)) date = date.plusDays(1)
        date = matching(task, date, forward = true) ?: return null
        return occurrence(task, date, zone)
    }

    /** Constant bounded search, not iteration through years of missed daily occurrences. */
    fun latestDue(task: Task, now: Instant, zone: ZoneId): Occurrence? {
        val threshold = now.plusSeconds((task.leadMinutes ?: 0).toLong() * 60)
        var date = threshold.atZone(zone).toLocalDate()
        // Check actual instant, including DST transitions, before considering previous date.
        while (date >= task.firstDate) {
            date = matching(task, date, forward = false) ?: return null
            if (date < task.firstDate) return null
            val candidate = occurrence(task, date, zone)
            if (candidate.lockAt <= now) return candidate
            date = date.minusDays(1)
        }
        return null
    }

    private fun occurrence(task: Task, date: LocalDate, zone: ZoneId): Occurrence {
        val local = date.atTime(task.startTime)
        return Occurrence(local, local.atZone(zone).toInstant().minusSeconds((task.leadMinutes ?: 0).toLong() * 60))
    }

    private fun matching(task: Task, from: LocalDate, forward: Boolean): LocalDate? {
        val step = if (forward) 1L else -1L
        return when (val rule = task.recurrence) {
            Recurrence.Once -> task.firstDate.takeIf { if (forward) it >= from else it <= from }
            Recurrence.Daily -> from
            is Recurrence.Weekly -> (0L..6L).map { from.plusDays(it * step) }.first { it.dayOfWeek in rule.days }
            is Recurrence.Monthly -> {
                var month = YearMonth.from(from)
                while (true) {
                    if (month.isValidDay(rule.day)) {
                        val date = month.atDay(rule.day)
                        if (if (forward) date >= from else date <= from) return date
                    }
                    month = month.plusMonths(step)
                }
                @Suppress("UNREACHABLE_CODE") null
            }
        }
    }
}
