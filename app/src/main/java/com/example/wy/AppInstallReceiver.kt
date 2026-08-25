package com.example.wy

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDateTime
import kotlin.concurrent.thread

class AppInstallReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "AppInstallReceiver"
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        Log.d(TAG, "onReceive called : Broadcast Received! Action: ${intent?.action}")
        if (context == null || intent?.action != Intent.ACTION_PACKAGE_ADDED) {
            Log.d(TAG, "context is null or action is not ACTION_PACKAGE_ADDED")
            return
        }
        if (intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) {
            Log.d(TAG, "skip update (EXTRA_REPLACING)")
            return
        }
        val packageName = intent.data?.schemeSpecificPart ?: run {
            Log.e(TAG, "packageName is null")
            return
        }
        Log.d(TAG, "packageName is $packageName")

        val appName = try {
            val pm = context.packageManager
            val info = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(info).toString()
        } catch (e: Exception) {
            Log.e(TAG, "Error getting app name", e)
            return
        }
        Log.w(TAG, "appName ====> $appName")
        sendIt(appName, packageName)
    }

    @SuppressLint("UnsafeOptInUsageError")
    private fun sendIt(appName: String, pkg: String) {
        val payload = mapOf(
            "timestamp" to LocalDateTime.now().toString(),
            "appName" to appName,
            "package" to pkg
        )

        thread(name = "send-app-installed") {
            var conn: HttpURLConnection? = null
            try {
                val json = Json.encodeToString(payload)
                conn = (URL(cathyUrl("/api/app-installed")).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    connectTimeout = 10000
                    readTimeout = 10000
                    setRequestProperty("Content-Type", "application/json")
                    outputStream.use { it.write(json.toByteArray(Charsets.UTF_8)) }
                }

                val code = conn.responseCode
                if (code in 200..299) {
                    Log.i(TAG, "Install event sent OK: $appName")
                } else {
                    Log.e(TAG, "Install send failed: HTTP $code")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Install send error", e)
            } finally {
                conn?.disconnect()
            }
        }
    }
}
