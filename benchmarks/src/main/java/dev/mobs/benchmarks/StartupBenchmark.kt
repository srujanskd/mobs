package dev.mobs.benchmarks

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Run on a dedicated physical phone against the optimized, profileable benchmark APK. */
@RunWith(AndroidJUnit4::class)
class StartupBenchmark {
    @get:Rule val benchmark = MacrobenchmarkRule()

    @Test fun coldStartup() = benchmark.measureRepeated(
        packageName = "dev.mobs",
        metrics = listOf(StartupTimingMetric()),
        compilationMode = CompilationMode.None(),
        startupMode = StartupMode.COLD,
        iterations = 10,
        setupBlock = {
            // Stable permission state; these grant nothing beyond the camera/mic app workflow.
            device.executeShellCommand("pm grant dev.mobs android.permission.CAMERA")
            device.executeShellCommand("pm grant dev.mobs android.permission.RECORD_AUDIO")
            pressHome()
        },
    ) { startActivityAndWait() }
}
