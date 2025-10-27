package com.example.emotibitconnector

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

class SessionService : Service() {

    override fun onCreate() {
        super.onCreate()
        ensureChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        return when (action) {
            ACTION_STOP -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                RUNNING = false
                START_NOT_STICKY
            }
            ACTION_START -> {
                val device = intent?.getStringExtra(EXTRA_DEVICE_IP).orEmpty()
                val dp = intent?.getIntExtra(EXTRA_DATA_PORT, -1) ?: -1
                val cp = intent?.getIntExtra(EXTRA_CTRL_PORT, -1) ?: -1
                startForeground(NOTIFICATION_ID, buildNotification(device, dp, cp))
                RUNNING = true
                START_STICKY
            }
            else -> {
                if (!RUNNING) {
                    startForeground(NOTIFICATION_ID, buildNotification("", -1, -1))
                    RUNNING = true
                }
                START_STICKY
            }
        }
    }

    override fun onDestroy() {
        RUNNING = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(deviceIp: String, dp: Int, cp: Int): Notification {
        val title = getString(R.string.app_name)
        val message = if (deviceIp.isNotBlank() && dp >= 0 && cp >= 0) {
            getString(R.string.session_notification_content, deviceIp, dp, cp)
        } else {
            getString(R.string.session_notification_default)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setWhen(System.currentTimeMillis())
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "emotibit_session"
        private const val CHANNEL_NAME = "EmotiBit Session"
        private const val NOTIFICATION_ID = 42
        private const val ACTION_START = "com.example.emotibitconnector.action.START_SESSION_SERVICE"
        private const val ACTION_STOP = "com.example.emotibitconnector.action.STOP_SESSION_SERVICE"
        private const val EXTRA_DEVICE_IP = "extra_device_ip"
        private const val EXTRA_DATA_PORT = "extra_data_port"
        private const val EXTRA_CTRL_PORT = "extra_ctrl_port"
        @Volatile
        private var RUNNING = false

        fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val manager = ContextCompat.getSystemService(context, NotificationManager::class.java)
                val existing = manager?.getNotificationChannel(CHANNEL_ID)
                if (existing == null) {
                    val channel = NotificationChannel(
                        CHANNEL_ID,
                        CHANNEL_NAME,
                        NotificationManager.IMPORTANCE_LOW
                    ).apply {
                        description = context.getString(R.string.session_notification_channel_description)
                    }
                    manager?.createNotificationChannel(channel)
                }
            }
        }

        fun start(context: Context, deviceIp: String, dataPort: Int, ctrlPort: Int) {
            val intent = Intent(context, SessionService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_DEVICE_IP, deviceIp)
                putExtra(EXTRA_DATA_PORT, dataPort)
                putExtra(EXTRA_CTRL_PORT, ctrlPort)
            }
            ensureChannel(context)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            if (!RUNNING) return
            val intent = Intent(context, SessionService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }
}
