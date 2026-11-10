package com.tuntas.app.domain

import java.io.File
import java.nio.file.Files
import java.time.*
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class FileTaskRepositoryTest {
    private fun file() = domainTempDirectory("tuntas-store").resolve("tasks").toFile()
    @Test fun allScheduleTypesRoundTripThroughDisk() {
        val file = file(); val repo = FileTaskRepository(file)
        val rules = listOf(Recurrence.Once, Recurrence.Daily, Recurrence.Weekly(setOf(DayOfWeek.TUESDAY, DayOfWeek.SUNDAY)), Recurrence.Monthly(31))
        val rows = rules.mapIndexed { index, rule ->
            val task = Task("$index", "Membaca 📚", LocalDate.of(2024, 2, 29), LocalTime.of(23, 59), rule, 30)
            task.id to TaskState(task)
        }.toMap()
        val expected = TaskSnapshot(rows, setOf("org.example.phone"))
        repo.update { expected }
        assertEquals(expected, FileTaskRepository(file).read())
    }
    @Test fun failedTransactionAndInterruptedTempPreserveLastCommittedSnapshot() {
        val file = file(); val repo = FileTaskRepository(file)
        repo.update { it.copy(allowlist = setOf("safe")) }
        assertThrows(IllegalStateException::class.java) { repo.update { error("Interrupted") } }
        File(file.path + ".tmp").writeBytes(byteArrayOf(1, 2))
        assertEquals(setOf("safe"), FileTaskRepository(file).read().allowlist)
    }
    @Test fun corruptSnapshotFailsVisiblyRatherThanLosingCompletionHistory() {
        val file = file(); file.writeBytes(byteArrayOf(1, 2, 3, 4))
        assertThrows(IllegalArgumentException::class.java) { FileTaskRepository(file).read() }
    }
    @Test fun concurrentRepositoryInstancesDoNotLoseTransactions() {
        val file = file(); val pool = Executors.newFixedThreadPool(2)
        try {
            val futures = (1..30).map { n -> pool.submit { FileTaskRepository(file).update { it.copy(allowlist = it.allowlist + "app$n") } } }
            futures.forEach { it.get(10, TimeUnit.SECONDS) }
            assertEquals(30, FileTaskRepository(file).read().allowlist.size)
        } finally { pool.shutdownNow() }
    }
    @Test fun separateJvmRestartsReadPersistedTasksPendingAllowlistAndCompletion() {
        val file = file()
        val classpath = listOf(FileTaskRepository::class.java, RepositoryProcessProbe::class.java, kotlin.Unit::class.java)
            .map { File(it.protectionDomain.codeSource.location.toURI()).path }.distinct().joinToString(File.pathSeparator)
        fun run(mode: String) {
            val output = File(file.parentFile, "$mode.log")
            val process = ProcessBuilder(File(System.getProperty("java.home"), "bin/java").path, "-cp", classpath,
                RepositoryProcessProbe::class.java.name, file.path, mode).redirectErrorStream(true).redirectOutput(output).start()
            assertTrue("Child timeout", process.waitFor(15, TimeUnit.SECONDS))
            assertEquals(output.readText(), 0, process.exitValue())
        }
        run("pending"); run("complete"); run("verify")
    }
}

/** Real child JVMs, not a mock store or one object's in-memory cache. */
object RepositoryProcessProbe {
    @JvmStatic fun main(args: Array<String>) {
        val time = object : com.tuntas.app.platform.scheduling.ClockProvider {
            override fun nowEpochMillis() = Instant.parse("2024-01-01T10:00:00Z").toEpochMilli()
            override fun currentZoneId(): ZoneId = ZoneOffset.UTC
        }
        val engine = TaskEngine(FileTaskRepository(File(args[0])), time)
        when (args[1]) {
            "pending" -> {
                engine.saveTask(Task("a", "Read", LocalDate.of(2024, 1, 1), LocalTime.of(9, 0), Recurrence.Daily))
                engine.setAllowlist(setOf("phone"))
                check(engine.snapshot().blocking)
            }
            "complete" -> {
                check(engine.snapshot().allowlist == setOf("phone"))
                check(engine.complete("a", engine.snapshot().tasks.getValue("a").pending!!, true))
            }
            "verify" -> {
                engine.reconcile()
                check(!engine.snapshot().blocking)
                check(engine.snapshot().tasks.getValue("a").completedThrough != null)
                check(engine.snapshot().allowlist == setOf("phone"))
            }
            else -> error("Unknown probe mode")
        }
    }
}
