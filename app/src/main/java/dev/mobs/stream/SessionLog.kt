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
internal class LogcatCapture(private val secret: () -> String?, private val onFailure: (String) -> Unit = {}) : LogCapture {
    override fun start(file: File, sinceMs: Long): AutoCloseable {
        val since = LOGCAT_TIME.format(Instant.ofEpochMilli(sinceMs))
        val process = try {
            // -T replays from that time and then follows, instead of dumping the whole buffer.
            // RtmpClient/CommandsManager carry connect, publish and server status replies, which
            // are the only record of why an RTMP session failed. The per-second cap bounds them.
            ProcessBuilder("logcat", "-v", "threadtime", "--pid=${Process.myPid()}", "-T", since,
                "MobsRender:I", "VideoEncoder:I", "CCodec:I", "StreamEngine:I", "RtmpClient:I", "CommandsManager:I", "*:W")
                .redirectErrorStream(true)
                .start()
        } catch (e: IOException) {
            Log.w(TAG, "logcat unavailable: ${e.javaClass.simpleName}")
            onFailure("Session text logging is unavailable")
            return AutoCloseable {}
        }
        val closing = java.util.concurrent.atomic.AtomicBoolean(false)
        val reader = thread(name = "session-log", isDaemon = true) {
            try {
                BoundedSessionLog(file).use { out ->
                    var windowStart = android.os.SystemClock.elapsedRealtime()
                    var lines = 0
                    var dropped = 0
                    process.inputStream.bufferedReader().forEachLine { line ->
                        val now = android.os.SystemClock.elapsedRealtime()
                        if (now - windowStart >= 1_000) {
                            if (dropped > 0) out.write("[diagnostics] suppressed $dropped log lines in previous interval")
                            windowStart = now
                            lines = 0
                            dropped = 0
                        }
                        if (lines++ < 100) out.write(redactLogLine(line, secret()).take(4096)) else dropped++
                    }
                    if (dropped > 0) out.write("[diagnostics] suppressed $dropped log lines in final interval")
                }
                if (!closing.get()) onFailure("Session text logging ended unexpectedly")
            } catch (e: IOException) {
                // Destroying the process on close also lands here; only an unrequested stop is a failure.
                if (!closing.get()) onFailure("Session text logging failed")
            } finally {
                process.destroy()
            }
        }
        return AutoCloseable {
            closing.set(true)
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

private val WEB_URL = Regex("""https?://[^\s<>\"]+""", RegexOption.IGNORE_CASE)

private val URL_USERINFO = Regex("""(?<=://)[^/\s@]+@""")

private val RTMP_URL_TAIL = Regex("""(rtmps?://[^/\s]+/[^/\s]+/)\S+""", RegexOption.IGNORE_CASE)

/** Removes [secret] wherever it appears, and anything after the application name in an RTMP URL. */
internal fun redactLogLine(line: String, secret: String?): String {
    val withoutSecret = if (secret.isNullOrBlank()) line else line.replace(secret, REDACTED)
    val withoutUser = URL_USERINFO.replace(withoutSecret, "$REDACTED@")
    return RTMP_URL_TAIL.replace(WEB_URL.replace(withoutUser, REDACTED)) { "${it.groupValues[1]}$REDACTED" }
}

private const val REDACTED = "<REDACTED>"

/** The server for on-screen status, without any `user:password@` credentials RTMP allows in the URL. */
internal fun displayServer(server: String): String = URL_USERINFO.replace(server, "")
