package dev.mobs.editor

import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mobs.R
import dev.mobs.stream.CameraFacing
import dev.mobs.stream.CameraStatus
import dev.mobs.stream.StreamEngine

/**
 * Proof-of-concept live screen: the composed camera output, a mute toggle, and a camera switch.
 * The preview is the same composition that will feed the encoder, so nothing here is throwaway
 * when streaming is wired up.
 */
@Composable
fun CameraScreen(modifier: Modifier = Modifier) {
    val engine = rememberStreamEngine()
    if (engine == null) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }
    val state by engine.state.collectAsStateWithLifecycle()

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
            EnginePreview(
                engine = engine,
                modifier = Modifier
                    .align(Alignment.Center)
                    .fillMaxHeight()
                    .aspectRatio(state.video.width.toFloat() / state.video.height.toFloat()),
            )
        }

        Text(
            text = statusLine(state.camera, state.video.width, state.video.height, state.video.fps),
            color = Color.White,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.align(Alignment.TopStart).padding(16.dp),
        )

        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
        ) {
            Button(onClick = { engine.setMicrophoneMuted(!state.microphoneMuted) }) {
                Text(
                    stringResource(
                        if (state.microphoneMuted) R.string.action_unmute else R.string.action_mute,
                    ),
                )
            }
            Button(onClick = engine::switchCamera) {
                Text(
                    stringResource(
                        when (state.cameraFacing) {
                            CameraFacing.REAR -> R.string.action_camera_front
                            CameraFacing.FRONT -> R.string.action_camera_rear
                        },
                    ),
                )
            }
        }
    }
}

/**
 * Hosts the engine's output surface. The surface lifecycle drives attach/detach so the camera is
 * released whenever the screen is gone, and per-frame work never travels through Compose state.
 */
@Composable
private fun EnginePreview(engine: StreamEngine, modifier: Modifier = Modifier) {
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
                            if (engine.state.value.previewing) {
                                engine.setPreviewSize(width, height)
                            } else {
                                engine.attachPreview(holder.surface, width, height)
                            }
                        }

                        override fun surfaceDestroyed(holder: SurfaceHolder) {
                            engine.detachPreview()
                        }
                    },
                )
            }
        },
    )
    DisposableEffect(engine) {
        onDispose { engine.detachPreview() }
    }
}

@Composable
private fun statusLine(camera: CameraStatus, width: Int, height: Int, fps: Int): String = when (camera) {
    is CameraStatus.Open -> stringResource(R.string.status_camera_open, width, height, fps)
    is CameraStatus.Closed -> stringResource(R.string.status_camera_closed)
    is CameraStatus.Disconnected -> stringResource(R.string.status_camera_disconnected)
    is CameraStatus.Error -> stringResource(R.string.status_camera_error, camera.reason)
}
