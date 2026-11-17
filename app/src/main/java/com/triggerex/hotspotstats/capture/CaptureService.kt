package com.triggerex.hotspotstats.capture

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
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.triggerex.hotspotstats.R
import com.triggerex.hotspotstats.ui.Format
import com.triggerex.hotspotstats.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch

/**
 * Keeps the process in the foreground while [CaptureManager] captures, so the capture
 * continues with the app in the background. Shows live counters and a Stop action.
 */
class CaptureService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var observing = false

    override fun onBind(intent: Intent?): IBinder? = null

    @OptIn(FlowPreview::class)
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        CaptureManager.init(this)
        if (intent?.action == ACTION_STOP) {
            CaptureManager.stop()
            return START_NOT_STICKY
        }
        createChannel()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(CaptureManager.state.value), type)
        if (!observing) {
            observing = true
            scope.launch {
                CaptureManager.state.sample(1_000).collect { state ->
                    if (state == null || !state.running) {
                        ServiceCompat.stopForeground(this@CaptureService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    } else {
                        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(state))
                    }
                }
            }
        }
        // Nothing to resume after the process dies: the root helper has exited with it.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun createChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "Packet capture", NotificationManager.IMPORTANCE_LOW)
        channel.description = "Shown while a packet capture is running"
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun notification(state: CaptureState?): android.app.Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, CaptureService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val text = state?.let {
            "${it.packets} packets · ${Format.bytes(it.fileBytes)} · ${Format.duration(it.elapsedMs / 1000)}"
        } ?: "Starting…"
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_capture)
            .setContentTitle(if (state?.stopping == true) "Stopping capture…" else "Capturing hotspot traffic")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "capture"
        private const val NOTIFICATION_ID = 42
        private const val ACTION_STOP = "com.triggerex.hotspotstats.STOP_CAPTURE"

        fun startTracking(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, CaptureService::class.java))
        }
    }
}
