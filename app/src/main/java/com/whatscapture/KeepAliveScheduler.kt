package com.whatscapture

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

object KeepAliveScheduler {

    private const val PERIODIC_KEEP_ALIVE_NAME = "whatscapture_periodic_keepalive"
    private const val IMMEDIATE_KEEP_ALIVE_NAME = "whatscapture_immediate_keepalive"

    fun schedule(context: Context) {
        val wm = WorkManager.getInstance(context)

        val periodicRequest = PeriodicWorkRequestBuilder<KeepAliveWorker>(15, TimeUnit.MINUTES)
            .build()

        wm.enqueueUniquePeriodicWork(
            PERIODIC_KEEP_ALIVE_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            periodicRequest
        )

        enqueueImmediate(context)
    }

    fun enqueueImmediate(context: Context) {
        val wm = WorkManager.getInstance(context)
        val oneTimeRequest = OneTimeWorkRequestBuilder<KeepAliveWorker>().build()
        wm.enqueueUniqueWork(
            IMMEDIATE_KEEP_ALIVE_NAME,
            ExistingWorkPolicy.REPLACE,
            oneTimeRequest
        )
    }
}
