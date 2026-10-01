package com.example.wy

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        Log.d(TAG, "boot completed")
        DailyScheduler.scheduler(context)
        val action = MonitorAction.resolve(context) ?: return
        if (action == AlarmReceiver.ACTION_START) {
            MonitorState.setEnabled(context, true)
            KillRestart.scheduleHeartbeat(context)
        }
        ContextCompat.startForegroundService(
            context,
            Intent(context, MonitorService::class.java).putExtra(AlarmReceiver.EXTRA_ACTION, action),
        )
    }

    companion object {
        private const val TAG = "BootReceiver"
    }
}
