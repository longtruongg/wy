package com.example.wy

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        Log.d("BootReceiver", "Boot completed")
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED) {
            DailyScheduler.scheduler(context!!)
        }
        if (isWithinActiveHours()) {
            Log.d("BootReceiver", "Starting service")
            ContextCompat.startForegroundService(
                context!!,
                Intent(context, MonitorService::class.java).apply {
                    putExtra("action", "start_monitor")
                }
            )
        }
    }
}