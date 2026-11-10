package com.tuntas.app.ui

import com.tuntas.app.domain.*
import java.time.*
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle

enum class RepeatKind(val label: String) { ONCE("Sekali"), DAILY("Harian"), WEEKLY("Hari tertentu"), MONTHLY("Bulanan") }
data class DraftResult(val task: Task? = null, val error: String? = null)
data class TaskDraft(
    val title: String = "",
    val date: String = LocalDate.now().toString(),
    val time: String = "",
    val lead: String = "",
    val kind: RepeatKind = RepeatKind.ONCE,
    val weekdays: Set<DayOfWeek> = emptySet(),
    val monthDay: String = "1"
) {
    fun validate(id: String): DraftResult {
        if (title.isBlank()) return DraftResult(error = "Judul task wajib diisi.")
        val parsedDate = runCatching { LocalDate.parse(date.trim()) }.getOrNull()
            ?: return DraftResult(error = "Tanggal tidak valid. Gunakan YYYY-MM-DD.")
        val parsedTime = runCatching { LocalTime.parse(time.trim(), TIME_FORMAT) }.getOrNull()
            ?: return DraftResult(error = "Jam mulai wajib dan valid. Gunakan HH:mm (00:00–23:59).")
        val minutes = if (lead.isBlank()) null else lead.trim().toIntOrNull()
            ?: return DraftResult(error = "Lead harus berupa menit bulat, nol atau lebih.")
        if (minutes != null && minutes < 0) return DraftResult(error = "Lead tidak boleh negatif.")
        val rule = when (kind) {
            RepeatKind.ONCE -> Recurrence.Once
            RepeatKind.DAILY -> Recurrence.Daily
            RepeatKind.WEEKLY -> if (weekdays.isEmpty()) return DraftResult(error = "Pilih minimal satu hari.") else Recurrence.Weekly(weekdays)
            RepeatKind.MONTHLY -> {
                val day = monthDay.trim().toIntOrNull()
                if (day == null || day !in 1..31) return DraftResult(error = "Tanggal bulanan harus 1–31.")
                Recurrence.Monthly(day)
            }
        }
        return DraftResult(Task(id, title.trim(), parsedDate, parsedTime, rule, minutes))
    }
    companion object {
        private val TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm").withResolverStyle(ResolverStyle.STRICT)
        fun from(task: Task) = TaskDraft(task.title, task.firstDate.toString(), task.startTime.format(TIME_FORMAT), task.leadMinutes?.toString() ?: "",
            when (task.recurrence) { Recurrence.Once -> RepeatKind.ONCE; Recurrence.Daily -> RepeatKind.DAILY; is Recurrence.Weekly -> RepeatKind.WEEKLY; is Recurrence.Monthly -> RepeatKind.MONTHLY },
            (task.recurrence as? Recurrence.Weekly)?.days ?: emptySet(), (task.recurrence as? Recurrence.Monthly)?.day?.toString() ?: "1")
    }
}
