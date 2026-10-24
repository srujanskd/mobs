package dev.mobs.render

import android.Manifest
import android.content.Intent
import android.graphics.PixelFormat
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import androidx.activity.ComponentActivity
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.RequiresDevice
import androidx.test.platform.app.InstrumentationRegistry
import dev.mobs.scene.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/** Explicit physical-phone checks. No server or stream key is used. Not a power benchmark. */
@RequiresDevice
@RunWith(AndroidJUnit4::class)
class CameraSceneDeviceTest {
    @Test fun rearCameraProducesFreshFramesAfterAStaticSceneCut() = capture(dual = false)
    @Test fun supportedConcurrentCamerasBothProduceFreshFrames() = capture(dual = true)

    private fun capture(dual: Boolean) {
        assumeTrue("Pass -Pandroid.testInstrumentationRunnerArguments.physicalCameras=true on a physical phone",
            InstrumentationRegistry.getArguments().getString("physicalCameras") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assumeTrue("Emulators cannot establish camera acceptance", !android.os.Build.FINGERPRINT.contains("generic") && !android.os.Build.MODEL.contains("sdk"))
        if (dual) assumeTrue("Phone does not advertise the required concurrent capture sizes", sceneDualCameraSupport(context).available)
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.CAMERA)
        ActivityScenario.launch<ComponentActivity>(Intent(context, ComponentActivity::class.java)).use {
            val reader = ImageReader.newInstance(1920, 1080, PixelFormat.RGBA_8888, 3)
            val consumer = HandlerThread("SceneTestConsumer").apply { start() }
            reader.setOnImageAvailableListener({ it.acquireLatestImage()?.close() }, Handler(consumer.looper))
            val errors = CopyOnWriteArrayList<String>()
            val rates = CopyOnWriteArrayList<Pair<Int, Int>>()
            val source = SceneSource(name = "Rear", kind = SourceKind.REAR_CAMERA)
            val scene = Scene(name = "Camera test", sources = listOf(source) + if (dual) listOf(
                SceneSource(name = "Front", kind = SourceKind.FRONT_CAMERA, bounds = SourceBounds(0.64f, 0.6f, 0.32f, 0.32f)),
            ) else emptyList())
            val renderer = SceneRenderer(context, File(context.filesDir, "scene-media"), 1920, 1080, 30,
                error = errors::add, captureStats = { rear, front -> rates += rear to front })
            try {
                renderer.start(reader.surface, scene)
                awaitFrames(rates, errors, dual)
                renderer.update(Scene(name = "Static", sources = emptyList()))
                Thread.sleep(500)
                rates.clear()
                renderer.update(scene)
                awaitFrames(rates, errors, dual)
                rates.clear()
                renderer.refresh(scene)
                awaitFrames(rates, errors, dual)
                assertTrue(errors.toString(), errors.isEmpty())
            } finally { renderer.stop(); reader.close(); consumer.quitSafely(); consumer.join(1_000) }
        }
    }

    private fun awaitFrames(rates: List<Pair<Int, Int>>, errors: List<String>, dual: Boolean) {
        val deadline = System.nanoTime() + 15_000_000_000L
        while (System.nanoTime() < deadline && errors.isEmpty()) {
            if (rates.any { (rear, front) -> rear > 0 && (!dual || front > 0) }) return
            Thread.sleep(100)
        }
        fail("Fresh camera frames did not arrive: $rates, errors=$errors")
    }
}
