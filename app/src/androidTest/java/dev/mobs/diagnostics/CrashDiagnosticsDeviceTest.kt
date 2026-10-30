package dev.mobs.diagnostics

import androidx.core.content.FileProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

class CrashDiagnosticsDeviceTest {
    @Test fun exitHistoryAndSavedCrashesAreAvailableThroughShareProvider() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertTrue(context.applicationContext is MobsApplication)
        val files = CrashDiagnostics.collect(context)
        val history = files.single { it.name == "process-exits.txt" }
        assertTrue(history.readText().contains("Mobs process exit history"))
        assertTrue(history.readText().contains("app="))
        files.forEach { file ->
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
            context.contentResolver.openInputStream(uri).use { input ->
                assertNotNull("Diagnostics must be readable from the share URI", input)
                assertTrue(input!!.read() >= 0)
            }
        }
    }
}
