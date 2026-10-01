package com.example.wy

import android.content.Context

object MonitorAction {
    fun resolve (context: Context):String?=when {
        isWithinActiveHours() -> AlarmReceiver.ACTION_START
        HomeUpload.isUploadWindow() && EventQueue.hasPending(context) -> AlarmReceiver.ACTION_FLUSH
        DeliveryState.needsCatchUp(context) -> AlarmReceiver.ACTION_STOP
        else -> null
    }
}