package com.example.wy

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.Calendar
import java.util.concurrent.TimeUnit

class Worker(ctx: Context, params:WorkerParameters): CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        AppDatabase.getInstance(applicationContext).payloadHelper().deleteSyncedEvents()
        scheduleMidnight(applicationContext)
        return Result.success()
    }


}
fun scheduleMidnight(context:Context){
   val now = Calendar.getInstance()
   val nextMidNight = Calendar.getInstance().apply {
       add(Calendar.DAY_OF_YEAR,1)
       set(Calendar.HOUR_OF_DAY,0)
       set(Calendar.MINUTE,0)
       set(Calendar.SECOND,0)
   }
    val delay = nextMidNight.timeInMillis-now.timeInMillis
    val request = OneTimeWorkRequestBuilder<Worker>()
        .setInitialDelay(delay, TimeUnit.MILLISECONDS)
        .build()
    WorkManager.getInstance(context).enqueue(request)
}

