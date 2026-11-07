package dev.mobs.diagnostics

import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import dev.mobs.scene.Scene
import dev.mobs.scene.SceneSource
import dev.mobs.scene.SourceKind
import dev.mobs.stream.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

class SessionDiagnosticsDeviceTest {
    @Test fun capturesDeviceAndThreadCpuWithoutSourceContentsAndRecordsChanges() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val diagnostics = SessionDiagnostics(context)
        val scene = Scene(name = "private-scene-name", sources = listOf(
            SceneSource(name = "private-source-name", kind = SourceKind.BROWSER, url = "https://private.example/token"),
        ))
        val state = StreamEngineState(streamActive = true, scene = scene)
        val first = requireNotNull(diagnostics.sample(state, ThermalState()))
        val json = JSONObject(first)
        assertEquals("start", json.getString("kind"))
        assertTrue(json.getJSONObject("device").has("model"))
        val threads = json.getJSONObject("threads")
        assertTrue("App's own proc thread counters should be readable on this test device", threads.getBoolean("available"))
        assertTrue(threads.getJSONArray("samples").length() > 0)
        assertTrue(threads.getJSONArray("samples").getJSONObject(0).getLong("cpu_ticks") >= 0)
        assertTrue(json.getLong("clock_ticks_per_second") > 0)
        assertTrue(json.getJSONObject("environment").has("network"))
        assertFalse(first.contains("private"))
        assertFalse(first.contains("https://"))
        assertNull(diagnostics.sample(state, ThermalState()))
        val transition = JSONObject(requireNotNull(diagnostics.sample(state.copy(heatMode = HeatMode.WARM), ThermalState(ThermalLevel.MODERATE))))
        assertEquals("state_change", transition.getString("kind"))
        assertEquals("MODERATE", transition.getString("thermal"))
        assertFalse("Heavy sampling must stay on its ten-second interval", transition.has("threads"))
    }

    @Test fun actualLogcatCaptureRedactsUrlsAndKeysAndStopsOnClose() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, "session-log-test-${System.nanoTime()}.log")
        val failures = CopyOnWriteArrayList<String>()
        val marker = "test-marker-${System.nanoTime()}"
        val capture = LogcatCapture({ "fake-stream-key" }, failures::add).start(file, System.currentTimeMillis() - 500)
        try {
            Log.w("MobsDiagnosticTest", "$marker fake-stream-key https://widget.example/private-token")
            val deadline = System.nanoTime() + 5_000_000_000L
            while ((!file.exists() || !file.readText().contains(marker)) && System.nanoTime() < deadline) Thread.sleep(50)
            val saved = file.readText()
            assertTrue(saved.contains(marker))
            assertFalse(saved.contains("fake-stream-key"))
            assertFalse(saved.contains("private-token"))
            assertTrue(failures.toString(), failures.isEmpty())
        } finally { capture.close() }
        val length = file.length()
        Log.w("MobsDiagnosticTest", "$marker after-close")
        Thread.sleep(200)
        assertEquals(length, file.length())
        file.delete()
    }
}
