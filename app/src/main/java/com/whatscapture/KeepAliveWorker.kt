package com.whatscapture

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

class KeepAliveWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        Config.init(applicationContext)
        if (!Config.isServiceEnabled(applicationContext)) {
            return Result.success()
        }

        try {
            NotificationListener.forceRebind(applicationContext)
        } catch (_: Exception) {
        }

        val serviceIntent = Intent(applicationContext, MediaMonitorService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            applicationContext.startForegroundService(serviceIntent)
        } else {
            applicationContext.startService(serviceIntent)
        }

        return Result.success()
    }
}
