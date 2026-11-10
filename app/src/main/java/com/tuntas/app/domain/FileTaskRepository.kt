package com.tuntas.app.domain

import java.io.*
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.*
import java.util.concurrent.ConcurrentHashMap

/** Versioned offline binary snapshot. Use a CE filesDir path, one canonical file per app.
 * File lock serializes processes; fsync + atomic rename makes task/completion/allowlist one transaction.
 * Corruption/version failures are surfaced, never silently replaced with empty state.
 */
class FileTaskRepository(private val file: File) : TaskRepository {
    private val monitor = monitors.getOrPut(file.canonicalPath) { Any() }
    override fun read(): TaskSnapshot = locked { load() }
    override fun update(transform: (TaskSnapshot) -> TaskSnapshot): TaskSnapshot = locked {
        val next = transform(load())
        val temp = File(file.path + ".tmp")
        try {
            FileOutputStream(temp).use { output ->
                val data = DataOutputStream(output)
                encode(data, next)
                data.flush()
                output.fd.sync()
            }
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { temp.delete() }
        next
    }
    private fun <T> locked(action: () -> T): T = synchronized(monitor) {
        check(file.absoluteFile.parentFile!!.let { it.isDirectory || it.mkdirs() })
        RandomAccessFile(File(file.path + ".lock"), "rw").use { lockFile ->
            lockFile.channel.lock().use { action() }
        }
    }
    private fun load(): TaskSnapshot {
        if (!file.exists()) return TaskSnapshot()
        return DataInputStream(FileInputStream(file)).use { input ->
            require(input.readInt() == 0x54554E31) { "Unsupported or corrupt task store" }
            val rows = (0 until input.count()).map {
                val id = input.readUTF(); val title = input.readUTF()
                val date = LocalDate.parse(input.readUTF()); val time = LocalTime.parse(input.readUTF())
                val lead = input.readInt().also { require(it >= -1) }
                val rule = when (input.readInt()) {
                    0 -> Recurrence.Once
                    1 -> Recurrence.Daily
                    2 -> Recurrence.Weekly((0 until input.count()).map { DayOfWeek.of(input.readInt()) }.toSet())
                    3 -> Recurrence.Monthly(input.readInt())
                    else -> error("Unknown recurrence")
                }
                val pending = if (input.readBoolean()) Occurrence(LocalDateTime.parse(input.readUTF()), Instant.parse(input.readUTF())) else null
                val covered = input.localOrNull(); val completed = input.localOrNull()
                TaskState(Task(id, title, date, time, rule, lead.takeIf { it >= 0 }), pending, covered, completed)
            }
            require(rows.map { it.task.id }.distinct().size == rows.size)
            val allowlist = (0 until input.count()).map { input.readUTF() }.toSet()
            require(input.read() == -1) { "Trailing task store data" }
            TaskSnapshot(rows.associateBy { it.task.id }, allowlist)
        }
    }
    private fun encode(out: DataOutputStream, state: TaskSnapshot) {
        out.writeInt(0x54554E31); out.writeInt(state.tasks.size)
        state.tasks.toSortedMap().values.forEach { row ->
            val task = row.task
            out.writeUTF(task.id); out.writeUTF(task.title)
            out.writeUTF(task.firstDate.toString()); out.writeUTF(task.startTime.toString())
            out.writeInt(task.leadMinutes ?: -1)
            when (val rule = task.recurrence) {
                Recurrence.Once -> out.writeInt(0)
                Recurrence.Daily -> out.writeInt(1)
                is Recurrence.Weekly -> {
                    out.writeInt(2); out.writeInt(rule.days.size)
                    rule.days.sortedBy { it.value }.forEach { out.writeInt(it.value) }
                }
                is Recurrence.Monthly -> { out.writeInt(3); out.writeInt(rule.day) }
            }
            out.writeBoolean(row.pending != null)
            row.pending?.let { out.writeUTF(it.localStart.toString()); out.writeUTF(it.lockAt.toString()) }
            out.localOrNull(row.coveredThrough); out.localOrNull(row.completedThrough)
        }
        out.writeInt(state.allowlist.size); state.allowlist.sorted().forEach { out.writeUTF(it) }
    }
    private fun DataInputStream.count() = readInt().also { require(it in 0..100_000) }
    private fun DataInputStream.localOrNull() = if (readBoolean()) LocalDateTime.parse(readUTF()) else null
    private fun DataOutputStream.localOrNull(value: LocalDateTime?) {
        writeBoolean(value != null); if (value != null) writeUTF(value.toString())
    }
    companion object { private val monitors = ConcurrentHashMap<String, Any>() }
}
