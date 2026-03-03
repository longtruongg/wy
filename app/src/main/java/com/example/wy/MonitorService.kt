package com.example.wy

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.AppOpsManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.annotation.RequiresPermission
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.Calendar
import kotlin.concurrent.thread

val BASE_IP = "backend_ip"

class MonitorService : Service() {

    private var monitoringJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val TAG = "MonitorService"
    private lateinit var installReceiver: AppInstallReceiver
    private val channelId = "monitor_channel"

    private var currentForegroundPkg: String? = null
    private var sessionStartTime: Long = 0L
    private var lastConfirmedTime: Long = 0L

    private val reportedPackages = mutableSetOf<String>()

    private val MIN_OPEN_TIME_MS = 10 * 60 * 1000L      // 5 min threshold
    private val ABSENCE_TOLERANCE_MS = 3 * 60 * 1000L  // 3 min gap tolerance (> 5 min window)
    private val POLL_INTERVAL_MS = 30_000L

    // FIX: Query window must be LONGER than MIN_OPEN_TIME_MS
    // so the original open event is always within the window
    private val QUERY_WINDOW_MS = 15 * 60 * 1000L      // 10 min query window

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "MonitorService onCreate")

        createNotificationChannel()

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Monitor Service")
            .setContentText("Watching for app usage...")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .build()

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(1, notification)
            }
            Log.i(TAG, "startForeground called successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start foreground service", e)
            stopSelf()
            return
        }

        installReceiver = AppInstallReceiver()
        val filter = IntentFilter(Intent.ACTION_PACKAGE_ADDED).apply {
            addDataScheme("package")
        }
        ContextCompat.registerReceiver(
            this,
            installReceiver,
            filter,
            ContextCompat.RECEIVER_EXPORTED
        )
        Log.i(TAG, "AppInstallReceiver registered")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Monitor Service Channel",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Channel for monitoring app installations & usage"
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.getStringExtra("action")
        when (action) {
            "stop" -> {
                Log.i(TAG, "Received stop command → stopping service")
                stopSelf()
                return START_NOT_STICKY
            }

            "start_monitor", null -> {
                // normal start
            }

            else -> {}

        }

        if (!hasUsageStatsPermission()) {
            Log.w(TAG, "Usage stats permission not granted")
            stopSelf()
            return START_NOT_STICKY
        }
        startMonitoring()
        return START_STICKY


    }

    private fun hasUsageStatsPermission(): Boolean {
        val appOps = getSystemService(APP_OPS_SERVICE) as AppOpsManager
        val mode = appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            android.os.Process.myUid(),
            packageName
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun startMonitoring() {
        monitoringJob?.cancel()
        monitoringJob = scope.launch {
            while (isActive) {
                val currentTime = System.currentTimeMillis()

                if (!isWithinActiveHours()) {
                    Log.d(TAG, "Outside active hours — pausing monitoring")
                    currentForegroundPkg = null
                    sessionStartTime = 0L
                    lastConfirmedTime = 0L
                    delay(5 * 60_000) // check again every 1 minute
                    continue
                }

                // Reset at midnight
                val calendar = Calendar.getInstance()
                if (calendar.get(Calendar.HOUR_OF_DAY) == 0 && calendar.get(Calendar.MINUTE) == 0) {
                    reportedPackages.clear()
                    Log.d(TAG, "Daily reset done")
                }

                val fgPkg = getCurrentForegroundPackage()

                if (fgPkg != null) {
                    if (fgPkg == currentForegroundPkg) {
                        // Same app confirmed in foreground
                        lastConfirmedTime = currentTime
                        val totalTime = currentTime - sessionStartTime
                        Log.d(
                            TAG,
                            "$fgPkg accumulated: ${totalTime / 1000}s / ${MIN_OPEN_TIME_MS / 1000}s needed"
                        )

                        if (totalTime >= MIN_OPEN_TIME_MS && fgPkg !in reportedPackages) {
                            Log.i(
                                TAG,
                                "THRESHOLD REACHED → $fgPkg open for ${totalTime / 60000} min"
                            )
                            sendApplicationOpening(fgPkg, totalTime)
                            reportedPackages.add(fgPkg)
                        }

                    } else {
                        // New app came to foreground
                        Log.d(TAG, "New foreground app: $fgPkg (previous: $currentForegroundPkg)")
                        currentForegroundPkg = fgPkg
                        sessionStartTime = currentTime
                        lastConfirmedTime = currentTime
                    }

                } else {
                    // App not detected this poll
                    if (currentForegroundPkg != null) {
                        val absentDuration = currentTime - lastConfirmedTime

                        if (absentDuration > ABSENCE_TOLERANCE_MS) {
                            // Truly gone
                            Log.d(
                                TAG,
                                "App '$currentForegroundPkg' gone for ${absentDuration / 1000}s — clearing"
                            )
                            currentForegroundPkg = null
                        } else {
                            // Brief gap — keep counting total session time
                            val totalTime = currentTime - sessionStartTime
                            Log.d(
                                TAG,
                                "Brief gap ${absentDuration / 1000}s — total session: ${totalTime / 1000}s for $currentForegroundPkg"
                            )

                            if (totalTime >= MIN_OPEN_TIME_MS && currentForegroundPkg !in reportedPackages) {
                                Log.i(
                                    TAG,
                                    "THRESHOLD REACHED (gap) → $currentForegroundPkg open for ${totalTime / 60000} min"
                                )
                                sendApplicationOpening(currentForegroundPkg!!, totalTime)
                                reportedPackages.add(currentForegroundPkg!!)
                            }
                        }
                    }
                }

                delay(POLL_INTERVAL_MS)
            }
        }
    }

    @InternalSerializationApi
    @Serializable
    data class AppOpenedLongPayload(
        val timestamp: String,
        val appName: String,
        val `package`: String,
        val duration: Long
    )

    @OptIn(InternalSerializationApi::class)
    private fun sendApplicationOpening(pkg: String, durationMs: Long) {
        val appName = try {
            packageManager.getApplicationLabel(
                packageManager.getApplicationInfo(pkg, 0)
            ).toString()
        } catch (e: Exception) {
            pkg
        }

        val durationMin = durationMs / 60000
        Log.w(TAG, "SENDING → $appName ($pkg) open for $durationMin min")

        thread(name = "send-long-open") {
            try {
                val payload = AppOpenedLongPayload(
                    timestamp = java.time.LocalDateTime.now().toString(),
                    appName = appName,
                    `package` = pkg,
                    duration = durationMs
                )
                val json = Json.encodeToString(payload)

                val url = URL("http://${BASE_IP}/api/app-opened-long")
                val conn = url.openConnection() as HttpURLConnection
                conn.apply {
                    requestMethod = "POST"
                    doOutput = true
                    connectTimeout = 10000
                    readTimeout = 10000
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    outputStream.use { it.write(json.toByteArray(Charsets.UTF_8)) }
                }

                val responseCode = conn.responseCode
                if (responseCode in 200..299) {
                    Log.i(TAG, "Sent successfully (HTTP $responseCode)")
                } else {
                    Log.e(TAG, "Send failed — HTTP $responseCode")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send long-open event", e)
            }
        }
    }

    @SuppressLint("ServiceCast")
    private fun getCurrentForegroundPackage(): String? {
        val usageStatsManager = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val endTime = System.currentTimeMillis()

        val beginTime = endTime - QUERY_WINDOW_MS

        val usageEvents = usageStatsManager.queryEvents(beginTime, endTime) ?: return null

        var lastForegroundPackage: String? = null
        var lastForegroundTime: Long = 0L

        while (usageEvents.hasNextEvent()) {
            val event = UsageEvents.Event()
            usageEvents.getNextEvent(event)

            when (event.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED,
                UsageEvents.Event.MOVE_TO_FOREGROUND -> {
                    if (event.timeStamp > lastForegroundTime) {
                        lastForegroundTime = event.timeStamp
                        lastForegroundPackage = event.packageName
                    }
                }

                UsageEvents.Event.ACTIVITY_PAUSED,
                UsageEvents.Event.MOVE_TO_BACKGROUND -> {
                    if (event.packageName == lastForegroundPackage && event.timeStamp > lastForegroundTime) {
                        lastForegroundPackage = null
                        lastForegroundTime = 0L
                    }
                }
            }
        }

        return lastForegroundPackage
    }

    private fun isGameApplication(packageName: String): Boolean {
        return try {
            val appInfo = packageManager.getApplicationInfo(packageName, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                appInfo.category == ApplicationInfo.CATEGORY_GAME
            } else {
                @Suppress("DEPRECATION")
                (appInfo.flags and ApplicationInfo.FLAG_IS_GAME) != 0
            }
        } catch (e: PackageManager.NameNotFoundException) {
            false
        } catch (e: Exception) {
            false
        }
    }

    override fun onDestroy() {
        Log.e(TAG, "⚠️ onDestroy fired")
        monitoringJob?.cancel()
        try {
            unregisterReceiver(installReceiver)
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering receiver", e)
        }
        val restartIntent = Intent(applicationContext, RestartService::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            applicationContext,
            1,
            restartIntent,
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
        )
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmManager.setExactAndAllowWhileIdle(
            AlarmManager.ELAPSED_REALTIME,
            SystemClock.elapsedRealtime() + 6000,
            pendingIntent
        )


        //restart service
//        sendBroadcast(Intent(applicationContext, RestartService::class.java))
        Log.d(TAG, "MonitorService destroyed")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    @SuppressLint("SuspiciousIndentation")
    @RequiresPermission(Manifest.permission.SCHEDULE_EXACT_ALARM)
    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.e(TAG, "⚠️ onTaskRemoved fired")
        super.onTaskRemoved(rootIntent)
        Log.d(TAG, "Task removed — scheduling restart")
        val restartIntent = Intent(applicationContext, MonitorService::class.java)
        val pendingIntent = PendingIntent.getService(
            applicationContext,
            1,
            restartIntent,
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
        )
        val alarmManager = getSystemService(ALARM_SERVICE) as AlarmManager
        alarmManager.setExactAndAllowWhileIdle(
            AlarmManager.ELAPSED_REALTIME,
            SystemClock.elapsedRealtime() + 6000,
            pendingIntent
        )
        Log.e(TAG, "⚠️ Restart scheduled in 6 seconds")
    }

    private fun isAppRemoved(): Boolean {
        return false
    }


}

fun isWithinActiveHours(): Boolean {
    val now = LocalTime.now()
    val start = LocalTime.of(8, 0)
    val end = LocalTime.of(19, 0)
    return !now.isBefore(start) && now.isBefore(end)
}


object DailyScheduler {
    private const val REQUEST_START = 1001
    private const val REQUEST_STOP = 1002
    fun scheduler(ctx: Context) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            if (!am.canScheduleExactAlarms()) {
                // Show UI → guide user to Settings → Alarms & reminders → allow for your app
                return
            }
        }
        val startPI = PendingIntent.getService(
            ctx,
            REQUEST_START,
            Intent(ctx, MonitorService::class.java).putExtra("action", "start"),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopPI = PendingIntent.getService(
            ctx,
            REQUEST_STOP,
            Intent(ctx, MonitorService::class.java).putExtra("action", "stop"),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val now = LocalDateTime.now(ZoneId.systemDefault())
        var nextStart = now.withHour(8).withMinute(0).withSecond(0).withNano(0)
        if (nextStart.isBefore(now)) nextStart = nextStart.plusDays(1)

        // Next 19:00
        var nextStop = now.withHour(19).withMinute(0).withSecond(0).withNano(0)
        if (nextStop.isBefore(now)) nextStop = nextStop.plusDays(1)

        am.setExactAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            nextStart.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
            startPI
        )
        am.setExactAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            nextStop.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
            stopPI
        )
    }
}
