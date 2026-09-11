package com.campusmesh.android.call

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
import com.campusmesh.android.MainActivity
import com.campusmesh.android.R

/**
 * Keeps the call's mic capture/playback threads alive with the screen off or the app
 * backgrounded, independent of [com.campusmesh.android.service.MeshForegroundService]'s own
 * promotion logic. Lives only for the duration of one active call - started from
 * [CallSignalingManager] right when streaming begins, stopped when the call ends.
 */
class CallForegroundService : Service() {

    companion object {
        private const val CHANNEL_ID = "campus_mesh_call"
        private const val NOTIFICATION_ID = 20001
        private const val ACTION_START = "com.campusmesh.android.call.START"
        private const val ACTION_HANGUP = "com.campusmesh.android.call.HANGUP"

        fun start(context: Context) {
            val intent = Intent(context, CallForegroundService::class.java).apply { action = ACTION_START }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            try { context.stopService(Intent(context, CallForegroundService::class.java)) } catch (_: Exception) { }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_HANGUP) {
            try { CallSignalingManager.endCall() } catch (_: Exception) { }
            stopSelf()
            return START_NOT_STICKY
        }
        startForegroundCompat(buildNotification())
        return START_NOT_STICKY
    }

    private fun buildNotification(): Notification {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)

        val openIntent = Intent(this, MainActivity::class.java)
        val contentPending = PendingIntent.getActivity(this, 0, openIntent, flags)

        val hangupIntent = Intent(this, CallForegroundService::class.java).apply { action = ACTION_HANGUP }
        val hangupPending = PendingIntent.getService(this, 1, hangupIntent, flags)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Voice call")
            .setContentText("Tap to return to the call")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setContentIntent(contentPending)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Hang up", hangupPending)
            .build()
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Voice calls", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Ongoing voice call notification"
                setShowBadge(false)
            }
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
