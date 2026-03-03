package com.example.wy

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat

class RestartService : BroadcastReceiver() {


    override fun onReceive(context: Context?, intent: Intent?) {
        Log.d("RestartReceiver", "Restarting MonitorService")
        ContextCompat.startForegroundService(
            context!!,
            Intent(context, MonitorService::class.java)
        )
    }

}