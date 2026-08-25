package com.example.wy

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.getStringExtra(EXTRA_ACTION) ?: return
        Log.d(TAG, "alarm action=$action")
        DailyScheduler.scheduler(context)

        when (action) {
            ACTION_START -> {
                if (!isWithinActiveHours()) {
                    Log.d(TAG, "start alarm outside 08:00–19:00 — not starting")
                    return
                }
                MonitorState.setEnabled(context, true)
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, MonitorService::class.java).putExtra("action", ACTION_START)
                )
            }

            ACTION_STOP -> {
                MonitorState.setEnabled(context, false)
                KillRestart.cancel(context)
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, MonitorService::class.java).putExtra("action", ACTION_STOP)
                )
            }
        }
    }

    companion object {
        private const val TAG = "AlarmReceiver"
        const val EXTRA_ACTION = "action"
        const val ACTION_START = "start"
        const val ACTION_STOP = "stop"
    }
}
