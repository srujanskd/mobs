package dev.mobs.editor

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.view.Surface
import android.view.WindowManager
import android.widget.Toast
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.material3.Surface
import androidx.compose.material3.HorizontalDivider
import dev.mobs.scene.Scene
import dev.mobs.scene.SceneSource
import dev.mobs.scene.SourceKind
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.mobs.R
import dev.mobs.diagnostics.CrashDiagnostics
import dev.mobs.render.OverlayAnchor
import dev.mobs.render.TextOverlay
import dev.mobs.stream.CameraFacing
import dev.mobs.stream.CameraStatus
import dev.mobs.stream.ConnectionState
import dev.mobs.stream.DestinationProblem
import dev.mobs.stream.SessionRecorder
import dev.mobs.stream.StreamService
import dev.mobs.stream.StreamDestination
import dev.mobs.stream.StreamEngineState
import dev.mobs.stream.StreamStats
import dev.mobs.stream.ThermalLevel
import dev.mobs.stream.ThermalState
import dev.mobs.stream.isCritical
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Proof-of-concept live screen: the composed camera output, mute and camera switch, stream-key
 * setup, and Go live / Stop. The preview is the same composition that feeds the encoder.
 */
@Composable
fun CameraScreen(
    modifier: Modifier = Modifier,
    viewModel: CameraViewModel = viewModel(factory = CameraViewModel.Factory),
) {
    val sceneModel: SceneViewModel = viewModel(factory = SceneViewModel.Factory)
    val collection by sceneModel.collection.collectAsStateWithLifecycle()
    val refreshing by sceneModel.refreshing.collectAsStateWithLifecycle()
    val refreshNotice by sceneModel.notice.collectAsStateWithLifecycle()
    val sceneStoreError by sceneModel.error.collectAsStateWithLifecycle()
    var showScenes by rememberSaveable { mutableStateOf(false) }
    val session = rememberStreamSession()
    DisposableEffect(session, sceneModel) {
        sceneModel.bind(session?.engine)
        onDispose { sceneModel.bind(null) }
    }
    DisposableEffect(session, viewModel) {
        if (session != null) viewModel.bind(session.engine, session.thermal)
        onDispose { viewModel.unbind() }
    }
    val editorState by viewModel.state.collectAsStateWithLifecycle()
    val destination by viewModel.destination.collectAsStateWithLifecycle()
    val thermal by viewModel.thermal.collectAsStateWithLifecycle()
    var showCameraSettings by rememberSaveable { mutableStateOf(false) }
    var showSetup by rememberSaveable { mutableStateOf(false) }
    var confirmStop by rememberSaveable { mutableStateOf(false) }
    var showText by rememberSaveable { mutableStateOf(false) }
    var showLogo by rememberSaveable { mutableStateOf(false) }
    val logoFailed by viewModel.logoFailed.collectAsStateWithLifecycle()
    // The system photo picker needs no storage permission.
    val pickLogo = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let { viewModel.pickLogo(it.toString()) }
    }
    val launchLogoPicker = {
        pickLogo.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }
    var fillPreview by rememberSaveable { mutableStateOf(true) }
    var previewOff by rememberSaveable { mutableStateOf(false) }
    if (showScenes && collection != null) {
        SceneEditor(
            collection = collection!!,
            dualCameraSupport = editorState?.dualCameraSupport ?: dev.mobs.scene.DualCameraSupport(),
            onSave = sceneModel::save, onDelete = sceneModel::delete, onClose = { showScenes = false },
        )
    }
    if (sceneStoreError != null) AlertDialog(
        onDismissRequest = sceneModel::dismissError, text = { Text(sceneStoreError.orEmpty()) },
        confirmButton = { TextButton(onClick = sceneModel::dismissError) { Text("OK") } },
    )
    val state = editorState
    if (state == null) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    // Keep the display on only while live. The window flag is released with the screen, so no
    // wake lock can outlive the editor; preview-only sessions may still sleep.
    val view = LocalView.current
    DisposableEffect(view, state.streamActive) {
        view.keepScreenOn = state.streamActive
        onDispose { view.keepScreenOn = false }
    }

    // Preview-off only applies while live; a stopped or failed stream brings the editor back.
    LaunchedEffect(state.streamActive) {
        if (!state.streamActive) previewOff = false
    }
    if (previewOff || state.heatMode.suspendPreview) {
        PreviewOffScreen(state, thermal, onWake = { previewOff = false }, onStop = viewModel::stopStream, modifier = modifier)
        return
    }

    val activity = LocalActivity.current
    DisposableEffect(activity) {
        val controller = activity?.window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose { controller?.show(WindowInsetsCompat.Type.systemBars()) }
    }
    LaunchedEffect(fillPreview, session) { viewModel.setPreviewFill(fillPreview) }

    val pinchCamera = state.scene?.sources?.firstOrNull { it.visible && it.opacity > 0f && it.kind in Scene.CAMERA_KINDS }
    val context = LocalContext.current
    val dual = state.scene?.cameras?.size == 2
    val pinchRange = remember(pinchCamera?.kind, dual, context) {
        pinchCamera?.let { dev.mobs.render.scenePinchZoomRange(context, it.kind, dual) } ?: (1f..1f)
    }
    val pinchSceneId = state.scene?.id
    val onPinch: ((Float, Boolean) -> Unit)? = if (pinchCamera == null || pinchSceneId == null || dual && pinchCamera.zoom < 1f) null else { scale, finished ->
        sceneModel.pinchZoom(pinchSceneId, pinchCamera.id, scale, pinchRange, finished)
    }

    Box(modifier = modifier.fillMaxSize().background(Color.Black).testTag("studio-fullscreen")) {
        // Controls are siblings of the preview. None of their sizes constrain its surface.
        if (state.profileError != null) {
            Column(Modifier.align(Alignment.Center).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                StudioGlyph(StudioIcon.CAMERA, null, StudioAccent)
                Text(state.profileError, style = MaterialTheme.typography.bodyMedium)
            }
        } else key(session?.engine) {
            EnginePreview(viewModel, state.video.fps, Modifier.fillMaxSize().testTag("studio-preview"), onPinch)
        }
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = .6f), Color.Transparent, Color.Transparent, Color.Black.copy(alpha = .7f)))))
        Box(Modifier.fillMaxSize().displayCutoutPadding().padding(16.dp)) {
            Column(Modifier.align(Alignment.TopStart).background(Color(0xB30C1018), RoundedCornerShape(16.dp)).padding(horizontal = 14.dp, vertical = 10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (state.streamActive) Text("● LIVE", color = Color(0xFFFF879B), style = MaterialTheme.typography.labelMedium)
                    Text(state.scene?.name ?: "Camera", style = MaterialTheme.typography.titleSmall, color = Color.White)
                }
                Text("${state.video.height}p · ${state.video.fps} fps${if (state.microphoneMuted) " · Mic muted" else ""}", style = MaterialTheme.typography.labelSmall, color = Color(0xFFBBC5D8))
            }
            Row(Modifier.align(Alignment.TopEnd).background(Color(0xB30C1018), RoundedCornerShape(16.dp)).padding(6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ConnectionStatus(connection = state.connection, stats = state.stats)
                StudioAction(StudioIcon.FRAME, if (fillPreview) "Fit entire output" else "Fill preview", active = !fillPreview, onClick = { fillPreview = !fillPreview })
                if (refreshing) Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp) }
                else StudioAction(StudioIcon.REFRESH, "Refresh scenes and sources", enabled = collection != null, onClick = sceneModel::refresh)
            }
            val notice = state.sceneError ?: state.recordingError ?: refreshNotice
            notice?.let {
                Text(it, Modifier.align(Alignment.TopCenter).padding(top = 76.dp).widthIn(max = 520.dp).background(Color.Black.copy(alpha = .8f), RoundedCornerShape(12.dp)).padding(12.dp),
                    color = if (state.sceneError != null || state.recordingError != null) Color(0xFFFFA1B3) else StudioAccent, style = MaterialTheme.typography.labelSmall)
            }
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        collection?.scenes?.forEach { scene ->
                            FilterChip(selected = state.scene?.id == scene.id, onClick = { sceneModel.activate(scene) }, label = { Text(scene.name) }, colors = androidx.compose.material3.FilterChipDefaults.filterChipColors(containerColor = Color(0xB30C1018), selectedContainerColor = Color(0xD93D456E)))
                        }
                    }
                    val camera = state.scene?.sources?.firstOrNull { it.visible && it.kind in Scene.CAMERA_KINDS }
                    if (camera != null) {
                        val context = LocalContext.current
                        val lenses = remember(camera.kind, state.scene?.cameras) { dev.mobs.render.sceneLensOptions(context, camera.kind, state.scene!!.cameras.size == 2) }
                        val feedback = state.cameraZoom[camera.id]
                        Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                            if (feedback?.confirmed != true || kotlin.math.abs(feedback.requested - camera.zoom) >= .03f) {
                                Text(if (feedback?.failed == true) "Camera at ${feedback.actual?.let(::lensLabel) ?: "unknown zoom"}"
                                    else "Switching to ${lensLabel(camera.zoom)}", color = Color.White, style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.background(Color.Black.copy(alpha = .65f), RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 4.dp))
                            }
                            Row(Modifier.horizontalScroll(rememberScrollState()).background(Color(0xB30C1018), RoundedCornerShape(30.dp)).padding(horizontal = 6.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                lenses.forEach { ratio ->
                                    TextButton(onClick = { state.scene?.let { sceneModel.save(it.update(camera.copy(zoom = ratio)), true) } }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                                        Text(lensLabel(ratio), color = if (feedback?.actual?.let { kotlin.math.abs(it - ratio) < .03f } == true) StudioAccent else Color.White)
                                    }
                                }
                            }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.background(Color(0xB30C1018), RoundedCornerShape(18.dp)).padding(6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        StudioAction(StudioIcon.MIC, stringResource(if (state.microphoneMuted) R.string.action_unmute else R.string.action_mute), active = state.microphoneMuted, off = state.microphoneMuted, onClick = viewModel::toggleMicrophone)
                        StudioAction(StudioIcon.CAMERA, "Camera controls", onClick = { showCameraSettings = true })
                        StudioAction(StudioIcon.FLIP, "Switch camera", onClick = {
                            val scene = state.scene
                            val cameras = scene?.sources?.filter { it.kind in Scene.CAMERA_KINDS }.orEmpty()
                            if (scene != null && cameras.size == 1) {
                                val camera = cameras.single()
                                val kind = if (camera.kind == SourceKind.REAR_CAMERA) SourceKind.FRONT_CAMERA else SourceKind.REAR_CAMERA
                                sceneModel.save(scene.update(camera.copy(kind = kind, name = if (kind == SourceKind.FRONT_CAMERA) "Front camera" else "Rear camera", zoom = 1f)), true)
                            } else showCameraSettings = true
                        })
                        StudioAction(StudioIcon.LAYERS, "Scene editor", enabled = collection != null, onClick = { showScenes = true })
                        StudioAction(StudioIcon.SETTINGS, stringResource(R.string.action_stream_setup), enabled = !state.streamActive && destination != SavedDestination.Loading, onClick = { showSetup = true })
                        if (state.streamActive) StudioAction(StudioIcon.EYE, stringResource(R.string.action_preview_off), onClick = { previewOff = true })
                        else {
                            val context = LocalContext.current
                            val shareScope = rememberCoroutineScope()
                            var sharing by remember { mutableStateOf(false) }
                            StudioAction(StudioIcon.SHARE, "Share session logs", enabled = !sharing, onClick = {
                                shareScope.launch {
                                    sharing = true
                                    try { shareSessionLogs(context) } finally { sharing = false }
                                }
                            })
                        }
                    }
                    Column(Modifier.weight(1f).background(Color(0x990C1018), RoundedCornerShape(12.dp)).padding(8.dp)) {
                        ThermalStatus(thermal)
                        if (state.heatMode == dev.mobs.stream.HeatMode.WARM) Text(stringResource(R.string.heat_warm), color = Color.White)
                    }
                    when {
                        state.streamActive -> Button(onClick = { confirmStop = true }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE95573)), shape = RoundedCornerShape(16.dp), contentPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp)) { Text(stringResource(R.string.action_stop_stream)) }
                        destination is SavedDestination.Ready -> Button(onClick = viewModel::goLive, enabled = state.profileError == null && !thermal.level.isCritical() && state.heatMode != dev.mobs.stream.HeatMode.COOLDOWN, shape = RoundedCornerShape(16.dp), contentPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp)) { Text(stringResource(R.string.action_go_live)) }
                        else -> Button(onClick = { showSetup = true }, enabled = destination == SavedDestination.Missing, shape = RoundedCornerShape(16.dp), contentPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp)) { Text(stringResource(R.string.action_set_up_stream)) }
                    }
                }
            }
        }
    }

    LaunchedEffect(refreshNotice) {
        if (refreshNotice != null) { delay(3_000); sceneModel.dismissNotice() }
    }
    if (showCameraSettings) AlertDialog(
        onDismissRequest = { showCameraSettings = false },
        title = { Text("Camera") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val scene = state.scene
                val cameras = scene?.sources?.filter { it.kind in Scene.CAMERA_KINDS }.orEmpty()
                if (cameras.isEmpty()) Text("Add a camera in the scene editor.")
                cameras.forEach { source ->
                    CameraSourceControls(source, scene!!.cameras.size == 2, feedback = state.cameraZoom[source.id], live = true, onChange = { sceneModel.save(scene.update(it), true) })
                    if (cameras.size == 1) OutlinedButton(onClick = {
                        val kind = if (source.kind == SourceKind.REAR_CAMERA) SourceKind.FRONT_CAMERA else SourceKind.REAR_CAMERA
                        sceneModel.save(scene.update(source.copy(kind = kind, name = if (kind == SourceKind.FRONT_CAMERA) "Front camera" else "Rear camera", zoom = 1f)), true)
                    }) { Text(if (source.kind == SourceKind.REAR_CAMERA) "Switch to front camera" else "Switch to rear camera") }
                }
            }
        },
        confirmButton = { TextButton(onClick = { showCameraSettings = false }) { Text("Done") } },
    )

    if (showSetup) {
        StreamSetupDialog(
            saved = (destination as? SavedDestination.Ready)?.destination,
            onSave = { server, key ->
                viewModel.saveDestination(server, key).also { if (it == null) showSetup = false }
            },
            onDismiss = { showSetup = false },
        )
    }
    val logo = state.logoOverlay
    if (showLogo && logo != null) {
        LogoDialog(
            anchor = logo.anchor,
            onAnchor = viewModel::setLogoAnchor,
            onReplace = {
                showLogo = false
                launchLogoPicker()
            },
            onRemove = {
                viewModel.removeLogo()
                showLogo = false
            },
            onDismiss = { showLogo = false },
        )
    }

    if (logoFailed) {
        AlertDialog(
            onDismissRequest = viewModel::dismissLogoFailure,
            text = { Text(stringResource(R.string.logo_unreadable)) },
            confirmButton = {
                TextButton(onClick = viewModel::dismissLogoFailure) { Text(stringResource(R.string.logo_done)) }
            },
        )
    }

    if (showText) {
        TextOverlayDialog(
            current = state.textOverlay,
            onApply = { text, anchor ->
                viewModel.setTextOverlay(text, anchor)
                showText = false
            },
            onDismiss = { showText = false },
        )
    }
    if (confirmStop && state.streamActive) {
        AlertDialog(
            onDismissRequest = { confirmStop = false },
            title = { Text(stringResource(R.string.stop_title)) },
            text = { Text(stringResource(R.string.stop_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmStop = false
                    viewModel.stopStream()
                }) { Text(stringResource(R.string.stop_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmStop = false }) { Text(stringResource(R.string.stop_cancel)) }
            },
        )
    }
}

/**
 * Transport state only. "Connected" means YouTube's ingest accepted the connection, not that the
 * broadcast is public; that is controlled in YouTube Studio until sign-in lands.
 */
@Composable
private fun ConnectionStatus(connection: ConnectionState, stats: StreamStats, modifier: Modifier = Modifier) {
    Column(modifier = modifier, horizontalAlignment = Alignment.End) {
        val color = when (connection) {
            is ConnectionState.Connected -> MaterialTheme.colorScheme.primary
            is ConnectionState.Failed, is ConnectionState.Disconnected -> MaterialTheme.colorScheme.error
            else -> Color.White
        }
        Text(
            text = when (connection) {
                is ConnectionState.Idle -> stringResource(R.string.connection_offline)
                is ConnectionState.Connecting -> stringResource(R.string.connection_connecting, connection.server)
                is ConnectionState.Connected -> stringResource(
                    R.string.connection_connected,
                    formatElapsed(rememberElapsedMs(stats.connectedSinceElapsedMs)),
                )
                is ConnectionState.Reconnecting ->
                    stringResource(R.string.connection_reconnecting, connection.attempt, connection.reason)
                is ConnectionState.Failed -> stringResource(R.string.connection_failed, connection.reason)
                is ConnectionState.Disconnected -> stringResource(R.string.connection_disconnected)
            },
            color = color,
            style = MaterialTheme.typography.labelLarge,
        )
        if (connection is ConnectionState.Connected || connection is ConnectionState.Reconnecting) {
            Text(
                text = pluralStringResource(
                    R.plurals.stats_line,
                    stats.droppedVideoFrames.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                    stats.bitrateBps / 1_000_000.0,
                    stats.droppedVideoFrames,
                    stats.targetBitrateBps / 1_000_000.0,
                    stats.outputFps,
                ),
                color = Color.White,
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

/**
 * The stream keeps encoding while the preview is detached, so no frames are drawn for the display,
 * and the window drops to minimum brightness. A black UI alone would save far less: the plan
 * requires that preview presentation itself stops. Tapping anywhere restores the editor.
 */
@Composable
private fun PreviewOffScreen(
    state: StreamEngineState,
    thermal: ThermalState,
    onWake: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val activity = LocalActivity.current
    DisposableEffect(activity) {
        val window = activity?.window
        window?.attributes = window?.attributes?.apply { screenBrightness = PREVIEW_OFF_BRIGHTNESS }
        onDispose {
            window?.attributes = window?.attributes?.apply {
                screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            }
        }
    }
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable(onClick = onWake),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            ConnectionStatus(connection = state.connection, stats = state.stats)
            ThermalStatus(thermal)
            if (state.heatMode.suspendPreview) Text(
                stringResource(if (state.heatMode == dev.mobs.stream.HeatMode.COOLDOWN)
                    R.string.heat_cooldown else R.string.heat_hot), color = Color.White,
                modifier = Modifier.padding(16.dp),
            )
            if (!state.heatMode.suspendPreview) Text(
                stringResource(R.string.preview_off_hint),
                color = Color.Gray,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(top = 12.dp),
            )
            if (state.streamActive) TextButton(onClick = onStop) {
                Text(stringResource(R.string.action_stop_stream), color = Color.White)
            }
        }
    }
}

/**
 * Sends the newest sessions' CSVs and logs through the share sheet, for phones that cannot be
 * attached to a computer. Logs are redacted when written; read access is granted per file.
 */
private suspend fun shareSessionLogs(context: Context) {
    val files = withContext(Dispatchers.IO) {
        SessionRecorder.newestSessionFiles(StreamService.sessionsDirectory(context), SHARED_SESSIONS) +
            CrashDiagnostics.collect(context)
    }
    if (files.isEmpty()) {
        Toast.makeText(context, R.string.share_logs_none, Toast.LENGTH_SHORT).show()
        return
    }
    val uris = files.mapTo(ArrayList()) { FileProvider.getUriForFile(context, "${context.packageName}.files", it) }
    val send = Intent(Intent.ACTION_SEND_MULTIPLE)
        .setType("text/plain")
        .putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(send, context.getString(R.string.action_share_logs)))
}

private const val SHARED_SESSIONS = 3

/** Low enough to save display power while the status text stays readable indoors. */
private const val PREVIEW_OFF_BRIGHTNESS = 0.02f

/** Always visible, so heat is noticed before it forces a stop. Headroom 1.0 means severe throttling. */
@Composable
private fun ThermalStatus(thermal: ThermalState) {
    val level = stringResource(
        when (thermal.level) {
            ThermalLevel.NONE -> R.string.thermal_none
            ThermalLevel.LIGHT -> R.string.thermal_light
            ThermalLevel.MODERATE -> R.string.thermal_moderate
            ThermalLevel.SEVERE -> R.string.thermal_severe
            ThermalLevel.CRITICAL -> R.string.thermal_critical
            ThermalLevel.EMERGENCY -> R.string.thermal_emergency
            ThermalLevel.SHUTDOWN -> R.string.thermal_shutdown
        },
    )
    Text(
        text = thermal.headroom?.let { stringResource(R.string.thermal_with_headroom, level, it) }
            ?: stringResource(R.string.thermal_status, level),
        color = if (thermal.level >= ThermalLevel.SEVERE) MaterialTheme.colorScheme.error else Color.White,
        style = MaterialTheme.typography.labelMedium,
    )
}

/** Ticks once a second while [sinceElapsedMs] is set; only this text recomposes. */
@Composable
private fun rememberElapsedMs(sinceElapsedMs: Long?): Long {
    val elapsed by produceState(0L, sinceElapsedMs) {
        if (sinceElapsedMs == null) return@produceState
        while (true) {
            value = SystemClock.elapsedRealtime() - sinceElapsedMs
            delay(1_000)
        }
    }
    return elapsed
}

/**
 * The stream key is held in plain `remember` state, not `rememberSaveable`, so it never enters
 * the saved-instance-state bundle.
 */
@Composable
private fun StreamSetupDialog(
    saved: StreamDestination?,
    onSave: (server: String, streamKey: String) -> DestinationProblem?,
    onDismiss: () -> Unit,
) {
    var server by remember { mutableStateOf(saved?.server ?: StreamDestination.YOUTUBE_RTMPS_SERVER) }
    var streamKey by remember { mutableStateOf(saved?.streamKey.orEmpty()) }
    var keyVisible by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<DestinationProblem?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.setup_title)) },
        text = {
            // Landscape-locked: with the keyboard up the dialog has little height, so let it scroll
            // instead of squeezing the fields.
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(stringResource(R.string.setup_body), style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    value = server,
                    onValueChange = { server = it; problem = null },
                    label = { Text(stringResource(R.string.setup_server)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = streamKey,
                    onValueChange = { streamKey = it; problem = null },
                    label = { Text(stringResource(R.string.setup_key)) },
                    singleLine = true,
                    visualTransformation =
                        if (keyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                    modifier = Modifier.fillMaxWidth(),
                )
                TextButton(onClick = { keyVisible = !keyVisible }) {
                    Text(stringResource(if (keyVisible) R.string.setup_hide_key else R.string.setup_show_key))
                }
                problem?.let {
                    Text(
                        stringResource(it.message()),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { problem = onSave(server, streamKey) }) {
                Text(stringResource(R.string.setup_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.setup_cancel)) }
        },
    )
}

@Composable
private fun TextOverlayDialog(
    current: TextOverlay?,
    onApply: (text: String, anchor: OverlayAnchor) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by rememberSaveable { mutableStateOf(current?.text.orEmpty()) }
    var anchor by rememberSaveable { mutableStateOf(current?.anchor ?: OverlayAnchor.TOP_CENTER) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.text_title)) },
        text = {
            // Landscape-locked: with the keyboard up the dialog has little height, so let it scroll
            // instead of squeezing the fields.
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(stringResource(R.string.text_body), style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text(stringResource(R.string.text_label)) },
                    maxLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
                AnchorPicker(selected = anchor, onSelect = { anchor = it })
            }
        },
        confirmButton = {
            TextButton(onClick = { onApply(text, anchor) }, enabled = text.isNotBlank()) {
                Text(stringResource(R.string.text_apply))
            }
        },
        dismissButton = {
            Row {
                if (current != null) {
                    TextButton(onClick = { onApply("", anchor) }) { Text(stringResource(R.string.text_remove)) }
                }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.setup_cancel)) }
            }
        },
    )
}

/** Changes apply immediately, including while live, so the dialog has no Apply step. */
@Composable
private fun LogoDialog(
    anchor: OverlayAnchor,
    onAnchor: (OverlayAnchor) -> Unit,
    onReplace: () -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.logo_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                AnchorPicker(selected = anchor, onSelect = onAnchor)
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.logo_done)) }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onRemove) { Text(stringResource(R.string.text_remove)) }
                TextButton(onClick = onReplace) { Text(stringResource(R.string.logo_replace)) }
            }
        },
    )
}

@Composable
private fun AnchorPicker(selected: OverlayAnchor, onSelect: (OverlayAnchor) -> Unit) {
    OverlayAnchor.entries.chunked(3).forEach { row ->
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            row.forEach { option ->
                FilterChip(
                    selected = selected == option,
                    onClick = { onSelect(option) },
                    label = { Text(stringResource(option.label())) },
                )
            }
        }
    }
}

private fun OverlayAnchor.label() = when (this) {
    OverlayAnchor.TOP_LEFT -> R.string.anchor_top_left
    OverlayAnchor.TOP_CENTER -> R.string.anchor_top_center
    OverlayAnchor.TOP_RIGHT -> R.string.anchor_top_right
    OverlayAnchor.BOTTOM_LEFT -> R.string.anchor_bottom_left
    OverlayAnchor.BOTTOM_CENTER -> R.string.anchor_bottom_center
    OverlayAnchor.BOTTOM_RIGHT -> R.string.anchor_bottom_right
}

private fun DestinationProblem.message() = when (this) {
    DestinationProblem.SERVER_SCHEME -> R.string.setup_error_scheme
    DestinationProblem.SERVER_MALFORMED -> R.string.setup_error_malformed
    DestinationProblem.SERVER_APP_MISSING -> R.string.setup_error_app
    DestinationProblem.KEY_BLANK -> R.string.setup_error_key_blank
    DestinationProblem.KEY_WHITESPACE -> R.string.setup_error_key_whitespace
}

/**
 * Hosts the engine's output surface. The surface lifecycle drives attach/detach so the camera is
 * released whenever the screen is gone, and per-frame work never travels through Compose state.
 */
@Composable
private fun EnginePreview(
    viewModel: CameraViewModel,
    fps: Int,
    modifier: Modifier = Modifier,
    onPinch: ((Float, Boolean) -> Unit)? = null,
) {
    val latestPinch by rememberUpdatedState(onPinch)
    AndroidView(
        modifier = modifier,
        factory = { context ->
            SurfaceView(context).apply {
                setOnTouchListener(PreviewPinchZoom(context) { latestPinch })
                holder.addCallback(
                    object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) {
                            // Lets a 90/120 Hz display settle on a multiple of the camera rate
                            // instead of refreshing faster than the preview ever changes.
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                holder.surface.setFrameRate(
                                    fps.toFloat(),
                                    Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE,
                                )
                            }
                        }

                        override fun surfaceChanged(
                            holder: SurfaceHolder,
                            format: Int,
                            width: Int,
                            height: Int,
                        ) {
                            viewModel.updatePreview(holder.surface, width, height)
                        }

                        override fun surfaceDestroyed(holder: SurfaceHolder) {
                            viewModel.detachPreview()
                        }
                    },
                )
            }
        },
    )
    DisposableEffect(viewModel) {
        onDispose { viewModel.detachPreview() }
    }
}

@Composable
private fun statusLine(camera: CameraStatus, width: Int, height: Int, fps: Int): String = when (camera) {
    is CameraStatus.Open -> stringResource(R.string.status_camera_open, width, height, fps)
    is CameraStatus.Closed -> stringResource(R.string.status_camera_closed)
    is CameraStatus.Disconnected -> stringResource(R.string.status_camera_disconnected)
    is CameraStatus.Error -> stringResource(R.string.status_camera_error, camera.reason)
}
