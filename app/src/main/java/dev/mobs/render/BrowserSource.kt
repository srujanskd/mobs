package dev.mobs.render

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import dev.mobs.scene.isBrowserUrl
import java.util.concurrent.atomic.AtomicBoolean

/** Main-thread WebView snapshots. One frame may be in flight, keeping GL backpressure bounded. */
internal class BrowserSource(
    context: Context,
    url: String,
    private val width: Int,
    private val height: Int,
    private val onError: () -> Unit = {},
    private val renderScale: Float = 1f,
    private val onFrame: (Bitmap, () -> Unit) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private val busy = AtomicBoolean(false)
    @Volatile private var closed = false
    private var web: WebView? = null
    private var failed = false
    private val tick = object : Runnable {
        override fun run() {
            if (closed) return
            val view = web ?: return
            if (busy.compareAndSet(false, true)) {
                val bitmap = androidx.core.graphics.createBitmap(width, height)
                try {
                    // A detached WebView has no parent to service requestLayout after DOM changes.
                    view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                    view.layout(0, 0, width, height)
                    if (!failed) view.draw(Canvas(bitmap))
                    onFrame(bitmap) { busy.set(false) }
                } catch (_: RuntimeException) { bitmap.recycle(); busy.set(false) }
            }
            main.postDelayed(this, 100)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun create(context: Context, url: String) {
        if (closed) return
        web = WebView(context).apply {
            setBackgroundColor(Color.TRANSPARENT)
            setInitialScale((renderScale * 100).toInt())
            setLayerType(View.LAYER_TYPE_SOFTWARE, null)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.mediaPlaybackRequiresUserGesture = true
            webViewClient = object : WebViewClient() {
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
        main.post(tick)
    }

    init { main.post { try { create(context.applicationContext, url) } catch (_: RuntimeException) { onError(); close() } } }
    fun close() {
        closed = true
        main.post { main.removeCallbacks(tick); web?.stopLoading(); web?.destroy(); web = null }
    }
}
