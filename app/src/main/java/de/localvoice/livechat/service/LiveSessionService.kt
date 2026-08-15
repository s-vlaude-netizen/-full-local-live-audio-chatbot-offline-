package de.localvoice.livechat.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import de.localvoice.livechat.LiveChatApplication
import de.localvoice.livechat.MainActivity
import de.localvoice.livechat.R
import de.localvoice.livechat.session.LiveState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Haelt den Live-Modus am Leben, wenn der Bildschirm aus ist oder die App im
 * Hintergrund liegt - genau der Fall, fuer den das Ganze gedacht ist.
 *
 * Das Gespraech selbst laeuft im [de.localvoice.livechat.session.LiveSessionController]
 * der Anwendung; der Dienst zeigt nur den Zustand an und verhindert, dass das
 * System den Prozess einsammelt.
 */
class LiveSessionService : Service() {

    private val scope = CoroutineScope(SupervisorJob())
    private var watcher: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            controller().stop()
            stopSelf()
            return START_NOT_STICKY
        }

        startInForeground(getString(R.string.app_name), "Wird gestartet")

        if (watcher == null) {
            watcher = scope.launch {
                controller().state.collectLatest { state ->
                    if (state == LiveState.IDLE) {
                        stopForegroundCompat()
                        stopSelf()
                    } else {
                        notify(label(state), controller().statusDetail.value)
                    }
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        watcher = null
        scope.cancel()
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Ohne Oberflaeche soll auch nicht weiter zugehoert werden.
        controller().stop()
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    private fun controller() = (application as LiveChatApplication).container.liveSession

    private fun label(state: LiveState): String = when (state) {
        LiveState.IDLE -> "Bereit"
        LiveState.PREPARING -> "Wird vorbereitet"
        LiveState.LISTENING -> "Hoert zu"
        LiveState.THINKING -> "Denkt nach"
        LiveState.SPEAKING -> "Liest vor"
    }

    private fun startInForeground(title: String, text: String) {
        val notification = buildNotification(title, text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun notify(title: String, text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager?.notify(NOTIFICATION_ID, buildNotification(title, text))
    }

    private fun buildNotification(title: String, text: String): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, LiveSessionService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text.ifEmpty { getString(R.string.app_name) })
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(open)
            .addAction(android.R.drawable.ic_media_pause, "Beenden", stop)
            .build()
    }

    private fun stopForegroundCompat() {
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notification_channel_description)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "live_session"
        private const val NOTIFICATION_ID = 42
        private const val ACTION_STOP = "de.localvoice.livechat.action.STOP"

        fun start(context: Context) {
            val intent = Intent(context, LiveSessionService::class.java)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, LiveSessionService::class.java))
        }
    }
}
