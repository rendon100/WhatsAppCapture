package com.whatscapture

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED ||
            action == MediaMonitorService.ACTION_KEEP_ALIVE_RESTART
        ) {
            Config.init(context)
            if (!Config.isServiceEnabled(context)) return

            // Nudge notification listener to reconnect if it was detached.
            try {
                NotificationListener.forceRebind(context)
            } catch (_: Exception) {
            }

            KeepAliveScheduler.schedule(context)

            val serviceIntent = Intent(context, MediaMonitorService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        }
    }
}
