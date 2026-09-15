package dev.mobs.stream

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import dev.mobs.MainActivity
import dev.mobs.R

/**
 * Owns the media session for as long as the broadcast lasts, independently of any screen. The
 * editor binds to read state and issue commands; it never holds the engine itself.
 *
 * Android only allows camera and microphone foreground service types to start from a visible,
 * authorized user action, so the caller must hold the camera and microphone permissions before
 * calling [start].
 */
class StreamService : Service() {

    private var engineOrNull: RootEncoderStreamEngine? = null

    val engine: StreamEngine
        get() = requireNotNull(engineOrNull) { "Service is not created" }

    inner class LocalBinder : Binder() {
        val service: StreamService get() = this@StreamService
    }

    override fun onCreate() {
        super.onCreate()
        val engine = RootEncoderStreamEngine(this)
        engineOrNull = engine
        // Configure capture and both encoders once, before any preview attaches. A rejected
        // profile is reported through engine state rather than silently downgraded.
        engine.prepare()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundSession()
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = LocalBinder()

    override fun onDestroy() {
        engineOrNull?.release()
        engineOrNull = null
        super.onDestroy()
    }

    private fun startForegroundSession() {
        createChannel()
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notification: Notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(getString(R.string.stream_notification_title))
            .setContentText(getString(R.string.stream_notification_text))
            .setContentIntent(openApp)
            .setOngoing(true)
            .build()
        // The camera and microphone service types only exist from API 30. On Android 10 the
        // service still runs, without the typed camera/microphone declaration.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.stream_notification_channel),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    companion object {
        private const val CHANNEL_ID = "stream_session"
        private const val NOTIFICATION_ID = 1

        fun start(context: Context) {
            context.startForegroundService(Intent(context, StreamService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, StreamService::class.java))
        }
    }
}
