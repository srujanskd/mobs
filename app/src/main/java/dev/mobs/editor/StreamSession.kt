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
import dev.mobs.stream.BoundSession
import dev.mobs.stream.StreamService

/**
 * Binds the editor to the foreground service that owns the media session and returns the session,
 * or null until the binding completes. The screen only ever sees the engine interface.
 *
 * Call only once the camera and microphone permissions are granted: Android refuses to start a
 * camera/microphone foreground service without them.
 */
@Composable
fun rememberStreamSession(): BoundSession? {
    val context = LocalContext.current
    var session by remember { mutableStateOf<BoundSession?>(null) }

    DisposableEffect(context) {
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                session = (binder as StreamService.LocalBinder).session
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                session = null
            }
        }
        StreamService.start(context)
        context.bindService(Intent(context, StreamService::class.java), connection, 0)
        onDispose {
            context.unbindService(connection)
            session = null
        }
    }
    return session
}
