package com.cerocoder.meshrelay.service

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
import com.cerocoder.meshrelay.MainActivity
import com.cerocoder.meshrelay.R

/**
 * Keeps the process alive while the connection to the node is held.
 *
 * The connection itself belongs to the application-level container: this service
 * is a lifecycle anchor, not the owner. Without it Android suspends the process,
 * and the connection is dropped the moment the user leaves the app.
 */
class MeshForegroundService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannel()
        val text = intent?.getStringExtra(EXTRA_TEXT) ?: getString(R.string.service_notification_text)
        val notification: Notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.service_notification_title))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentIntent(openAppIntent())
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        // START_NOT_STICKY, not START_STICKY: the connection lives in the process,
        // and if the process is killed there is nothing for the service to
        // restore. With STICKY the system would restart it with a null intent,
        // and the shade would show a notification for a connection that does
        // not exist.
        return START_NOT_STICKY
    }

    /** A tap on the notification returns to the app rather than opening nothing. */
    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        },
        PendingIntent.FLAG_IMMUTABLE,
    )

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.service_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        )
        manager.createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "mesh_connection"
        private const val NOTIFICATION_ID = 1
        private const val EXTRA_TEXT = "extra_text"

        fun start(context: Context) {
            context.startForegroundService(Intent(context, MeshForegroundService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, MeshForegroundService::class.java))
        }

        /**
         * Replace the notification body without restarting the service.
         *
         * Counters only, and no faster than every thirty seconds: this is the one thing
         * that still runs with the screen off, and the whole point of building no
         * snapshots in the background is lost if the notification asks for one.
         */
        fun updateText(context: Context, text: String) {
            context.startService(
                Intent(context, MeshForegroundService::class.java).putExtra(EXTRA_TEXT, text),
            )
        }
    }
}
