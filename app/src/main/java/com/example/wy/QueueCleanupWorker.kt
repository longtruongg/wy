package com.example.wy

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.Calendar
import java.util.concurrent.TimeUnit

class QueueCleanupWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        EventQueue.dropStale(applicationContext, maxAgeMs = 3L * 24 * 60 * 60 * 1000) // 3 days
        scheduleMidnightCleanup(applicationContext)
        return Result.success()
    }
}

fun scheduleMidnightCleanup(context: Context) {
    val now = Calendar.getInstance()
    val nextMidnight = Calendar.getInstance().apply {
        add(Calendar.DAY_OF_YEAR, 1)
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
    }
    val delay = nextMidnight.timeInMillis - now.timeInMillis
    val request = OneTimeWorkRequestBuilder<QueueCleanupWorker>()
        .setInitialDelay(delay, TimeUnit.MILLISECONDS)
        .build()
    WorkManager.getInstance(context).enqueue(request)
}