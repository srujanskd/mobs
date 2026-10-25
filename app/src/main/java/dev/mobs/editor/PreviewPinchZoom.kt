package dev.mobs.editor

import android.content.Context
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View

/** Keep a gesture bound to the camera selected when the second finger goes down. */
internal class PreviewPinchZoom(
    context: Context,
    currentGesture: () -> ((Float, Boolean) -> Unit)?,
) : View.OnTouchListener {
    private val detector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        private var gesture: ((Float, Boolean) -> Unit)? = null
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            gesture = currentGesture()
            return gesture != null
        }
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            gesture?.invoke(detector.scaleFactor, false)
            return true
        }
        override fun onScaleEnd(detector: ScaleGestureDetector) {
            gesture?.invoke(1f, true)
            gesture = null
        }
    }).apply { isQuickScaleEnabled = false }

    override fun onTouch(view: View, event: MotionEvent): Boolean {
        detector.onTouchEvent(event)
        return true
    }
}
