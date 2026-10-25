package dev.mobs.editor

import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

/** Sends real multi-pointer MotionEvents through Android's ScaleGestureDetector. No camera needed. */
class PreviewPinchZoomDeviceTest {
    @Test fun spreadingAndClosingFingersProducesContinuousScaleAndOneFinish() = onMain {
        val samples = mutableListOf<Pair<Float, Boolean>>()
        val input = fixture { scale, finished -> samples += scale to finished }
        input.start()
        (120..300 step 20).forEach { input.move(it.toFloat()) }
        (280 downTo 100 step 20).forEach { input.move(it.toFloat()) }
        input.end()
        assertTrue(samples.any { !it.second && it.first > 1f })
        assertTrue(samples.any { !it.second && it.first < 1f })
        assertEquals(1, samples.count { it.second })
        assertTrue(samples.last().second)
    }

    @Test fun cancellationFinishesOnceAndNextGestureCanStart() = onMain {
        val samples = mutableListOf<Pair<Float, Boolean>>()
        val input = fixture { scale, finished -> samples += scale to finished }
        input.start()
        input.move(250f)
        input.move(300f)
        input.cancel()
        assertEquals(1, samples.count { it.second })
        input.start()
        input.move(250f)
        input.move(300f)
        input.end()
        assertEquals(2, samples.count { it.second })
    }

    @Test fun singleFingerDoesNotZoomAndAbsentCameraIgnoresPinch() = onMain {
        var calls = 0
        val input = fixture { _, _ -> calls++ }
        input.singleFinger()
        assertEquals(0, calls)
        val noCamera = Fixture { null }
        noCamera.start()
        noCamera.move(300f)
        noCamera.end()
    }

    @Test fun cameraCallbackIsCapturedAtGestureStart() = onMain {
        var oldCalls = 0
        var newCalls = 0
        var callback: (Float, Boolean) -> Unit = { _, _ -> oldCalls++ }
        val input = Fixture { callback }
        input.start()
        input.move(250f)
        input.move(280f)
        val before = oldCalls
        assertTrue(before > 0)
        callback = { _, _ -> newCalls++ }
        input.move(300f)
        input.end()
        assertTrue(oldCalls > before)
        assertEquals(0, newCalls)
        input.start()
        input.move(250f)
        input.move(300f)
        input.end()
        assertTrue(newCalls > 0)
    }

    private fun onMain(block: () -> Unit) {
        var failure: Throwable? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            try { block() } catch (caught: Throwable) { failure = caught }
        }
        failure?.let { throw it }
    }
    private fun fixture(callback: (Float, Boolean) -> Unit) = Fixture { callback }

    private class Fixture(callback: () -> ((Float, Boolean) -> Unit)?) {
        private val context = InstrumentationRegistry.getInstrumentation().targetContext
        private val listener = PreviewPinchZoom(context, callback)
        private val view = View(context)
        private var downTime = SystemClock.uptimeMillis()
        private var time = downTime
        fun start() {
            downTime = time + 20
            event(MotionEvent.ACTION_DOWN, 1, 100f)
            event(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 2, 100f)
        }
        fun move(span: Float) = event(MotionEvent.ACTION_MOVE, 2, span)
        fun end() {
            event(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 2, 100f)
            event(MotionEvent.ACTION_UP, 1, 100f)
        }
        fun cancel() = event(MotionEvent.ACTION_CANCEL, 2, 100f)
        fun singleFinger() {
            event(MotionEvent.ACTION_DOWN, 1, 100f)
            event(MotionEvent.ACTION_MOVE, 1, 200f)
            event(MotionEvent.ACTION_UP, 1, 200f)
        }
        private fun event(action: Int, count: Int, span: Float) {
            time += 20
            val properties = Array(count) { i -> MotionEvent.PointerProperties().apply { id = i; toolType = MotionEvent.TOOL_TYPE_FINGER } }
            val coordinates = Array(count) { i -> MotionEvent.PointerCoords().apply {
                // Physical pixels must exceed the device's density-dependent minimum scaling span.
                x = 1200f + if (i == 0) -span * 3 else span * 3
                y = 400f; pressure = 1f; size = 1f
            } }
            val event = MotionEvent.obtain(downTime, time, action, count, properties, coordinates, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
            try { listener.onTouch(view, event) } finally { event.recycle() }
        }
    }
}
