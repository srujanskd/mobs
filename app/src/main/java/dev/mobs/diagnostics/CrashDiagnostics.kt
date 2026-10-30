package dev.mobs.diagnostics

import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import java.io.File
import java.time.Instant

class MobsApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        val previous = Thread.getDefaultUncaughtExceptionHandler() ?: return
        val reports = CrashReports(CrashDiagnostics.directory(this), CrashDiagnostics.environment(this))
        Thread.setDefaultUncaughtExceptionHandler(ReportingExceptionHandler(
            save = { thread, failure -> reports.write(thread, failure) }, delegate = previous,
        ))
    }
}

internal object CrashDiagnostics {
    fun directory(context: Context) = File(context.filesDir, "diagnostics")

    @Suppress("DEPRECATION")
    fun environment(context: Context): String {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        return "app=${info.versionName} (${info.longVersionCode})\n" +
            "device=${Build.MANUFACTURER} ${Build.MODEL}\nandroid=${Build.VERSION.RELEASE} api=${Build.VERSION.SDK_INT}"
    }

    /** Called on IO when sharing. Android keeps these records across process death. */
    fun collect(context: Context): List<File> {
        val directory = directory(context)
        val reports = CrashReports(directory, environment(context))
        val exitFile = File(directory, "process-exits.txt")
        try {
            directory.mkdirs()
            exitFile.bufferedWriter().use { out ->
                out.appendLine("Mobs process exit history, collected ${Instant.now()}")
                out.appendLine(environment(context))
                if (Build.VERSION.SDK_INT < 30) {
                    out.appendLine("Android process exit history requires Android 11 or newer.")
                } else {
                    val manager = context.getSystemService(ActivityManager::class.java)
                    val exits = manager.getHistoricalProcessExitReasons(context.packageName, 0, 10)
                    out.appendLine("records=${exits.size}; records may be missing or overwritten by Android.")
                    for (exit in exits) {
                        out.appendLine("time=${Instant.ofEpochMilli(exit.timestamp)} pid=${exit.pid} " +
                            "reason=${reason(exit.reason)} (${exit.reason}) status=${exit.status} " +
                            "importance=${exit.importance} pss_kb=${exit.pss} rss_kb=${exit.rss}")
                    }
                    // Descriptions and raw native traces may contain URLs or memory contents.
                    // Export structured metadata only; Java/Kotlin stacks are stored separately.
                }
            }
        } catch (_: Exception) {
            // An unavailable OS history or unwritable disk must not prevent sharing saved sessions.
            exitFile.delete()
        }
        return reports.files().take(CrashReports.KEEP_REPORTS) + listOf(exitFile).filter { it.isFile }
    }

    private fun reason(value: Int): String = when (value) {
        ApplicationExitInfo.REASON_CRASH -> "java_crash"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "native_crash"
        ApplicationExitInfo.REASON_ANR -> "not_responding"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "low_memory"
        ApplicationExitInfo.REASON_SIGNALED -> "signal"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "excessive_resources"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "user_requested"
        ApplicationExitInfo.REASON_USER_STOPPED -> "user_stopped"
        ApplicationExitInfo.REASON_EXIT_SELF -> "self_exit"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "initialization_failure"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "permission_change"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "dependency_died"
        else -> "other_or_unknown"
    }
}
