package dev.mobs.render

import android.Manifest
import android.content.Intent
import android.graphics.PixelFormat
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import androidx.activity.ComponentActivity
import androidx.test.core.app.ActivityScenario
import androidx.test.filters.RequiresDevice
import androidx.test.platform.app.InstrumentationRegistry
import dev.mobs.scene.CameraZoomState
import dev.mobs.scene.Scene
import dev.mobs.scene.SceneSource
import dev.mobs.scene.SourceBounds
import dev.mobs.scene.SourceKind
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicReference

/** Opt-in physical-camera cadence check during 30 Hz zoom updates. Does not measure encoder/power. */
@RequiresDevice
class CameraZoomPerformanceDeviceTest {
    @Test fun rearZoomKeepsOutputAndCaptureCadence() = measure(SourceKind.REAR_CAMERA, false)
    @Test fun frontZoomKeepsOutputAndCaptureCadence() = measure(SourceKind.FRONT_CAMERA, false)
    @Test fun concurrentRearZoomKeepsBothCamerasCapturing() = measure(SourceKind.REAR_CAMERA, true)

    private fun measure(kind: SourceKind, dual: Boolean) {
        assumeTrue("Opt in with physicalCameras=true on a physical phone",
            InstrumentationRegistry.getArguments().getString("physicalCameras") == "true")
        assumeTrue("Emulators cannot establish performance acceptance",
            !Build.FINGERPRINT.contains("generic") && !Build.MODEL.contains("sdk"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        if (dual) assumeTrue(sceneDualCameraSupport(context).reason, sceneDualCameraSupport(context).available)
        val range = scenePinchZoomRange(context, kind, dual)
        val maximum = minOf(2f, range.endInclusive)
        assumeTrue("Selected camera must support zoom above 1x", maximum > 1.05f && 1f in range)
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.CAMERA)
        ActivityScenario.launch<ComponentActivity>(Intent(context, ComponentActivity::class.java)).use {
            val consumer = HandlerThread("ZoomPerformanceConsumer").apply { start() }
            val reader = ImageReader.newInstance(1920, 1080, PixelFormat.RGBA_8888, 3)
            val output = ConcurrentLinkedQueue<Long>()
            val rates = ConcurrentLinkedQueue<Triple<Long, Int, Int>>()
            val errors = ConcurrentLinkedQueue<String>()
            val feedback = AtomicReference<CameraZoomState?>()
            val maximumReportedZoom = AtomicReference(1f)
            reader.setOnImageAvailableListener({ images ->
                images.acquireLatestImage()?.use { output.add(System.nanoTime()) }
            }, Handler(consumer.looper))
            val camera = SceneSource(name = "Zoom camera", kind = kind)
            val scene = Scene(name = "Zoom cadence", sources = listOf(camera) + if (dual) listOf(
                SceneSource(name = "Front", kind = SourceKind.FRONT_CAMERA, bounds = SourceBounds(.64f, .6f, .32f, .32f)),
            ) else emptyList())
            val renderer = SceneRenderer(context, File(context.filesDir, "scene-media"), 1920, 1080, 30,
                error = errors::add, captureStats = { rear, front -> rates.add(Triple(System.nanoTime(), rear, front)) },
                cameraZoom = { id, state -> if (id == camera.id) {
                    feedback.set(state)
                    maximumReportedZoom.updateAndGet { maxOf(it, state.actual ?: 1f) }
                } })
            try {
                renderer.start(reader.surface, scene)
                awaitConfirmed(feedback, errors, 1f)
                // Match the existing session gate's warm-up period before recording cadence.
                Thread.sleep(30_000)
                assertTrue(errors.toString(), errors.isEmpty())
                val start = System.nanoTime()
                val duration = 20_000_000_000L
                var updates = 0
                while (true) {
                    val elapsed = System.nanoTime() - start
                    if (elapsed >= duration || errors.isNotEmpty()) break
                    val progress = elapsed.toDouble() / duration
                    val triangle = if (progress <= .5) progress * 2 else (1 - progress) * 2
                    renderer.update(scene.update(camera.copy(zoom = 1f + (maximum - 1f) * triangle.toFloat())))
                    updates++
                    Thread.sleep(33)
                }
                val end = System.nanoTime()
                renderer.update(scene)
                awaitConfirmed(feedback, errors, 1f)
                val frames = output.filter { it in start..end }.sorted()
                val samples = rates.filter { it.first in (start + 1_000_000_000L)..end }
                val outputFps = frames.size * 1_000_000_000.0 / (end - start)
                val rearMean = samples.map { it.second }.average()
                val frontMean = samples.map { it.third }.average()
                val maxGapMs = (listOf(start) + frames + end).zipWithNext { a, b -> (b - a) / 1_000_000.0 }.maxOrNull() ?: Double.POSITIVE_INFINITY
                val report = JSONObject().put("device", Build.MODEL).put("fingerprint", Build.FINGERPRINT)
                    .put("camera", kind.name).put("dual", dual).put("updates", updates)
                    .put("maximumRequestedZoom", maximum).put("maximumReportedZoom", maximumReportedZoom.get())
                    .put("durationSeconds", (end - start) / 1_000_000_000.0).put("outputFps", outputFps)
                    .put("rearMeanFps", rearMean.takeIf { it.isFinite() } ?: JSONObject.NULL)
                    .put("frontMeanFps", frontMean.takeIf { it.isFinite() } ?: JSONObject.NULL)
                    .put("maxOutputGapMs", maxGapMs).put("errors", errors.joinToString())
                val reportFile = File(context.filesDir, "zoom-performance-${kind.name}-$dual.json")
                reportFile.writeText(report.toString(2))
                val details = report.toString()
                assertTrue(details, errors.isEmpty())
                assertTrue("Zoom submissions fell below 25 updates/sec: $details", updates >= 500)
                assertTrue("Camera did not reach the zoom ramp peak: $details", maximumReportedZoom.get() >= maximum - .1f)
                assertTrue("Insufficient cadence samples: $details", samples.size >= 15)
                assertTrue("Output below existing 29 fps mean budget: $details", outputFps >= 29)
                if (kind == SourceKind.REAR_CAMERA) assertTrue(details, rearMean >= 29)
                if (dual || kind == SourceKind.FRONT_CAMERA) assertTrue(details, frontMean >= 29)
                // A separate provisional zoom-stall budget; retain the measured gap for review.
                assertTrue("Output stalled for more than 500 ms: $details", maxGapMs <= 500)
            } finally {
                renderer.stop()
                reader.close()
                consumer.quitSafely()
                consumer.join(1_000)
            }
        }
    }

    private fun awaitConfirmed(feedback: AtomicReference<CameraZoomState?>, errors: Collection<String>, ratio: Float) {
        val deadline = System.nanoTime() + 15_000_000_000L
        while (System.nanoTime() < deadline && errors.isEmpty()) {
            val state = feedback.get()
            if (state?.confirmed == true && kotlin.math.abs(state.requested - ratio) < .03f) return
            Thread.sleep(50)
        }
        fail("Camera did not confirm $ratio: ${feedback.get()}, errors=$errors")
    }
}
