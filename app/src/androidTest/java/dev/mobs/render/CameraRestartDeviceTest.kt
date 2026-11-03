package dev.mobs.render

import android.Manifest
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import androidx.activity.ComponentActivity
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.mobs.scene.Scene
import dev.mobs.scene.SceneSource
import dev.mobs.scene.SourceKind
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** Exercises actual Camera2 open/close callbacks on an emulator or phone. Not optical acceptance. */
class CameraRestartDeviceTest {
    @Test fun refreshAndSceneCutsReleaseUnusedCamerasAndResumeFreshFrames() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.CAMERA)
        val manager = context.getSystemService(CameraManager::class.java)
        val rear = manager.cameraIdList.firstOrNull {
            manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        }
        assumeTrue("No rear camera", rear != null)
        val size = manager.getCameraCharacteristics(rear!!).get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?.getOutputSizes(SurfaceTexture::class.java)?.firstOrNull { it.width == 640 && it.height == 480 }
        assumeTrue("Camera does not expose the regression fixture size", size != null)
        ActivityScenario.launch<ComponentActivity>(Intent(context, ComponentActivity::class.java)).use {
            val consumer = HandlerThread("RestartTestConsumer").apply { start() }
            val reader = ImageReader.newInstance(size!!.width, size.height, PixelFormat.RGBA_8888, 3)
            reader.setOnImageAvailableListener({ it.acquireLatestImage()?.close() }, Handler(consumer.looper))
            val errors = CopyOnWriteArrayList<String>()
            val samples = AtomicInteger()
            val rearAvailable = java.util.concurrent.atomic.AtomicBoolean(false)
            val idleSamples = AtomicInteger()
            val availability = object : CameraManager.AvailabilityCallback() {
                override fun onCameraAvailable(cameraId: String) { if (cameraId == rear) rearAvailable.set(true) }
                override fun onCameraUnavailable(cameraId: String) { if (cameraId == rear) rearAvailable.set(false) }
            }
            manager.registerAvailabilityCallback(availability, Handler(consumer.looper))
            val renderer = SceneRenderer(context, File(context.filesDir, "scene-media"), size.width, size.height, 30,
                error = errors::add, captureStats = { rearFps, frontFps ->
                    if (rearFps > 0) samples.incrementAndGet()
                    if (rearFps == 0 && frontFps == 0) idleSamples.incrementAndGet() else idleSamples.set(0)
                })
            val scene = Scene(name = "Restart regression", sources = listOf(SceneSource(name = "Rear", kind = SourceKind.REAR_CAMERA)))
            try {
                renderer.start(reader.surface, scene)
                awaitFreshSample(samples, 0, errors)
                repeat(3) {
                    val before = samples.get()
                    renderer.refresh(scene)
                    awaitFreshSample(samples, before, errors)
                }
                assertFalse("Active capture must own the rear camera", rearAvailable.get())
                renderer.update(Scene(name = "Logo only", sources = listOf(
                    SceneSource(name = "Background", kind = SourceKind.COLOR),
                )))
                val deadline = System.nanoTime() + 6_000_000_000L
                while ((!rearAvailable.get() || idleSamples.get() < 2) && System.nanoTime() < deadline) Thread.sleep(50)
                assertTrue("Leaving the camera scene must close the device", rearAvailable.get())
                assertTrue("Inactive cameras must stop producing frames", idleSamples.get() >= 2)
                val before = samples.get()
                renderer.update(scene)
                awaitFreshSample(samples, before, errors)
                assertTrue(errors.toString(), errors.isEmpty())
            } finally {
                renderer.stop()
                manager.unregisterAvailabilityCallback(availability)
                reader.close(); consumer.quitSafely(); consumer.join(1_000)
            }
        }
    }

    private fun awaitFreshSample(samples: AtomicInteger, before: Int, errors: List<String>) {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (System.nanoTime() < deadline && errors.isEmpty()) {
            // Two samples exclude one old cadence callback already queued at refresh time.
            if (samples.get() > before + 1) return
            Thread.sleep(50)
        }
        fail("Fresh camera frames did not resume: $errors")
    }
}
