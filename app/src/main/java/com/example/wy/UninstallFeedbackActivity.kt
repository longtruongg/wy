package com.example.wy

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.util.Log
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

class UninstallFeedbackActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        AlertDialog.Builder(this)
            .setTitle("App Uninstalled")
            .setMessage("Your app has been uninstalled.")
            .setSingleChoiceItems(
                arrayOf(
                    "Other"
                ), -1
            ) { _, which ->
                val reasons = listOf<String>(
                    "Other"
                )
                sendReason(reasons[which])

            }
            .setPositiveButton("Removed") { _, _ ->
                finish()
            }
            .setNeutralButton("Keep App") { _, _ ->
                finish()
            }
            .show()
    }

    private fun sendReason(str: String) {
        thread(name = "Bin uninstalled") {
            try {
                val payload = """{"reason":"$str","timestamp":"${java.time.LocalDateTime.now()}"}"""
                val url = URL("http://192.168.100.72:8080/api/app-uninstalled")
                val conn = url.openConnection() as HttpURLConnection
                conn.apply {
                    requestMethod = "POST"
                    doOutput = true
                    connectTimeout = 5000
                    setRequestProperty("Content-Type", "application/json")
                    outputStream.use { it.write(payload.toByteArray()) }
                }
                conn.responseCode
            } catch (e: Exception) {
                Log.e("UninstallFeedback", "Failed to send reason", e)
            }
        }
    }
}