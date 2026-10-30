package dev.mobs.diagnostics

import java.io.File
import java.time.Instant
import java.util.Collections
import java.util.IdentityHashMap

/** Small, private reports. Exception messages can contain stream keys or widget URLs, so omit them. */
internal class CrashReports(private val directory: File, private val environment: String) {
    fun write(thread: Thread, failure: Throwable, nowMs: Long = System.currentTimeMillis()) {
        directory.mkdirs()
        val file = File(directory, "crash-$nowMs-${thread.id}.txt")
        val report = buildString {
            appendLine("Mobs uncaught exception")
            appendLine("time=${Instant.ofEpochMilli(nowMs)}")
            appendLine(environment)
            appendLine("thread_id=${thread.id}")
            appendLine("Exception messages omitted to protect stream keys and widget URLs.")
            val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
            var cause: Throwable? = failure
            repeat(8) {
                val current = cause ?: return@repeat
                if (!seen.add(current)) { cause = null; return@repeat }
                appendLine("exception=${current.javaClass.name}")
                current.stackTrace.take(64).forEach { appendLine("  at $it") }
                cause = current.cause
            }
        }
        file.outputStream().use { out ->
            val bytes = report.toByteArray(Charsets.UTF_8)
            out.write(bytes, 0, minOf(bytes.size, MAX_BYTES))
        }
        files().drop(KEEP_REPORTS).forEach { it.delete() }
    }

    fun files(): List<File> = directory.listFiles { file ->
        file.isFile && file.name.startsWith("crash-") && file.extension == "txt"
    }.orEmpty().sortedByDescending { it.name }

    companion object {
        const val MAX_BYTES = 64 * 1024
        const val KEEP_REPORTS = 10
    }
}

/** Reporting must never swallow a crash or prevent Android's existing handler from running. */
internal class ReportingExceptionHandler(
    private val save: (Thread, Throwable) -> Unit,
    private val delegate: Thread.UncaughtExceptionHandler,
) : Thread.UncaughtExceptionHandler {
    override fun uncaughtException(thread: Thread, failure: Throwable) {
        try { save(thread, failure) } catch (_: Throwable) {
            // Best effort even when storage is full or the process is out of memory.
        } finally { delegate.uncaughtException(thread, failure) }
    }
}
