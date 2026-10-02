package com.example.wy

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.AlarmManager
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import android.util.Log
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class MainActivity : AppCompatActivity() {

    private var askedUsage = false
    private var askedBattery = false
    private var askedExactAlarm = false
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        findViewById<Button>(R.id.btnUninstall).setOnClickListener {
            showUninstallConfirmationDialog()
        }
    }

    override fun onResume() {
        super.onResume()
        DailyScheduler.scheduler(this)
        if (!ensurePermissions()) return
        maybeShowSamsungHint()
        ensureService()
    }

    /**
     * One settings screen at a time. On Android 10 there is no exact-alarm page
     * and no notification runtime permission; those exist only on newer OS versions.
     */
    @SuppressLint("BatteryLife")
    private fun ensurePermissions(): Boolean {
        if (!hasUsageStatsPermission()) {
            if (!askedUsage) {
                askedUsage = true
                startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                Toast.makeText(this, "Enable usage", Toast.LENGTH_LONG).show()
            }
            return false
        }
        val power = getSystemService(POWER_SERVICE) as PowerManager
        if (!power.isIgnoringBatteryOptimizations(packageName) && !askedBattery) {
            askedBattery = true
            val request = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = "package:$packageName".toUri()
            }
            startActivity(request)
            return false
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val alarms = getSystemService(ALARM_SERVICE) as AlarmManager
            if (!alarms.canScheduleExactAlarms() && !askedExactAlarm) {
                askedExactAlarm = true
                startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM))
                return false
            }
        }
        return true
    }

    private fun maybeShowSamsungHint() {
        if (!Build.MANUFACTURER.equals("samsung", ignoreCase = true)) return
        val prefs = getSharedPreferences(HINT_PREFS, MODE_PRIVATE)
        if (prefs.getBoolean(KEY_SAMSUNG_HINT, false)) return
        prefs.edit().putBoolean(KEY_SAMSUNG_HINT, true).apply()
        AlertDialog.Builder(this)
            .setTitle("Hi Friends")
            .setMessage(
                "Samsung Device",
            )
            .setPositiveButton("OK", null)
            .show()
    }

    private fun ensureService() {
//        val action = when {
//            isWithinActiveHours() -> AlarmReceiver.ACTION_START
//            HomeUpload.isUploadWindow() && EventQueue.hasPending(this) -> AlarmReceiver.ACTION_FLUSH
//            DeliveryState.needsCatchUp(this) -> AlarmReceiver.ACTION_STOP
//            else -> return
//        }
//        if (action == AlarmReceiver.ACTION_START && isServiceRunning(MonitorService::class.java)) return
//        if (action == AlarmReceiver.ACTION_START) {
//            MonitorState.setEnabled(this, true)
//            KillRestart.scheduleHeartbeat(this)
//        }
//        ContextCompat.startForegroundService(
//            this,
//            Intent(this, MonitorService::class.java).putExtra(AlarmReceiver.EXTRA_ACTION, action),
//        )
        if (!hasUsageStatsPermission()) return
        val action = MonitorAction.resolve(this) ?: return
        if (action == AlarmReceiver.ACTION_START && isServiceRunning(MonitorService::class.java)) return
        if (action == AlarmReceiver.ACTION_START) {
            MonitorState.setEnabled(this, true)
            KillRestart.scheduleHeartbeat(this)
        }
        ContextCompat.startForegroundService(
            this,
            Intent(this, MonitorService::class.java).putExtra(AlarmReceiver.EXTRA_ACTION, action),
        )
    }

    private fun showUninstallConfirmationDialog() {
        AlertDialog.Builder(this)
            .setTitle("Uninstall App")
            .setMessage("Are you sure you want to uninstall this app?")
            .setPositiveButton("OK") { _, _ -> sendSelfUninstallAndUninstall() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    @OptIn(InternalSerializationApi::class)
    private fun sendSelfUninstallAndUninstall() {
        val payload = PackageChangePayload(
            timestamp = isoTimestamp(System.currentTimeMillis()),
            appName = appLabel(this, packageName),
            `package` = packageName,
            deviceId = deviceId(this),
        )
        val body = Json.encodeToString(payload)
        Toast.makeText(this, "Bye byee...", Toast.LENGTH_SHORT).show()
        Thread {
            try {
                val codeOk = BackendClient.post(this
                    ,ApiPaths.UNINSTALLED, body)
                Log.i(TAG, "self uninstall posted=$codeOk")
            } catch (e: Exception) {
                Log.e(TAG, "self uninstall post failed", e)
            }
            runOnUiThread {
                try {
                    startActivity(Intent(Intent.ACTION_DELETE).apply {
                        data = "package:$packageName".toUri()
                    })
                } catch (e: Exception) {
                    Log.e(TAG, "uninstall intent failed", e)
                    Toast.makeText(this, "Could not launch uninstaller", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    @Suppress("DEPRECATION")
    private fun isServiceRunning(serviceClass: Class<*>): Boolean {
        val manager = getSystemService(ACTIVITY_SERVICE) as ActivityManager
        return manager.getRunningServices(Int.MAX_VALUE)
            .any { it.service.className == serviceClass.name }
    }

    private fun hasUsageStatsPermission(): Boolean {
        val appOps = getSystemService(APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                packageName,
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                packageName,
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    companion object {
        private const val TAG = "MainActivity"
        private const val HINT_PREFS = "monitor_state"
        private const val KEY_SAMSUNG_HINT = "samsung_hint"
    }
}
