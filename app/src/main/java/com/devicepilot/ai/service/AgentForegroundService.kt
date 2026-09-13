package com.devicepilot.ai.service

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
import com.devicepilot.ai.DevicePilotApp
import com.devicepilot.ai.MainActivity

/**
 * Keeps the process alive (and the agent loop running) while the agent drives other apps.
 * Shows a persistent notification with the current step and a Stop action, and a small
 * floating Stop button overlay if the user granted the overlay permission.
 */
class AgentForegroundService : Service() {

    private var overlay: StopOverlay? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel(this)
        startForegroundCompat(build(this, "Agent is starting…"))
        overlay = StopOverlay(this).also { it.show { DevicePilotApp.session(this).stop() } }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { DevicePilotApp.session(this).stop(); stopSelf(); return START_NOT_STICKY }
            ACTION_UPDATE -> {
                val text = intent.getStringExtra(EXTRA_TEXT) ?: "Working…"
                (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIF_ID, build(this, text))
                overlay?.setStatus(text)
            }
        }
        return START_NOT_STICKY
    }

    private fun startForegroundCompat(n: Notification) {
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    override fun onDestroy() {
        overlay?.hide(); overlay = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val CHANNEL_ID = "agent_running"
        const val NOTIF_ID = 42
        const val ACTION_STOP = "com.devicepilot.ai.STOP"
        const val ACTION_UPDATE = "com.devicepilot.ai.UPDATE"
        const val EXTRA_TEXT = "text"

        fun updateStatus(ctx: Context, text: String) {
            try {
                ctx.startService(Intent(ctx, AgentForegroundService::class.java).setAction(ACTION_UPDATE).putExtra(EXTRA_TEXT, text))
            } catch (_: Throwable) { }
        }

        fun ensureChannel(ctx: Context) {
            if (Build.VERSION.SDK_INT >= 26) {
                val nm = ctx.getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                    nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Agent activity", NotificationManager.IMPORTANCE_LOW).apply {
                        description = "Shown while DevicePilot is performing a task"
                        setShowBadge(false)
                    })
                }
            }
        }

        fun build(ctx: Context, text: String): Notification {
            val open = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val stop = PendingIntent.getService(ctx, 1, Intent(ctx, AgentForegroundService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            return NotificationCompat.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_manage)
                .setContentTitle("DevicePilot is working")
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(open)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stop)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()
        }
    }
}
