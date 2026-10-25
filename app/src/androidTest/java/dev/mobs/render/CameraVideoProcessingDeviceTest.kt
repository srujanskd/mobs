package dev.mobs.render

import android.Manifest
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Checks real request builders against each camera's advertised modes, including fixed-focus cameras. */
class CameraVideoProcessingDeviceTest {
    @Test fun automaticVideoProcessingUsesSupportedModesAndPreservesUnsupportedDefaults() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.CAMERA)
        val manager = context.getSystemService(CameraManager::class.java)
        assumeTrue("No cameras advertised", manager.cameraIdList.isNotEmpty())
        ActivityScenario.launch<ComponentActivity>(Intent(context, ComponentActivity::class.java)).use {
            manager.cameraIdList.forEach { id ->
                val opened = CompletableFuture<CameraDevice>()
                val closed = CountDownLatch(1)
                manager.openCamera(id, object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) { if (!opened.complete(camera)) camera.close() }
                    override fun onDisconnected(camera: CameraDevice) {
                        camera.close()
                        opened.completeExceptionally(IllegalStateException("Camera $id disconnected"))
                    }
                    override fun onError(camera: CameraDevice, error: Int) {
                        camera.close()
                        opened.completeExceptionally(IllegalStateException("Camera $id error $error"))
                    }
                    override fun onClosed(camera: CameraDevice) { closed.countDown() }
                }, Handler(Looper.getMainLooper()))
                val camera = try { opened.get(10, TimeUnit.SECONDS) } catch (failure: Exception) {
                    opened.cancel(false)
                    throw failure
                }
                try {
                    val info = manager.getCameraCharacteristics(id)
                    val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
                    val defaults = request.build()
                    request.configureVideoProcessing(info)
                    val actual = request.build()
                    fun verify(key: CaptureRequest.Key<Int>, modes: IntArray?, expected: Int) {
                        assertEquals("Camera $id, ${key.name}",
                            if (modes?.contains(expected) == true) expected else defaults.get(key), actual.get(key))
                    }
                    verify(CaptureRequest.CONTROL_MODE, info.get(CameraCharacteristics.CONTROL_AVAILABLE_MODES), CaptureRequest.CONTROL_MODE_AUTO)
                    verify(CaptureRequest.CONTROL_AE_MODE, info.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES), CaptureRequest.CONTROL_AE_MODE_ON)
                    verify(CaptureRequest.CONTROL_AWB_MODE, info.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES), CaptureRequest.CONTROL_AWB_MODE_AUTO)
                    val focusModes = info.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)
                    verify(CaptureRequest.CONTROL_AF_MODE, focusModes,
                        if (focusModes?.contains(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO) == true)
                            CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO else CaptureRequest.CONTROL_AF_MODE_OFF)
                    verify(CaptureRequest.NOISE_REDUCTION_MODE, info.get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES), CaptureRequest.NOISE_REDUCTION_MODE_FAST)
                    verify(CaptureRequest.EDGE_MODE, info.get(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES), CaptureRequest.EDGE_MODE_FAST)
                    verify(CaptureRequest.TONEMAP_MODE, info.get(CameraCharacteristics.TONEMAP_AVAILABLE_TONE_MAP_MODES), CaptureRequest.TONEMAP_MODE_FAST)
                    verify(CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE, info.get(CameraCharacteristics.COLOR_CORRECTION_AVAILABLE_ABERRATION_MODES), CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_FAST)
                } finally {
                    camera.close()
                    assertTrue("Camera $id failed to close", closed.await(5, TimeUnit.SECONDS))
                }
            }
        }
    }
}
