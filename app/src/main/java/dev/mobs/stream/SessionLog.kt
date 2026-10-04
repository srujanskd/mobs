package dev.mobs.stream

import android.os.Process
import android.util.Log
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.concurrent.thread

/** Copies diagnostic log lines into a file until the returned handle is closed. */
internal fun interface LogCapture {
    /** Captures lines logged from [sinceMs] (wall clock) onward, so the session's first lines are kept. */
    fun start(file: File, sinceMs: Long): AutoCloseable
}

/**
 * Streams this process's own logcat into the file. Reading an app's own log needs no permission.
 * RootEncoder logs RTMP status replies, which can echo the stream name, so every line passes
 * through [redactLogLine] with the active key before it is written.
 */
internal class LogcatCapture(private val secret: () -> String?) : LogCapture {
    override fun start(file: File, sinceMs: Long): AutoCloseable {
        val since = LOGCAT_TIME.format(Instant.ofEpochMilli(sinceMs))
        val process = try {
            // -T replays from that time and then follows, instead of dumping the whole buffer.
            ProcessBuilder("logcat", "-v", "threadtime", "--pid=${Process.myPid()}", "-T", since)
                .redirectErrorStream(true)
                .start()
        } catch (e: IOException) {
            Log.w(TAG, "logcat unavailable: ${e.javaClass.simpleName}")
            return AutoCloseable {}
        }
        val reader = thread(name = "session-log", isDaemon = true) {
            try {
                file.bufferedWriter().use { out ->
                    process.inputStream.bufferedReader().forEachLine { line ->
                        out.write(redactLogLine(line, secret()))
                        out.newLine()
                        out.flush()
                    }
                }
            } catch (e: IOException) {
                // The stream closes when the process is destroyed.
            }
        }
        return AutoCloseable {
            process.destroy()
            reader.join(JOIN_TIMEOUT_MS)
        }
    }

    private companion object {
        const val TAG = "SessionLog"
        const val JOIN_TIMEOUT_MS = 1_000L

        /** logcat's -T format, in the device's zone like the log itself. */
        val LOGCAT_TIME: DateTimeFormatter =
            DateTimeFormatter.ofPattern("MM-dd HH:mm:ss.SSS").withZone(ZoneId.systemDefault())
    }
}

private val RTMP_URL_TAIL = Regex("""(rtmps?://[^/\s]+/[^/\s]+/)\S+""", RegexOption.IGNORE_CASE)

/** Removes [secret] wherever it appears, and anything after the application name in an RTMP URL. */
internal fun redactLogLine(line: String, secret: String?): String {
    val withoutSecret = if (secret.isNullOrBlank()) line else line.replace(secret, REDACTED)
    return RTMP_URL_TAIL.replace(withoutSecret) { "${it.groupValues[1]}$REDACTED" }
}

private const val REDACTED = "<REDACTED>"
