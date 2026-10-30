package dev.mobs.render

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.Choreographer
import android.view.View
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import dev.mobs.scene.isBrowserUrl
import java.util.concurrent.atomic.AtomicBoolean

/** Main-thread, vsync-paced WebView snapshots. Only one borrowed bitmap may be in flight.
 * Consumers must call complete after uploading/reading the bitmap and must not recycle it.
 */
internal class BrowserSource(
    context: Context,
    url: String,
    private var width: Int,
    private var height: Int,
    private val onError: () -> Unit = {},
    private val renderScale: Float = 1f,
    fps: Int = 30,
    private val onFrame: (Bitmap, () -> Unit) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private val busy = AtomicBoolean(false)
    @Volatile private var closed = false
    private var web: WebView? = null
    private var failed = false
    private var active = true
    private val choreographer by lazy { Choreographer.getInstance() }
    private var intervalNs = 1_000_000_000L / fps.also { require(it in 1..60) }
    private var nextFrameNs = 0L
    private var dirty = true
    private var lastCaptureNs = 0L
    private var lastChangeNs = 0L
    private var buffer: Bitmap? = null
    private val tick = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (closed || !active) return
            choreographer.postFrameCallback(this)
            if (frameTimeNanos < nextFrameNs) return
            // Keep a fixed phase rather than adding capture/upload time to every interval.
            nextFrameNs = if (nextFrameNs == 0L || frameTimeNanos - nextFrameNs >= intervalNs)
                frameTimeNanos + intervalNs else nextFrameNs + intervalNs
            val view = web ?: return
            if (dirty) lastChangeNs = frameTimeNanos
            // Reuse idle content, with a safety refresh for missed WebView invalidations.
            // A short grace period avoids gaps between asynchronous animation updates.
            if (!dirty && frameTimeNanos - lastChangeNs > ANIMATION_GRACE_NS &&
                frameTimeNanos - lastCaptureNs < IDLE_REFRESH_NS) return
            if (!busy.compareAndSet(false, true)) return
            val bitmap = buffer?.takeIf { !it.isRecycled && it.width == width && it.height == height }
                ?: androidx.core.graphics.createBitmap(width, height).also { replacement ->
                    buffer?.takeUnless { it.isRecycled }?.recycle()
                    buffer = replacement
                }
            val returned = AtomicBoolean(false)
            val complete = {
                if (returned.compareAndSet(false, true)) main.post {
                    if (closed || !active || bitmap.width != width || bitmap.height != height) {
                        if (buffer === bitmap) buffer = null
                        bitmap.recycle()
                    }
                    busy.set(false)
                }
                Unit
            }
            try {
                dirty = false // Invalidations during layout/draw must survive for the next frame.
                lastCaptureNs = frameTimeNanos
                // A detached WebView has no parent to service requestLayout after DOM changes.
                if (view.isLayoutRequested || view.width != width || view.height != height) {
                    view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                    view.layout(0, 0, width, height)
                }
                bitmap.eraseColor(Color.TRANSPARENT)
                if (!failed) view.draw(Canvas(bitmap))
                onFrame(bitmap, complete)
            } catch (_: RuntimeException) { dirty = true; complete() }
        }
    }

    private fun discardIdleBuffer() {
        if (!busy.get()) {
            buffer?.recycle()
            buffer = null
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun create(context: Context, url: String) {
        if (closed) return
        web = object : WebView(context) {
            override fun invalidate() { dirty = true; super.invalidate() }
            @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
            override fun invalidate(dirtyRect: Rect?) { dirty = true; super.invalidate(dirtyRect) }
            @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
            override fun invalidate(l: Int, t: Int, r: Int, b: Int) { dirty = true; super.invalidate(l, t, r, b) }
        }.apply {
            setBackgroundColor(Color.TRANSPARENT)
            setInitialScale((renderScale * 100).toInt())
            setLayerType(View.LAYER_TYPE_SOFTWARE, null)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.mediaPlaybackRequiresUserGesture = true
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) { dirty = true }
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                    !isBrowserUrl(request.url.toString())
                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: android.webkit.WebResourceError) {
                    if (!closed && request.isForMainFrame) { failed = true; onError() }
                }
                override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: android.webkit.WebResourceResponse) {
                    if (!closed && request.isForMainFrame) { failed = true; onError() }
                }
            }
            measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            layout(0, 0, width, height)
            loadUrl(url)
        }
        choreographer.postFrameCallback(tick)
    }

    init { main.post { try { create(context.applicationContext, url) } catch (_: RuntimeException) { onError(); close() } } }
    /** Keep the document and its live connections; only stop bitmap work while off scene. */
    fun setViewport(width: Int, height: Int, active: Boolean) {
        main.post {
            if (closed || (this.width == width && this.height == height && this.active == active)) return@post
            this.width = width
            this.height = height
            this.active = active
            dirty = true
            choreographer.removeFrameCallback(tick)
            nextFrameNs = 0L
            if (!active || buffer?.let { it.width != width || it.height != height } == true) discardIdleBuffer()
            if (active) choreographer.postFrameCallback(tick)
        }
    }

    /** Main-thread pacing changes preserve the document and borrowed-buffer lifetime. */
    fun setFrameRate(fps: Int) {
        require(fps in 1..60)
        main.post {
            if (!closed) {
                intervalNs = 1_000_000_000L / fps
                nextFrameNs = 0L
            }
        }
    }

    fun close() {
        closed = true
        main.post {
            choreographer.removeFrameCallback(tick)
            discardIdleBuffer()
            web?.stopLoading()
            web?.destroy()
            web = null
        }
    }

    private companion object {
        const val IDLE_REFRESH_NS = 1_000_000_000L
        const val ANIMATION_GRACE_NS = 250_000_000L
    }
}
