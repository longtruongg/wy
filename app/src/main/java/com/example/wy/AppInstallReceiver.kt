package com.example.wy

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDateTime
import kotlin.concurrent.thread

@SuppressLint("UnsafeOptInUsageError")
@Serializable
data class Payload(
    val timestamp: String,
    val appName: String,
    val active: String
)

class AppInstallReceiver : BroadcastReceiver() {
    companion object {
        private val client = OkHttpClient()
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private const val TAG = "AppInstallReceiver"
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        Log.d(TAG, "onReceive called : Broadcast Received! Action: ${intent?.action}")
        if (context == null || intent?.action != Intent.ACTION_PACKAGE_ADDED) {
            Log.d(TAG, "context is null or action is not ACTION_PACKAGE_ADDED")
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

        thread(name = "send app name") {
            sendIt(appName, packageName)
        }
    }

    private fun sendIt(appName: String, pkg: String) {
        val payload = mapOf(
            "timestamp" to LocalDateTime.now().toString(),
            "appName" to appName,
            "package" to pkg
            // "active" not needed anymore since endpoint implies it
        )

        thread {
            try {
                val json = Json.encodeToString(payload)
                val url = URL("http://${BASE_IP}:8080/api/app-installed")
                val conn = url.openConnection() as HttpURLConnection
                conn.apply {
                    requestMethod = "POST"
                    doOutput = true
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
            }
        }
    }
}
