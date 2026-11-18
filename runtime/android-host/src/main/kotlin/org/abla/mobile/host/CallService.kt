package org.abla.mobile.host

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

// Keeps a voice call (CallSession) going while the app is in the background or the
// screen is locked: a foreground service using the microphone, with an ongoing
// notification that opens the app. An app that makes calls declares it in its manifest
// with foregroundServiceType="microphone"; without that, calls still work on screen.
class CallService : Service() {
    companion object {
        private const val CHANNEL = "ongoing_call"
        private const val ID = 4710

        fun begin(context: Context, title: String) {
            val intent = Intent(context, CallService::class.java).putExtra("title", title)
            try {
                if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
            } catch (_: Exception) {
                // Not declared, or not allowed now: the call goes on while the app is on screen.
            }
        }

        fun finish(context: Context) {
            try { context.stopService(Intent(context, CallService::class.java)) } catch (_: Exception) {}
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26 && manager?.getNotificationChannel(CHANNEL) == null) {
            manager?.createNotificationChannel(NotificationChannel(CHANNEL, "Ongoing call", NotificationManager.IMPORTANCE_LOW))
        }
        val open = packageManager.getLaunchIntentForPackage(packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        val tap = PendingIntent.getActivity(this, ID, open ?: Intent(), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        @Suppress("DEPRECATION")
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else Notification.Builder(this)
        val notification = builder
            .setSmallIcon(android.R.drawable.sym_action_call)
            .setContentTitle(intent?.getStringExtra("title") ?: "On a call")
            .setContentText("Tap to return to the call")
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_CALL)
            .setContentIntent(tap)
            .build()
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            else startForeground(ID, notification)
        } catch (_: Exception) {
            stopSelf()
            return START_NOT_STICKY
        }
        // The call may have ended while this started.
        if (!CallSession.running()) stopSelf()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
}
