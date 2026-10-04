package dev.mobs.editor

import android.os.SystemClock
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.mobs.R
import dev.mobs.render.OverlayAnchor
import dev.mobs.render.TextOverlay
import dev.mobs.stream.CameraFacing
import dev.mobs.stream.CameraStatus
import dev.mobs.stream.ConnectionState
import dev.mobs.stream.DestinationProblem
import dev.mobs.stream.StreamDestination
import dev.mobs.stream.StreamStats
import dev.mobs.stream.ThermalLevel
import dev.mobs.stream.ThermalState
import dev.mobs.stream.isCritical
import kotlinx.coroutines.delay

/**
 * Proof-of-concept live screen: the composed camera output, mute and camera switch, stream-key
 * setup, and Go live / Stop. The preview is the same composition that feeds the encoder.
 */
@Composable
fun CameraScreen(
    modifier: Modifier = Modifier,
    viewModel: CameraViewModel = viewModel(factory = CameraViewModel.Factory),
) {
    val session = rememberStreamSession()
    DisposableEffect(session, viewModel) {
        if (session != null) viewModel.bind(session.engine, session.thermal)
        onDispose { viewModel.unbind() }
    }
    val editorState by viewModel.state.collectAsStateWithLifecycle()
    val destination by viewModel.destination.collectAsStateWithLifecycle()
    val thermal by viewModel.thermal.collectAsStateWithLifecycle()
    var showSetup by rememberSaveable { mutableStateOf(false) }
    var confirmStop by rememberSaveable { mutableStateOf(false) }
    var showText by rememberSaveable { mutableStateOf(false) }
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

    Box(modifier = modifier.fillMaxSize().background(Color.Black)) {
        val error = state.profileError
        if (error != null) {
            Column(
                modifier = Modifier.fillMaxSize().padding(32.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    stringResource(
                        R.string.profile_unsupported,
                        state.video.width,
                        state.video.height,
                        state.video.fps,
                    ),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(error, style = MaterialTheme.typography.bodyMedium)
            }
        } else {
            key(session?.engine) {
                EnginePreview(
                    viewModel = viewModel,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .fillMaxHeight()
                        .aspectRatio(state.video.width.toFloat() / state.video.height.toFloat()),
                )
            }
        }

        Column(modifier = Modifier.align(Alignment.TopStart).padding(16.dp)) {
            Text(
                text = statusLine(state.camera, state.video.width, state.video.height, state.video.fps),
                color = Color.White,
                style = MaterialTheme.typography.labelLarge,
            )
            ThermalStatus(thermal)
        }

        ConnectionStatus(
            connection = state.connection,
            stats = state.stats,
            modifier = Modifier.align(Alignment.TopEnd).padding(16.dp),
        )

        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
        ) {
            Button(onClick = viewModel::toggleMicrophone) {
                Text(
                    stringResource(
                        if (state.microphoneMuted) R.string.action_unmute else R.string.action_mute,
                    ),
                )
            }
            Button(onClick = viewModel::switchCamera) {
                Text(
                    stringResource(
                        when (state.cameraFacing) {
                            CameraFacing.REAR -> R.string.action_camera_front
                            CameraFacing.FRONT -> R.string.action_camera_rear
                        },
                    ),
                )
            }
            OutlinedButton(onClick = { showText = true }) {
                Text(stringResource(R.string.action_text_overlay))
            }
            OutlinedButton(
                onClick = { showSetup = true },
                enabled = !state.streamActive && destination != SavedDestination.Loading,
            ) {
                Text(stringResource(R.string.action_stream_setup))
            }
            when {
                state.streamActive -> Button(
                    onClick = { confirmStop = true },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) {
                    Text(stringResource(R.string.action_stop_stream))
                }
                destination is SavedDestination.Ready -> Button(
                    onClick = viewModel::goLive,
                    enabled = state.profileError == null && !thermal.level.isCritical(),
                ) {
                    Text(stringResource(R.string.action_go_live))
                }
                else -> Button(
                    onClick = { showSetup = true },
                    enabled = destination == SavedDestination.Missing,
                ) {
                    Text(stringResource(R.string.action_set_up_stream))
                }
            }
        }
    }

    if (showSetup) {
        StreamSetupDialog(
            saved = (destination as? SavedDestination.Ready)?.destination,
            onSave = { server, key ->
                viewModel.saveDestination(server, key).also { if (it == null) showSetup = false }
            },
            onDismiss = { showSetup = false },
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
                OverlayAnchor.entries.chunked(3).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { option ->
                            FilterChip(
                                selected = anchor == option,
                                onClick = { anchor = option },
                                label = { Text(stringResource(option.label())) },
                            )
                        }
                    }
                }
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
private fun EnginePreview(viewModel: CameraViewModel, modifier: Modifier = Modifier) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            SurfaceView(context).apply {
                holder.addCallback(
                    object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) = Unit

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
