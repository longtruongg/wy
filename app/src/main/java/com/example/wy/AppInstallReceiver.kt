package com.example.wy

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.concurrent.thread

class AppInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        thread(name = "pkg-change") {
            try {
                handle(context, intent)
            } finally {
                pending.finish()
            }
        }
    }

    private fun handle(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_PACKAGE_ADDED && action != Intent.ACTION_PACKAGE_REMOVED) return
        if (intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) {
            Log.d(TAG, "skip update")
            return
        }
        val pkg = intent.data?.schemeSpecificPart ?: return
        val added = action == Intent.ACTION_PACKAGE_ADDED
        val now = System.currentTimeMillis()
        val payload = PackageChangePayload(
            timestamp = isoTimestamp(now),
            appName = appLabel(context, pkg),
            `package` = pkg,
            deviceId = deviceId(context),
        )
        val id = if (added) "pkg-added:$pkg:$now" else "pkg-removed:$pkg:$now"
        val path = if (added) ApiPaths.INSTALLED else ApiPaths.UNINSTALLED
        Log.i(TAG, "${if (added) "installed" else "uninstalled"} $pkg")
        if (EventQueue.enqueue(context, id, path, Json.encodeToString(payload))) {
            EventQueue.flush(context, RECEIVER_FLUSH_BUDGET_MS)
            EventQueue.maybeScheduleRetry(context)
        }
    }

    companion object {
        private const val TAG = "AppInstallReceiver"
        private const val RECEIVER_FLUSH_BUDGET_MS = 8_000L
    }
}
