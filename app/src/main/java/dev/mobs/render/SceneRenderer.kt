package dev.mobs.render

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLUtils
import android.opengl.Matrix
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Size
import android.view.Surface
import androidx.core.graphics.createBitmap
import dev.mobs.scene.Scene
import dev.mobs.scene.SceneSource
import dev.mobs.scene.SourceKind
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * One surface compositor for all scene sources. Camera frames stay in external GL textures;
 * static textures are uploaded only after content changes. One output feeds preview and encoder.
 * All GL and camera operations run on the same handler, with at most one pending draw.
 */
internal class SceneRenderer(
    context: Context,
    private val mediaDirectory: File,
    private val width: Int,
    private val height: Int,
    private val fps: Int,
    private val error: (String) -> Unit,
    private val captureStats: (Int, Int) -> Unit = { _, _ -> },
    private val sourceError: (String) -> Unit = error,
    private val cameraZoom: (String, dev.mobs.scene.CameraZoomState) -> Unit = { _, _ -> },
) {
    private val appContext = context.applicationContext
    private val manager = context.getSystemService(CameraManager::class.java)
    private val thread = HandlerThread("SceneCompositor").apply { start() }
    private val handler = Handler(thread.looper)
    private val cameraCallbacks = Handler(Looper.getMainLooper())
    private val ownedDevices = java.util.Collections.synchronizedSet(mutableSetOf<CameraDevice>())
    private val restartGate = CameraRestartGate<CameraDevice>()
    private val zoomRecovery = ConcurrentZoomRecovery()
    private var renderFailed = false
    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var target: Surface? = null
    private var scene: Scene? = null
    @Volatile private var generation = 0
    @Volatile private var released = false
    private var lastStatsNs = System.nanoTime()
    private var nextTickNs = System.nanoTime()
    private val pendingScene = java.util.concurrent.atomic.AtomicReference<Scene?>(null)
    private val refreshRequested = java.util.concurrent.atomic.AtomicBoolean(false)
    private val updateQueued = java.util.concurrent.atomic.AtomicBoolean(false)
    private var drawing = false
    private var program2d = 0
    private var programCamera = 0
    private var locations2d: Locations? = null
    private var locationsCamera: Locations? = null
    private val mirroredMatrix = FloatArray(16)
    private val identity = FloatArray(16).also { Matrix.setIdentityM(it, 0) }
    private val textures = mutableMapOf<String, StaticTexture>()
    private val browsers = mutableMapOf<String, Pair<List<Any>, BrowserSource>>()
    private val cameras = linkedMapOf<SourceKind, CameraInput>()
    private val quad = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
        put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)); position(0)
    }
    // Android bitmap/camera textures have their origin at the top left.
    private val uv = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
        put(floatArrayOf(0f, 1f, 1f, 1f, 0f, 0f, 1f, 0f)); position(0)
    }
    private val drawTask = Runnable {
        drawing = false
        if (!renderFailed) try { draw() } catch (_: RuntimeException) {
            renderFailed = true
            closeCameras()
            error("Scene rendering stopped because the output surface was lost")
        }
    }
    private val staticTick = object : Runnable {
        override fun run() {
            if (released) return
            if (cameras.isEmpty()) requestDraw()
            val now = System.nanoTime()
            if (cameras.values.any { now - it.lastArrivalNs > 5_000_000_000L }) cameraFailure(generation)
            val elapsed = (now - lastStatsNs) / 1_000_000_000.0
            if (elapsed >= 1) {
                captureStats(kotlin.math.round((cameras[SourceKind.REAR_CAMERA]?.frames ?: 0) / elapsed).toInt(),
                    kotlin.math.round((cameras[SourceKind.FRONT_CAMERA]?.frames ?: 0) / elapsed).toInt())
                cameras.values.forEach { it.frames = 0 }
                lastStatsNs = now
            }
            nextTickNs = maxOf(nextTickNs + 1_000_000_000L / fps, now)
            handler.postDelayed(this, ((nextTickNs - System.nanoTime() + 999_999) / 1_000_000).coerceAtLeast(1))
        }
    }

    fun start(surfaceTexture: SurfaceTexture, initial: Scene) {
        surfaceTexture.setDefaultBufferSize(width, height)
        start(Surface(surfaceTexture), initial)
    }

    /** Takes ownership of the supplied output surface. Also used by pixel-level device tests. */
    fun start(surface: Surface, initial: Scene) {
        handler.post {
            runSafely {
                target = surface
                initGl(requireNotNull(target))
                updateOnGl(initial)
            }
            handler.post(staticTick)
        }
    }

    fun update(value: Scene) {
        if (released) return
        pendingScene.set(value)
        scheduleUpdate()
    }

    /** Recreate source resources while keeping the compositor output surface and encoder alive. */
    fun refresh(value: Scene) {
        if (released) return
        refreshRequested.set(true)
        update(value)
    }

    private fun releaseSources() {
        closeCameras()
        browsers.values.forEach { it.second.close() }
        browsers.clear()
        textures.values.forEach { GLES20.glDeleteTextures(1, intArrayOf(it.id), 0) }
        textures.clear()
    }

    private fun scheduleUpdate() {
        if (updateQueued.compareAndSet(false, true)) handler.post {
            pendingScene.getAndSet(null)?.let { latest -> runSafely {
                if (refreshRequested.getAndSet(false)) { zoomRecovery.reset(); releaseSources() }
                updateOnGl(latest)
            } }
            updateQueued.set(false)
            if (pendingScene.get() != null && !released) scheduleUpdate()
        }
    }

    fun stop() {
        val done = CountDownLatch(1)
        handler.post {
            released = true
            handler.removeCallbacksAndMessages(null)
            pendingScene.set(null)
            releaseSources()
            if (display != EGL14.EGL_NO_DISPLAY) {
                GLES20.glDeleteProgram(program2d)
                GLES20.glDeleteProgram(programCamera)
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                EGL14.eglDestroySurface(display, eglSurface)
                EGL14.eglDestroyContext(display, eglContext)
                EGL14.eglTerminate(display)
            }
            target?.release()
            done.countDown()
            thread.quitSafely()
        }
        if (!done.await(2, TimeUnit.SECONDS)) {
            // Releasing the downstream consumer can unblock a stalled driver. Never crash the
            // service because cleanup is delayed; the queued cleanup still owns every resource.
            error("Scene cleanup is delayed by the graphics driver")
        }
    }

    private fun runSafely(block: () -> Unit) {
        if (released) return
        try { block() } catch (_: Exception) {
            // Platform exception messages may expose file paths. Keep the UI error stable.
            closeCameras()
            error("Scene capture failed. Check camera availability and choose a supported scene.")
            requestDraw()
        }
    }

    private fun initGl(surface: Surface) {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(EGL14.eglInitialize(display, IntArray(2), 0, IntArray(2), 1))
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        check(EGL14.eglChooseConfig(display, intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8, EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT, 0x3142, 1, EGL14.EGL_NONE,
        ), 0, configs, 0, 1, count, 0) && count[0] > 0)
        eglContext = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
        eglSurface = EGL14.eglCreateWindowSurface(display, configs[0], surface, intArrayOf(EGL14.EGL_NONE), 0)
        check(EGL14.eglMakeCurrent(display, eglSurface, eglSurface, eglContext))
        program2d = makeProgram(false)
        programCamera = makeProgram(true)
        locations2d = Locations(program2d)
        locationsCamera = Locations(programCamera)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
    }

    private fun updateOnGl(value: Scene) {
        if (eglContext == EGL14.EGL_NO_CONTEXT) return
        val previous = scene
        zoomRecovery.select(value.id to value.sources.filter { it.kind in Scene.CAMERA_KINDS }.map { listOf(it.id, it.kind, it.zoom, it.visible) })
        scene = value
        renderFailed = false
        val visible = value.sources.filter { it.visible && it.opacity > 0f }
        val retained = visible.filter { it.kind !in Scene.CAMERA_KINDS }.map { it.id }.toSet()
        textures.keys.filter { it !in retained }.toList().forEach { id ->
            textures.remove(id)?.let { GLES20.glDeleteTextures(1, intArrayOf(it.id), 0) }
        }
        val browserIds = visible.filter { it.kind == SourceKind.BROWSER && it.url.isNotEmpty() }.map { it.id }.toSet()
        browsers.keys.filter { it !in browserIds }.toList().forEach { browsers.remove(it)?.second?.close() }
        visible.filter { it.kind == SourceKind.BROWSER && it.url.isNotEmpty() }.forEach { source ->
            val key = listOf(source.url, source.bounds.width, source.bounds.height)
            if (browsers[source.id]?.first != key) {
                browsers.remove(source.id)?.second?.close()
                val w = (960 * source.bounds.width).toInt().coerceAtLeast(1)
                val h = (540 * source.bounds.height).toInt().coerceAtLeast(1)
                val browser = BrowserSource(appContext, source.url, w, h, renderScale = .5f, onError = { handler.post {
                    browsers.remove(source.id)?.second?.close()
                    textures.remove(source.id)?.let { GLES20.glDeleteTextures(1, intArrayOf(it.id), 0) }
                    sourceError("Web overlay could not load. Check its widget link and internet connection, then apply the scene again.")
                    requestDraw()
                } }) { bitmap, complete ->
                    if (released || !handler.post {
                        try {
                            if (!released && browsers[source.id]?.first == key) {
                                val texture = textures[source.id] ?: StaticTexture(texture(GLES20.GL_TEXTURE_2D), key).also { textures[source.id] = it }
                                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture.id)
                                GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
                                // Camera frames or staticTick draw this texture at the output cadence.
                            }
                        } catch (_: RuntimeException) {
                            sourceError("Web overlay rendering failed. Remove the source or apply the scene again.")
                        } finally { bitmap.recycle(); complete() }
                    }) { bitmap.recycle(); complete() }
                }
                browsers[source.id] = key to browser
            }
        }
        visible.filter { it.kind !in Scene.CAMERA_KINDS && it.kind != SourceKind.BROWSER }.forEach { source ->
            val old = textures[source.id]
            val key = listOf(source.kind, source.text, source.media, source.color,
                source.bounds.width, source.bounds.height)
            if (old?.key != key) {
                old?.let { GLES20.glDeleteTextures(1, intArrayOf(it.id), 0) }
                val bitmap = rasterize(source)
                val id = texture(GLES20.GL_TEXTURE_2D)
                GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
                bitmap.recycle()
                textures[source.id] = StaticTexture(id, key)
            }
        }
        val crossesUltrawide = value.cameras.size == 2 && value.sources.any { source ->
            source.kind in Scene.CAMERA_KINDS && previous?.sources?.firstOrNull { it.id == source.id }?.let { (it.zoom < 1f) != (source.zoom < 1f) } == true
        }
        if (cameras.keys != value.cameras || crossesUltrawide) openCameras(value.cameras)
        else if (cameras.values.any { it.zoom.state.failed } || previous?.sources?.filter { it.kind in Scene.CAMERA_KINDS }?.map { it.zoom } != value.sources.filter { it.kind in Scene.CAMERA_KINDS }.map { it.zoom }) {
            cameras.forEach { (kind, input) -> input.session?.let { setRepeating(input, it, kind) } }
        }
        requestDraw()
    }

    private fun rasterize(source: SceneSource): Bitmap {
        val w = (width * source.bounds.width).toInt().coerceAtLeast(1)
        val h = (height * source.bounds.height).toInt().coerceAtLeast(1)
        val bitmap = createBitmap(w, h)
        val canvas = Canvas(bitmap)
        when (source.kind) {
            SourceKind.COLOR -> canvas.drawColor(source.color)
            SourceKind.TEXT -> {
                val text = TextRasterizer.rasterize(TextOverlay(source.text, textSize = 0.18f, maxWidth = 1f, backgroundColor = source.color), w, h)
                text?.let {
                    val fit = minOf(1f, w.toFloat() / it.width, h.toFloat() / it.height)
                    val dw = it.width * fit
                    val dh = it.height * fit
                    canvas.drawBitmap(it, null, android.graphics.RectF((w - dw) / 2, (h - dh) / 2, (w + dw) / 2, (h + dh) / 2), Paint(Paint.FILTER_BITMAP_FLAG))
                    it.recycle()
                }
            }
            SourceKind.IMAGE -> {
                val file = File(mediaDirectory, source.media)
                require(source.media.isNotEmpty() && file.isFile) { "Missing image" }
                val image = android.graphics.ImageDecoder.decodeBitmap(android.graphics.ImageDecoder.createSource(file)) { decoder, info, _ ->
                    val fit = minOf(1f, w.toFloat() / info.size.width, h.toFloat() / info.size.height)
                    decoder.setTargetSize((info.size.width * fit).toInt().coerceAtLeast(1), (info.size.height * fit).toInt().coerceAtLeast(1))
                    decoder.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
                }
                canvas.drawBitmap(image, (w - image.width) / 2f, (h - image.height) / 2f, Paint(Paint.FILTER_BITMAP_FLAG))
                image.recycle()
            }
            else -> Unit
        }
        return bitmap
    }

    private fun openCameras(kinds: Set<SourceKind>) {
        closeCameras()
        if (kinds.isEmpty()) return
        val requestedGeneration = generation
        restartGate.request { runSafely { if (requestedGeneration == generation) beginOpenCameras(kinds) } }
        handler.postDelayed({
            if (!released && generation == requestedGeneration && restartGate.waiting) {
                restartGate.cancel()
                error("Camera restart timed out while waiting for the previous cameras to close. Refresh sources to retry.")
            }
        }, 4_000)
    }

    private fun beginOpenCameras(kinds: Set<SourceKind>) {
        if (appContext.checkSelfPermission(android.Manifest.permission.CAMERA) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            throw SecurityException("Camera permission is required")
        }
        val pair = if (kinds.size == 2) requireNotNull(findCameraPair(manager)) { "Concurrent capture unavailable" } else null
        kinds.forEach { kind ->
            val id = if (pair == null) cameraId(manager, kind) else if (kind == SourceKind.REAR_CAMERA) pair.rear else pair.front
            val size = if (pair != null && kind == SourceKind.FRONT_CAMERA) Size(1280, 720) else Size(width, height)
            require(supportsSize(manager, id, size))
            val tex = texture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES)
            val inputTexture = SurfaceTexture(tex).apply { setDefaultBufferSize(size.width, size.height) }
            val orientation = CameraOrientation {
                val rotation = appContext.getSystemService(android.hardware.display.DisplayManager::class.java)
                    .getDisplay(android.view.Display.DEFAULT_DISPLAY)?.rotation ?: Surface.ROTATION_0
                when (rotation) { Surface.ROTATION_90 -> 90; Surface.ROTATION_180 -> 180; Surface.ROTATION_270 -> 270; else -> 0 }
            }
            val input = CameraInput(id, tex, inputTexture, Surface(inputTexture), orientation)
            cameras[kind] = input
            inputTexture.setOnFrameAvailableListener({
                if (cameras[kind] === input && !released) {
                    input.texture.updateTexImage()
                    input.texture.getTransformMatrix(input.matrix)
                    applyCameraDisplayRotation(input.matrix, input.orientation)
                    val timestamp = input.texture.timestamp
                    if (timestamp <= input.lastTimestamp) return@setOnFrameAvailableListener
                    input.lastTimestamp = timestamp
                    input.lastArrivalNs = System.nanoTime()
                    input.ready = true
                    input.frames++
                    // The rear camera drives dual-camera cadence. No extra output per front frame.
                    if (kind == SourceKind.REAR_CAMERA || SourceKind.REAR_CAMERA !in cameras) requestDraw()
                }
            }, handler)
        }
        if (Build.VERSION.SDK_INT >= 30 && cameras.size == 2) {
            val configurations = cameras.values.associate { input -> input.cameraId to SessionConfiguration(
                SessionConfiguration.SESSION_REGULAR, listOf(OutputConfiguration(input.surface)),
                { runnable -> handler.post(runnable) }, object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) = Unit
                    override fun onConfigureFailed(session: CameraCaptureSession) = Unit
                },
            ) }
            require(manager.isConcurrentSessionConfigurationSupported(configurations)) { "Concurrent sizes unsupported" }
        }
        val expectedGeneration = generation
        cameras.values.toList().forEach { input ->
            restartGate.opening(input)
            try {
                @Suppress("MissingPermission")
                manager.openCamera(input.cameraId, object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        synchronized(ownedDevices) {
                            if (released) { camera.close(); return }
                            ownedDevices.add(camera)
                        }
                        if (!handler.post {
                            if (generation != expectedGeneration || released) {
                                restartGate.closing(camera)
                                ownedDevices.remove(camera)
                                camera.close()
                                restartGate.opened(input)
                            } else {
                                ownedDevices.add(camera)
                                input.device = camera
                                restartGate.opened(input)
                                // Open every device before allocating either concurrent session.
                                runSafely { if (cameras.values.all { it.device != null }) cameras.values.forEach { configure(it, expectedGeneration) } }
                            }
                        }) { ownedDevices.remove(camera); camera.close() }
                    }
                    private fun failed(camera: CameraDevice) {
                        if (!handler.post {
                            restartGate.closing(camera)
                            ownedDevices.remove(camera)
                            camera.close()
                            restartGate.opened(input)
                            cameraFailure(expectedGeneration)
                        }) camera.close()
                    }
                    override fun onDisconnected(camera: CameraDevice) = failed(camera)
                    override fun onError(camera: CameraDevice, error: Int) = failed(camera)
                    override fun onClosed(camera: CameraDevice) { handler.post { restartGate.closed(camera) } }
                }, cameraCallbacks)
            } catch (failure: Exception) {
                restartGate.opened(input)
                throw failure
            }
        }
    }

    private fun cameraFailure(expectedGeneration: Int) {
        if (generation != expectedGeneration || released) return
        closeCameras()
        error("Camera disconnected or concurrent capture was refused. Select a single-camera scene to recover.")
        requestDraw()
    }

    @Suppress("DEPRECATION")
    private fun configure(input: CameraInput, expectedGeneration: Int) {
        input.device?.createCaptureSession(listOf(input.surface), object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) {
                if (generation != expectedGeneration || released) { session.close(); return }
                input.session = session
                runSafely {
                    val kind = cameras.entries.first { it.value === input }.key
                    setRepeating(input, session, kind)
                }
            }
            override fun onConfigureFailed(session: CameraCaptureSession) { session.close(); cameraFailure(expectedGeneration) }
        }, handler)
    }

    private fun setRepeating(input: CameraInput, session: CameraCaptureSession, kind: SourceKind, next: ZoomCommand? = null) {
        val info = manager.getCameraCharacteristics(input.cameraId)
        val ranges = info.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES).orEmpty()
        val range = ranges.firstOrNull { it.lower == fps && it.upper == fps }
            ?: ranges.filter { it.contains(fps) }.minByOrNull { it.upper - it.lower }
            ?: kotlin.error("Camera cannot capture at requested frame rate")
        val zoom = scene?.sources?.firstOrNull { it.kind == kind }?.zoom ?: 1f
        val zoomRange = if (Build.VERSION.SDK_INT >= 30) info.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE) else null
        val effective = zoom.coerceIn(zoomRange?.lower ?: 1f, zoomRange?.upper ?: info.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f)
        val command = next ?: input.zoom.request(effective)
        publishZoom(input, kind)
        val request = requireNotNull(input.device).createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
            setTag(command)
            addTarget(input.surface)
            set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, range)
            configureVideoProcessing(info)
            // The compositor owns rotation. Disable compatibility rotation when the camera allows it.
            if (Build.VERSION.SDK_INT >= 31 && info.get(CameraCharacteristics.SCALER_AVAILABLE_ROTATE_AND_CROP_MODES)
                    ?.contains(CaptureRequest.SCALER_ROTATE_AND_CROP_NONE) == true) {
                set(CaptureRequest.SCALER_ROTATE_AND_CROP, CaptureRequest.SCALER_ROTATE_AND_CROP_NONE)
            }
            if (Build.VERSION.SDK_INT >= 30 && zoomRange != null) {
                // Explicitly clear any crop inherited from the previous physical lens.
                info.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)?.let { set(CaptureRequest.SCALER_CROP_REGION, it) }
                set(CaptureRequest.CONTROL_ZOOM_RATIO, command.ratio)
            } else {
                info.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)?.let { sensor ->
                    val ratio = command.ratio
                    val dx = ((sensor.width() - sensor.width() / ratio) / 2).toInt()
                    val dy = ((sensor.height() - sensor.height() / ratio) / 2).toInt()
                    set(CaptureRequest.SCALER_CROP_REGION, android.graphics.Rect(sensor.left + dx, sensor.top + dy, sensor.right - dx, sensor.bottom - dy))
                }
            }
        }.build()
        session.setRepeatingRequest(request, object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: android.hardware.camera2.TotalCaptureResult) {
                if (released || input.session !== session || cameras[kind] !== input) return
                val tag = request.tag as? ZoomCommand ?: return
                val reported = if (Build.VERSION.SDK_INT >= 30 && zoomRange != null) result.get(android.hardware.camera2.CaptureResult.CONTROL_ZOOM_RATIO)
                    else result.get(android.hardware.camera2.CaptureResult.SCALER_CROP_REGION)?.let { crop ->
                        info.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)?.let { sensor -> sensor.width().toFloat() / crop.width().coerceAtLeast(1) }
                    }
                val following = input.zoom.observe(tag, reported)
                publishZoom(input, kind)
                if (following != null) runSafely { setRepeating(input, session, kind, following) }
            }
        }, handler)
    }

    private fun publishZoom(input: CameraInput, kind: SourceKind) {
        val state = input.zoom.state
        if (state != input.lastZoomState) {
            input.lastZoomState = state
            scene?.sources?.firstOrNull { it.kind == kind }?.let { cameraZoom(it.id, state) }
            if (state.failed) {
                if (zoomRecovery.retry(cameras.size == 2, state.requested)) {
                    val expectedGeneration = generation
                    handler.post { runSafely {
                        if (generation == expectedGeneration && cameras[kind] === input) scene?.let { openCameras(it.cameras) }
                    } }
                } else sourceError(if (cameras.size == 2 && state.requested < 1f)
                    "The camera did not confirm ultrawide in dual mode after restarting both cameras. Use a single rear-camera scene for the full lens range."
                    else "Camera did not confirm the requested zoom. Its reported zoom is shown instead. Try refreshing the sources.")
            }
        }
    }

    private fun closeCameras() {
        generation++
        restartGate.cancel()
        synchronized(ownedDevices) {
            ownedDevices.forEach { restartGate.closing(it) }
            ownedDevices.forEach { it.close() }
            ownedDevices.clear()
        }
        cameras.values.forEach { input ->
            input.session?.close()
            input.device?.close()
            input.surface.release()
            input.texture.release()
            GLES20.glDeleteTextures(1, intArrayOf(input.id), 0)
        }
        cameras.clear()
    }

    private fun requestDraw() {
        if (!drawing && !released) { drawing = true; handler.post(drawTask) }
    }

    private fun draw() {
        if (released || eglContext == EGL14.EGL_NO_CONTEXT) return
        android.os.Trace.beginSection("Mobs.sceneFrame")
        try {
        GLES20.glViewport(0, 0, width, height)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        scene?.sources?.forEach { source ->
            if (!source.visible || source.opacity <= 0f) return@forEach
            val camera = cameras[source.kind]
            if (source.kind in Scene.CAMERA_KINDS && camera?.ready != true) return@forEach
            val id = camera?.id ?: textures[source.id]?.id ?: return@forEach
            val program = if (camera != null) programCamera else program2d
            GLES20.glUseProgram(program)
            val locations = requireNotNull(if (camera != null) locationsCamera else locations2d)
            val position = locations.position
            val texCoord = locations.texCoord
            GLES20.glEnableVertexAttribArray(position)
            GLES20.glEnableVertexAttribArray(texCoord)
            quad.position(0); uv.position(0)
            GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 0, quad)
            GLES20.glVertexAttribPointer(texCoord, 2, GLES20.GL_FLOAT, false, 0, uv)
            val matrix = if (camera != null && (source.mirrored || source.rotation != 0)) mirroredMatrix.also { System.arraycopy(camera.matrix, 0, it, 0, 16) } else camera?.matrix ?: identity
            if (camera != null && source.rotation != 0) {
                Matrix.translateM(matrix, 0, .5f, .5f, 0f)
                Matrix.rotateM(matrix, 0, source.rotation.toFloat(), 0f, 0f, 1f)
                Matrix.translateM(matrix, 0, -.5f, -.5f, 0f)
            }
            if (camera != null && source.mirrored) {
                Matrix.translateM(matrix, 0, 1f, 0f, 0f)
                Matrix.scaleM(matrix, 0, -1f, 1f, 1f)
            }
            GLES20.glUniformMatrix4fv(locations.matrix, 1, false, matrix, 0)
            GLES20.glUniform1f(locations.opacity, source.opacity)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(if (camera != null) GLES11Ext.GL_TEXTURE_EXTERNAL_OES else GLES20.GL_TEXTURE_2D, id)
            GLES20.glUniform1i(locations.texture, 0)
            with(source.bounds) {
                GLES20.glViewport((x * this@SceneRenderer.width).toInt(), ((1f - y - height) * this@SceneRenderer.height).toInt(), (width * this@SceneRenderer.width).toInt(), (height * this@SceneRenderer.height).toInt())
            }
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GLES20.glDisableVertexAttribArray(position)
            GLES20.glDisableVertexAttribArray(texCoord)
        }
        EGLExt.eglPresentationTimeANDROID(display, eglSurface, System.nanoTime())
        check(EGL14.eglSwapBuffers(display, eglSurface)) { "Scene output surface was lost" }
        } finally { android.os.Trace.endSection() }
    }

    private fun texture(target: Int): Int {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        GLES20.glBindTexture(target, ids[0])
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        return ids[0]
    }

    private fun makeProgram(external: Boolean): Int {
        fun shader(type: Int, code: String): Int {
            val id = GLES20.glCreateShader(type)
            GLES20.glShaderSource(id, code)
            GLES20.glCompileShader(id)
            val ok = IntArray(1)
            GLES20.glGetShaderiv(id, GLES20.GL_COMPILE_STATUS, ok, 0)
            check(ok[0] != 0) { GLES20.glGetShaderInfoLog(id) }
            return id
        }
        val vertex = shader(GLES20.GL_VERTEX_SHADER, "attribute vec4 aPosition; attribute vec2 aTexCoord; uniform mat4 uMatrix; varying vec2 vTexCoord; void main() { gl_Position = aPosition; vTexCoord = (uMatrix * vec4(aTexCoord,0.0,1.0)).xy; }")
        val fragment = shader(GLES20.GL_FRAGMENT_SHADER, (if (external) "#extension GL_OES_EGL_image_external : require\n" else "") +
            "precision mediump float; varying vec2 vTexCoord; uniform ${if (external) "samplerExternalOES" else "sampler2D"} uTexture; uniform float uOpacity; void main() { gl_FragColor = texture2D(uTexture, vTexCoord) * uOpacity; }")
        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vertex); GLES20.glAttachShader(program, fragment)
        GLES20.glLinkProgram(program)
        val ok = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, ok, 0)
        check(ok[0] != 0)
        GLES20.glDeleteShader(vertex); GLES20.glDeleteShader(fragment)
        return program
    }

    private class Locations(program: Int) {
        val position = GLES20.glGetAttribLocation(program, "aPosition")
        val texCoord = GLES20.glGetAttribLocation(program, "aTexCoord")
        val matrix = GLES20.glGetUniformLocation(program, "uMatrix")
        val opacity = GLES20.glGetUniformLocation(program, "uOpacity")
        val texture = GLES20.glGetUniformLocation(program, "uTexture")
    }
    private data class StaticTexture(val id: Int, val key: List<Any>)
    private class CameraInput(val cameraId: String, val id: Int, val texture: SurfaceTexture, val surface: Surface, val orientation: CameraOrientation) {
        val zoom = CameraZoomController()
        var lastZoomState: dev.mobs.scene.CameraZoomState? = null
        var device: CameraDevice? = null
        var session: CameraCaptureSession? = null
        var ready = false
        var frames = 0
        var lastTimestamp = Long.MIN_VALUE
        var lastArrivalNs = System.nanoTime()
        val matrix = FloatArray(16).also { Matrix.setIdentityM(it, 0) }
    }
}
