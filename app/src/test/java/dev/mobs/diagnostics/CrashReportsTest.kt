package dev.mobs.diagnostics

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CrashReportsTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun reportSurvivesRecreationAndExcludesSecretsInMessages() {
        val store = CrashReports(folder.root, "app=test")
        val cause = IllegalArgumentException("https://widget.example/token secret-key")
        cause.stackTrace = arrayOf(StackTraceElement("dev.mobs.Camera", "capture", "Camera.kt", 42))
        val failure = IllegalStateException("rtmps://server/live/secret-key", cause)
        store.write(Thread.currentThread(), failure, 1_800_000_000_000)
        val report = CrashReports(folder.root, "app=test").files().single().readText()
        assertTrue(report.contains("java.lang.IllegalStateException"))
        assertTrue(report.contains("java.lang.IllegalArgumentException"))
        assertTrue(report.contains("dev.mobs.Camera.capture(Camera.kt:42)"))
        assertFalse(report.contains("secret-key"))
        assertFalse(report.contains("widget.example"))
        assertFalse(report.contains("rtmps://"))
    }

    @Test fun handlerPersistsBeforeDelegatingTheOriginalFailure() {
        val failure = RuntimeException("test")
        var saved = false
        var delegated = false
        val handler = ReportingExceptionHandler(save = { thread, error ->
            assertSame(Thread.currentThread(), thread)
            assertSame(failure, error)
            saved = true
        }, delegate = Thread.UncaughtExceptionHandler { thread, error ->
            assertTrue(saved)
            assertSame(Thread.currentThread(), thread)
            assertSame(failure, error)
            delegated = true
        })
        handler.uncaughtException(Thread.currentThread(), failure)
        assertTrue(delegated)
    }

    @Test fun storageFailureStillDelegatesToAndroid() {
        val blocked = folder.newFile("not-a-directory")
        var delegated = false
        val handler = ReportingExceptionHandler(
            save = { thread, error -> CrashReports(blocked, "test").write(thread, error) },
            delegate = Thread.UncaughtExceptionHandler { _, _ -> delegated = true },
        )
        handler.uncaughtException(Thread.currentThread(), IllegalStateException())
        assertTrue(delegated)
    }

    @Test fun historyAndReportSizeStayBoundedEvenWithCyclicCauses() {
        val store = CrashReports(folder.root, "app=test")
        val first = RuntimeException()
        val second = RuntimeException(first)
        first.initCause(second)
        first.stackTrace = Array(500) { StackTraceElement("X".repeat(2000), "capture", "Camera.kt", it) }
        repeat(15) { store.write(Thread.currentThread(), first, 1_800_000_000_000 + it.toLong()) }
        assertEquals(10, store.files().size)
        assertTrue(store.files().all { it.length() <= CrashReports.MAX_BYTES })
        assertTrue(store.files().first().name.contains("1800000000014"))
        assertFalse(File(folder.root, "crash-1800000000000-${Thread.currentThread().id}.txt").exists())
    }
}
