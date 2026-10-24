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
import android.os.SystemClock
import dev.mobs.MainActivity
import dev.mobs.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * Owns the media session for as long as the broadcast lasts, independently of any screen. The
 * editor binds to read state and issue commands; it never holds the engine itself.
 *
 * Android only allows camera and microphone foreground service types to start from a visible,
 * authorized user action, so the caller must hold the camera and microphone permissions before
 * calling [start].
 */
class StreamService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var sessionLifetime: StreamSessionLifetime
    private var engineOrNull: RootEncoderStreamEngine? = null
    private lateinit var thermalMonitor: ThermalMonitor
    private var lastStartId = 0

    val engine: StreamEngine
        get() = requireNotNull(engineOrNull) { "Service is not created" }

    inner class LocalBinder : Binder() {
        val session: BoundSession get() = BoundSession(engine, thermalMonitor.state)
    }

    override fun onCreate() {
        super.onCreate()
        val engine = RootEncoderStreamEngine(this)
        engineOrNull = engine
        // Configure capture and both encoders once, before any preview attaches. A rejected
        // profile is reported through engine state rather than silently downgraded.
        engine.prepare()
        // Stop only up to the latest start request. A recreated editor may start the service again
        // between the old editor's unbind and this stop, and its flag-0 binding would not keep the
        // service alive; a bare stopSelf() would release the engine out from under it.
        sessionLifetime = StreamSessionLifetime(engine, serviceScope) { stopSelfResult(lastStartId) }
        thermalMonitor = ThermalMonitor(this).also { it.start(serviceScope) }
        ThermalGuard(engine, thermalMonitor.state, serviceScope)
        SessionRecorder(
            directory = sessionsDirectory(this),
            engine = engine,
            thermal = thermalMonitor.state,
            battery = BatteryReader(this)::sample,
            scope = serviceScope,
            io = Dispatchers.IO,
            elapsedMs = SystemClock::elapsedRealtime,
            nowMs = System::currentTimeMillis,
            logCapture = LogcatCapture(engine::streamKeyForRedaction),
            memory = ::processMemorySample,
            onFailure = engine::recordingFailed,
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        startForegroundSession()
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder {
        sessionLifetime.onEditorBound()
        return LocalBinder()
    }

    override fun onRebind(intent: Intent?) {
        sessionLifetime.onEditorBound()
        super.onRebind(intent)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        sessionLifetime.onEditorUnbound()
        return true
    }

    override fun onDestroy() {
        serviceScope.cancel()
        thermalMonitor.stop()
        engineOrNull?.release()
        engineOrNull = null
        super.onDestroy()
    }

    private fun startForegroundSession() {
        createChannel()
        val openApp = PendingIntent.getActivity(
            this,
            0,
            // Match the launcher intent so a tap resumes the existing task. A second editor
            // instance would share the engine's single preview and detach it on its way out.
            Intent(this, MainActivity::class.java)
                .setAction(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER),
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
        private const val SESSIONS_DIR = "sessions"

        /** App-specific external storage, so `adb pull` reaches the files without root. */
        fun sessionsDirectory(context: Context) =
            File(context.getExternalFilesDir(null) ?: context.filesDir, SESSIONS_DIR)

        fun start(context: Context) {
            context.startForegroundService(Intent(context, StreamService::class.java))
        }
    }
}

/** What an editor receives from the service: the media engine and the device's thermal state. */
class BoundSession(val engine: StreamEngine, val thermal: StateFlow<ThermalState>)
