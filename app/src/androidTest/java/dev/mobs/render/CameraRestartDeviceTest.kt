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

    /**
     * Severe heat immediately keeps the inset of a two-camera scene as a frozen frame and opens
     * only the main camera. Moderate heat first allows a grace period (see InsetCameraPauseTest).
     * The emulator cannot run a concurrent pair, so the inset is frozen from a live
     * single-camera frame: that exercises the same capture path used when a pair is paused.
     */
    @Test fun severeHeatFreezesTheInsetAndOpensOnlyTheMainCamera() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.CAMERA)
        val manager = context.getSystemService(CameraManager::class.java)
        val facings = manager.cameraIdList.map { manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) }
        assumeTrue("Needs rear and front cameras", CameraCharacteristics.LENS_FACING_BACK in facings && CameraCharacteristics.LENS_FACING_FRONT in facings)
        val rear = cameraId(manager, SourceKind.REAR_CAMERA)
        ActivityScenario.launch<ComponentActivity>(Intent(context, ComponentActivity::class.java)).use {
            // Single-camera capture uses the output size, so use a size emulators advertise.
            val width = 640
            val height = 480
            val consumer = HandlerThread("FrozenInsetConsumer").apply { start() }
            val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3)
            val insetPixels = java.util.concurrent.atomic.AtomicReference(IntArray(0))
            reader.setOnImageAvailableListener({ r -> r.acquireLatestImage()?.use { image ->
                val plane = image.planes[0]
                // Upper half of the inset, clear of the label band along its bottom edge.
                insetPixels.set(IntArray(9) { i ->
                    val x = (width * (0.65f + 0.1f * (i % 3))).toInt()
                    val y = (height * (0.62f + 0.06f * (i / 3))).toInt()
                    val o = y * plane.rowStride + x * plane.pixelStride
                    (plane.buffer.get(o).toInt() and 0xFF) + (plane.buffer.get(o + 1).toInt() and 0xFF) + (plane.buffer.get(o + 2).toInt() and 0xFF)
                })
            } }, Handler(consumer.looper))
            val errors = CopyOnWriteArrayList<String>()
            val rearSamples = AtomicInteger()
            val frontOnlySamples = AtomicInteger()
            val rearAvailable = java.util.concurrent.atomic.AtomicBoolean(false)
            val availability = object : CameraManager.AvailabilityCallback() {
                override fun onCameraAvailable(cameraId: String) { if (cameraId == rear) rearAvailable.set(true) }
                override fun onCameraUnavailable(cameraId: String) { if (cameraId == rear) rearAvailable.set(false) }
            }
            manager.registerAvailabilityCallback(availability, Handler(consumer.looper))
            val renderer = SceneRenderer(context, File(context.filesDir, "scene-media"), width, height, 30,
                error = errors::add, captureStats = { rearFps, frontFps ->
                    if (rearFps > 0) rearSamples.incrementAndGet()
                    if (frontFps > 0 && rearFps == 0) frontOnlySamples.incrementAndGet() else frontOnlySamples.set(0)
                })
            val rearScene = Scene(name = "Rear", sources = listOf(SceneSource(name = "Rear", kind = SourceKind.REAR_CAMERA)))
            val dual = Scene(name = "Front with rear inset", sources = listOf(
                SceneSource(name = "Front", kind = SourceKind.FRONT_CAMERA),
                SceneSource(name = "Rear inset", kind = SourceKind.REAR_CAMERA,
                    bounds = dev.mobs.scene.SourceBounds(.6f, .6f, .4f, .4f)),
            ))
            try {
                renderer.start(reader.surface, rearScene)
                awaitFreshSample(rearSamples, 0, errors)
                renderer.setHeatMode(dev.mobs.stream.HeatMode.HOT)
                renderer.update(dual)
                val deadline = System.nanoTime() + 10_000_000_000L
                while ((frontOnlySamples.get() < 2 || !rearAvailable.get()) && errors.isEmpty() && System.nanoTime() < deadline) Thread.sleep(50)
                assertTrue(errors.toString(), errors.isEmpty())
                assertTrue("Only the main camera may deliver frames under severe heat", frontOnlySamples.get() >= 2)
                assertTrue("The paused inset camera must be closed", rearAvailable.get())
                Thread.sleep(300)
                assertTrue("The frozen inset must keep the last rear image: ${insetPixels.get().toList()}",
                    insetPixels.get().any { it > 0 })
                // Normal heat alone does not resume the inset until the hold elapses.
                renderer.setHeatMode(dev.mobs.stream.HeatMode.NORMAL)
                Thread.sleep(2_000)
                assertTrue("Inset must stay paused during the cooling hold", rearAvailable.get())
                assertTrue(frontOnlySamples.get() >= 2)
                assertTrue(errors.toString(), errors.isEmpty())
            } finally {
                renderer.stop()
                manager.unregisterAvailabilityCallback(availability)
                reader.close(); consumer.quitSafely(); consumer.join(1_000)
            }
        }
    }

    @Test fun insetCaptureUsesSmallerBuffersAndRestoresDetailWhenEnlarged() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.CAMERA)
        val manager = context.getSystemService(CameraManager::class.java)
        val rear = cameraId(manager, SourceKind.REAR_CAMERA)
        val advertised = manager.getCameraCharacteristics(rear).get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?.getOutputSizes(SurfaceTexture::class.java).orEmpty().map { CaptureSize(it.width, it.height) }
        val full = advertised.filter { size ->
            size.width <= 1920 && size.height <= 1080 && advertised.any { smaller ->
                smaller.width >= 640 && smaller.height >= 360 && smaller.pixels < size.pixels &&
                    smaller.width.toLong() * size.height == smaller.height.toLong() * size.width
            }
        }.maxByOrNull { it.pixels }
        assumeTrue("Camera needs two same-aspect sizes above the capture floor: $advertised", full != null)
        val source = SceneSource(name = "Rear inset", kind = SourceKind.REAR_CAMERA,
            bounds = dev.mobs.scene.SourceBounds(width = .25f, height = .25f))
        val small = cameraCaptureSize(advertised, full!!, full, source)
        assumeTrue("Camera needs two landscape buffer sizes", small != full)
        ActivityScenario.launch<ComponentActivity>(Intent(context, ComponentActivity::class.java)).use {
            val consumer = HandlerThread("CameraSizeConsumer").apply { start() }
            val reader = ImageReader.newInstance(full.width, full.height, PixelFormat.RGBA_8888, 3)
            reader.setOnImageAvailableListener({ it.acquireLatestImage()?.close() }, Handler(consumer.looper))
            val errors = CopyOnWriteArrayList<String>()
            val work = CopyOnWriteArrayList<RenderStats>()
            val rates = AtomicInteger()
            val renderer = SceneRenderer(context, context.cacheDir, full.width, full.height, 30,
                error = errors::add, renderStats = work::add,
                captureStats = { fps, _ -> if (fps > 0) rates.incrementAndGet() })
            val scene = Scene(name = "Capture budget", sources = listOf(source))
            fun awaitSize(size: CaptureSize) {
                val before = rates.get()
                awaitFreshSample(rates, before, errors)
                assertTrue("Actual capture must use $size; got $work", work.any { it.rearWidth == size.width && it.rearHeight == size.height })
            }
            try {
                renderer.start(reader.surface, scene)
                awaitSize(small)
                work.clear()
                renderer.update(scene.update(source.copy(bounds = dev.mobs.scene.SourceBounds())))
                awaitSize(full)
                work.clear()
                renderer.setHeatMode(dev.mobs.stream.HeatMode.HOT)
                val ranges = manager.getCameraCharacteristics(rear).get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
                    .orEmpty().map { it.lower..it.upper }
                val expected = captureFrameRate(ranges, 15, 30)
                awaitSize(full)
                assertTrue("Severe heat must reach the actual Camera2 request", work.any {
                    it.rearRequestedFpsMin == expected.first && it.rearRequestedFpsMax == expected.last
                })
                // Shrinking keeps the current buffers: only an enlarged source reopens the camera.
                renderer.update(scene)
                work.clear()
                awaitSize(full)
                assertTrue(errors.toString(), errors.isEmpty())
            } finally { renderer.stop(); reader.close(); consumer.quitSafely(); consumer.join(1000) }
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
