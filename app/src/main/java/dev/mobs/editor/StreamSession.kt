package dev.mobs.editor

import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import dev.mobs.stream.ConnectionState
import dev.mobs.stream.StreamEngine
import dev.mobs.stream.StreamService

/**
 * Binds the editor to the foreground service that owns the media session and returns its engine,
 * or null until the binding completes. The screen only ever sees the [StreamEngine] interface.
 *
 * Call only once the camera and microphone permissions are granted: Android refuses to start a
 * camera/microphone foreground service without them.
 */
@Composable
fun rememberStreamEngine(): StreamEngine? {
    val context = LocalContext.current
    var engine by remember { mutableStateOf<StreamEngine?>(null) }

    DisposableEffect(context) {
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                engine = (binder as StreamService.LocalBinder).service.engine
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                engine = null
            }
        }
        StreamService.start(context)
        context.bindService(Intent(context, StreamService::class.java), connection, 0)
        onDispose {
            // A live broadcast must outlive the screen; a session that is not on air should not
            // keep a foreground service running behind it.
            val onAir = when (engine?.state?.value?.connection) {
                is ConnectionState.Connecting, is ConnectionState.Connected -> true
                else -> false
            }
            context.unbindService(connection)
            engine = null
            if (!onAir) StreamService.stop(context)
        }
    }
    return engine
}
