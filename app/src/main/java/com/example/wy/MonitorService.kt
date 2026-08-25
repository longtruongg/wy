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
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.annotation.RequiresPermission
import androidx.core.app.NotificationCompat
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
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import kotlin.concurrent.thread

const val BASE_IP = "backend_ip"

fun cathyUrl(path: String) = "http://$BASE_IP:8080$path"

object MonitorState {
    private const val PREFS = "monitor_state"
    private const val KEY_ENABLED = "enabled"

    fun setEnabled(ctx: Context, enabled: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ENABLED, enabled)
            .apply()
    }

    fun isEnabled(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, false)
}

object KillRestart {
    private const val REQUEST = 1003
    const val DELAY_MS = 10_000L

    fun schedule(ctx: Context) {
        if (!MonitorState.isEnabled(ctx) || !isWithinActiveHours()) return
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.setExactAndAllowWhileIdle(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            SystemClock.elapsedRealtime() + DELAY_MS,
            pending(ctx)
        )
        Log.e("KillRestart", "restart scheduled in ${DELAY_MS / 1000}s")
    }

    fun cancel(ctx: Context) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(pending(ctx))
    }

    private fun pending(ctx: Context): PendingIntent =
        PendingIntent.getBroadcast(
            ctx,
            REQUEST,
            Intent(ctx, AlarmReceiver::class.java)
                .putExtra(AlarmReceiver.EXTRA_ACTION, AlarmReceiver.ACTION_START),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
}

class MonitorService : Service() {

    private var monitoringJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val TAG = "MonitorService"
    private val channelId = "monitor_channel"

    private var currentForegroundPkg: String? = null
    private var sessionStartTime: Long = 0L
    private var lastConfirmedTime: Long = 0L

    private val reportedPackages = mutableSetOf<String>()

    private val MIN_OPEN_TIME_MS = 10 * 60 * 1000L
    private val ABSENCE_TOLERANCE_MS = 3 * 60 * 1000L
    private val POLL_INTERVAL_MS = 30_000L
    private val QUERY_WINDOW_MS = 15 * 60 * 1000L

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
            AlarmReceiver.ACTION_STOP -> {
                Log.i(TAG, "19:00 stop → sending daily results then stopping")
                MonitorState.setEnabled(this, false)
                KillRestart.cancel(this)
                DailyScheduler.scheduler(this)
                monitoringJob?.cancel()
                thread(name = "flush-daily-results") {
                    sendDailyResults()
                    stopSelf()
                }
                return START_NOT_STICKY
            }

            AlarmReceiver.ACTION_START, null -> {
                MonitorState.setEnabled(this, true)
            }
        }

        if (!isWithinActiveHours()) {
            Log.i(TAG, "Outside 08:00–19:00 → sending results then stopping")
            MonitorState.setEnabled(this, false)
            KillRestart.cancel(this)
            DailyScheduler.scheduler(this)
            monitoringJob?.cancel()
            thread(name = "flush-daily-results") {
                sendDailyResults()
                stopSelf()
            }
            return START_NOT_STICKY
        }

        if (!hasUsageStatsPermission()) {
            Log.w(TAG, "Usage stats permission not granted")
            stopSelf()
            return START_NOT_STICKY
        }
        if (monitoringJob?.isActive != true) {
            startMonitoring()
        }
        DailyScheduler.scheduler(this)
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
        reportedPackages.clear()
        currentForegroundPkg = null
        sessionStartTime = 0L
        lastConfirmedTime = 0L
        monitoringJob = scope.launch {
            while (isActive) {
                val currentTime = System.currentTimeMillis()

                if (!isWithinActiveHours()) {
                    Log.d(TAG, "Outside active hours — sending results then stopping")
                    MonitorState.setEnabled(this@MonitorService, false)
                    KillRestart.cancel(this@MonitorService)
                    DailyScheduler.scheduler(this@MonitorService)
                    sendDailyResults()
                    stopSelf()
                    return@launch
                }

                val fgPkg = getCurrentForegroundPackage()

                if (fgPkg != null) {
                    if (fgPkg == currentForegroundPkg) {
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
                        Log.d(TAG, "New foreground app: $fgPkg (previous: $currentForegroundPkg)")
                        currentForegroundPkg = fgPkg
                        sessionStartTime = currentTime
                        lastConfirmedTime = currentTime
                    }
                } else if (currentForegroundPkg != null) {
                    val absentDuration = currentTime - lastConfirmedTime

                    if (absentDuration > ABSENCE_TOLERANCE_MS) {
                        Log.d(
                            TAG,
                            "App '$currentForegroundPkg' gone for ${absentDuration / 1000}s — clearing"
                        )
                        currentForegroundPkg = null
                    } else {
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
    private fun sendDailyResults() {
        if (!hasUsageStatsPermission()) {
            Log.w(TAG, "skip daily flush — no usage stats permission")
            return
        }
        val zone = ZoneId.systemDefault()
        val begin = LocalDate.now(zone).atTime(8, 0).atZone(zone).toInstant().toEpochMilli()
        val end = System.currentTimeMillis()
        val durations = foregroundDurations(begin, end)
        Log.i(TAG, "daily flush: ${durations.size} apps seen since 08:00")
        for ((pkg, durationMs) in durations) {
            if (pkg == packageName) continue
            if (durationMs < MIN_OPEN_TIME_MS) continue
            if (pkg in reportedPackages) continue
            sendApplicationOpening(pkg, durationMs, blocking = true)
            reportedPackages.add(pkg)
        }
    }

    private fun foregroundDurations(begin: Long, end: Long): Map<String, Long> {
        val usageStatsManager = getSystemService(USAGE_STATS_SERVICE) as UsageStatsManager
        val usageEvents = usageStatsManager.queryEvents(begin, end) ?: return emptyMap()
        val durations = mutableMapOf<String, Long>()
        val openSince = mutableMapOf<String, Long>()
        val event = UsageEvents.Event()
        while (usageEvents.hasNextEvent()) {
            usageEvents.getNextEvent(event)
            when (event.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED,
                UsageEvents.Event.MOVE_TO_FOREGROUND -> {
                    openSince[event.packageName] = event.timeStamp
                }

                UsageEvents.Event.ACTIVITY_PAUSED,
                UsageEvents.Event.MOVE_TO_BACKGROUND -> {
                    val started = openSince.remove(event.packageName) ?: continue
                    val d = event.timeStamp - started
                    if (d > 0) {
                        durations[event.packageName] = (durations[event.packageName] ?: 0L) + d
                    }
                }
            }
        }
        for ((pkg, started) in openSince) {
            val d = end - started
            if (d > 0) {
                durations[pkg] = (durations[pkg] ?: 0L) + d
            }
        }
        return durations
    }

    @OptIn(InternalSerializationApi::class)
    private fun sendApplicationOpening(pkg: String, durationMs: Long, blocking: Boolean = false) {
        val appName = try {
            packageManager.getApplicationLabel(
                packageManager.getApplicationInfo(pkg, 0)
            ).toString()
        } catch (e: Exception) {
            pkg
        }

        val durationMin = durationMs / 60000
        Log.w(TAG, "SENDING → $appName ($pkg) open for $durationMin min")

        val work = {
            var conn: HttpURLConnection? = null
            try {
                val payload = AppOpenedLongPayload(
                    timestamp = java.time.LocalDateTime.now().toString(),
                    appName = appName,
                    `package` = pkg,
                    duration = durationMs
                )
                val json = Json.encodeToString(payload)

                conn = (URL(cathyUrl("/api/app-opened-long")).openConnection() as HttpURLConnection).apply {
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
            } finally {
                conn?.disconnect()
            }
        }

        if (blocking) {
            val t = thread(name = "send-long-open") { work() }
            t.join(15_000)
        } else {
            thread(name = "send-long-open") { work() }
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

    override fun onDestroy() {
        Log.e(TAG, "onDestroy fired")
        monitoringJob?.cancel()
        if (MonitorState.isEnabled(this) && isWithinActiveHours()) {
            KillRestart.schedule(this)
        }
        Log.d(TAG, "MonitorService destroyed")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    @RequiresPermission(Manifest.permission.SCHEDULE_EXACT_ALARM)
    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.e(TAG, "onTaskRemoved fired — user removed task")
        super.onTaskRemoved(rootIntent)
        if (MonitorState.isEnabled(this) && isWithinActiveHours()) {
            KillRestart.schedule(this)
        }
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !am.canScheduleExactAlarms()) {
            Log.w("DailyScheduler", "exact alarms not allowed")
            return
        }
        val startPI = PendingIntent.getBroadcast(
            ctx,
            REQUEST_START,
            Intent(ctx, AlarmReceiver::class.java)
                .putExtra(AlarmReceiver.EXTRA_ACTION, AlarmReceiver.ACTION_START),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopPI = PendingIntent.getBroadcast(
            ctx,
            REQUEST_STOP,
            Intent(ctx, AlarmReceiver::class.java)
                .putExtra(AlarmReceiver.EXTRA_ACTION, AlarmReceiver.ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val zone = ZoneId.systemDefault()
        val now = LocalDateTime.now(zone)

        var nextStart = now.withHour(8).withMinute(0).withSecond(0).withNano(0)
        if (!nextStart.isAfter(now)) nextStart = nextStart.plusDays(1)

        var nextStop = now.withHour(19).withMinute(0).withSecond(0).withNano(0)
        if (!nextStop.isAfter(now)) nextStop = nextStop.plusDays(1)

        am.setExactAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            nextStart.atZone(zone).toInstant().toEpochMilli(),
            startPI
        )
        am.setExactAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            nextStop.atZone(zone).toInstant().toEpochMilli(),
            stopPI
        )
        Log.d("DailyScheduler", "next start=$nextStart next stop=$nextStop")
    }
}
