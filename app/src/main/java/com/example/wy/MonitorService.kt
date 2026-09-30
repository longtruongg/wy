package com.example.wy

import android.Manifest
import android.app.AlarmManager
import android.app.AppOpsManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
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
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

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

    /** Armed while the service is alive. Process death does not run onDestroy on Samsung, so this alarm is the restart. */
    const val HEARTBEAT_MS = 60_000L

    fun schedule(ctx: Context) = scheduleIn(ctx, DELAY_MS)

    fun scheduleHeartbeat(ctx: Context) = scheduleIn(ctx, HEARTBEAT_MS)

    private fun scheduleIn(ctx: Context, delayMs: Long) {
        if (!MonitorState.isEnabled(ctx) || !isWithinActiveHours()) return
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !am.canScheduleExactAlarms()) {
            Log.w("KillRestart", "exact alarm not allowed")
            return
        }
        try {
            am.setExactAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + delayMs,
                pending(ctx),
            )
            Log.i("KillRestart", "restart armed in ${delayMs / 1000}s")
        } catch (e: SecurityException) {
            Log.e("KillRestart", "restart not scheduled", e)
        }
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
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
}

class MonitorService : Service() {

    private var monitoringJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val finishing = AtomicBoolean(false)
    private val json = Json { encodeDefaults = true }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate")
        createNotificationChannel()
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Monitor Service")
            .setContentText("Watching app usage 08:00–18:30")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed", e)
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.getStringExtra(AlarmReceiver.EXTRA_ACTION)
            ?: intent?.getStringExtra("action")
        Log.i(TAG, "onStartCommand action=$action")

        when (action) {
            AlarmReceiver.ACTION_FLUSH -> {
                flushAsync(stopWhenIdle = !isWithinActiveHours())
                return if (isWithinActiveHours()) START_STICKY else START_NOT_STICKY
            }

            AlarmReceiver.ACTION_STOP -> {
                finishWindow()
                return START_NOT_STICKY
            }
        }

        if (!isWithinActiveHours()) {
            finishWindow()
            return START_NOT_STICKY
        }
        if (!hasUsageStatsPermission()) {
            Log.w(TAG, "usage access missing")
            stopSelf()
            return START_NOT_STICKY
        }

        finishing.set(false)
        MonitorState.setEnabled(this, true)
        KillRestart.scheduleHeartbeat(this)
        if (monitoringJob?.isActive != true) startMonitoring()
        DailyScheduler.scheduler(this)
        return START_STICKY
    }

    private fun startMonitoring() {
        monitoringJob?.cancel()
        monitoringJob = scope.launch {
            while (isActive) {
                if (!isWithinActiveHours()) {
                    finishWindow()
                    return@launch
                }
                KillRestart.scheduleHeartbeat(this@MonitorService)
                scanDueWindows()
                EventQueue.flush(this@MonitorService, POLL_FLUSH_BUDGET_MS)
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    /**
     * Scans unfinished past days (Samsung may have killed the process before 18:30)
     * and today's window so far. A session is queued once; HTTP failure stays in the queue.
     */
    private fun scanDueWindows() {
        if (!hasUsageStatsPermission()) return
        DeliveryState.init(this)
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val now = LocalDateTime.now(zone).toLocalTime()
        var day = DeliveryState.lastFinishedDay(this).plusDays(1)
        val earliest = today.minusDays(ActiveWindow.CATCHUP_DAYS.toLong())
        if (day.isBefore(earliest)) day = earliest
        while (day.isBefore(today)) {
            scanDay(day, zone)
            DeliveryState.markDayFinished(this, day)
            day = day.plusDays(1)
        }
        if (now.isBefore(LocalTime.of(ActiveWindow.START_HOUR, ActiveWindow.START_MINUTE))) return
        scanDay(today, zone)
        if (!now.isBefore(LocalTime.of(ActiveWindow.END_HOUR, ActiveWindow.END_MINUTE))) {
            DeliveryState.markDayFinished(this, today)
        }
    }

    private fun scanDay(day: LocalDate, zone: ZoneId) {
        val windowStart = ActiveWindow.startMillis(day, zone)
        val windowEnd = ActiveWindow.endMillis(day, zone)
        val until = minOf(windowEnd, System.currentTimeMillis())
        if (until <= windowStart) return

        val marks = UsageEventReader.read(this, windowStart - ActiveWindow.LOOKBACK_MS, until)
            .filter { it.packageName != packageName }
        val hits = UsageSessions.longOpens(
            marks = marks,
            rangeStartMillis = windowStart,
            nowMillis = until,
            alreadyReported = DeliveryState.reportedKeys(this),
        )
        if (hits.isEmpty()) return
        Log.i(TAG, "day $day new long-opens: ${hits.size}")
        for (hit in hits) {
            val id = UsageSessions.key(hit)
            val payload = AppOpenedLongPayload(
                timestamp = isoTimestamp(hit.detectedAtMillis, zone),
                appName = appLabel(this, hit.packageName),
                `package` = hit.packageName,
                duration = hit.durationMillis,
                sessionStart = hit.sessionStartMillis,
                deviceId = deviceId(this),
            )
            val stored = EventQueue.enqueue(
                this,
                id,
                ApiPaths.OPENED_LONG,
                json.encodeToString(payload),
            )
            if (stored) DeliveryState.addReported(this, id)
        }
    }

    private fun finishWindow() {
        if (!finishing.compareAndSet(false, true)) return
        Log.i(TAG, "window close — scanning then flushing queue")
        MonitorState.setEnabled(this, false)
        KillRestart.cancel(this)
        DailyScheduler.scheduler(this)
        monitoringJob?.cancel()
        thread(name = "finish-window") {
            try {
                scanDueWindows()
                EventQueue.flush(this, FINISH_FLUSH_BUDGET_MS)
                EventQueue.maybeScheduleRetry(this)
            } finally {
                stopSelf()
            }
        }
    }

    private fun flushAsync(stopWhenIdle: Boolean) {
        thread(name = "flush-queue") {
            EventQueue.flush(this, FINISH_FLUSH_BUDGET_MS)
            if (!stopWhenIdle) return@thread
            EventQueue.maybeScheduleRetry(this)
            if (!isWithinActiveHours() && monitoringJob?.isActive != true) {
                stopSelf()
            }
        }
    }

    private fun hasUsageStatsPermission(): Boolean {
        val appOps = getSystemService(APP_OPS_SERVICE) as AppOpsManager
        val mode = appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            android.os.Process.myUid(),
            packageName,
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Monitor Service Channel",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "App usage monitoring between 08:00 and 18:30"
                setShowBadge(false)
            }
            val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        Log.i(TAG, "onDestroy")
        monitoringJob?.cancel()
        scope.cancel()
        if (MonitorState.isEnabled(this) && isWithinActiveHours()) {
            KillRestart.schedule(this)
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    @RequiresPermission(Manifest.permission.SCHEDULE_EXACT_ALARM)
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        if (MonitorState.isEnabled(this) && isWithinActiveHours()) {
            KillRestart.schedule(this)
        }
    }

    companion object {
        private const val TAG = "MonitorService"
        private const val CHANNEL_ID = "monitor_channel"
        private const val NOTIFICATION_ID = 1
        private const val POLL_INTERVAL_MS = 30_000L
        private const val POLL_FLUSH_BUDGET_MS = 15_000L
        private const val FINISH_FLUSH_BUDGET_MS = 45_000L
    }
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
        val startPi = PendingIntent.getBroadcast(
            ctx,
            REQUEST_START,
            Intent(ctx, AlarmReceiver::class.java)
                .putExtra(AlarmReceiver.EXTRA_ACTION, AlarmReceiver.ACTION_START),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopPi = PendingIntent.getBroadcast(
            ctx,
            REQUEST_STOP,
            Intent(ctx, AlarmReceiver::class.java)
                .putExtra(AlarmReceiver.EXTRA_ACTION, AlarmReceiver.ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val zone = ZoneId.systemDefault()
        val now = LocalDateTime.now(zone)
        var nextStart = now.withHour(ActiveWindow.START_HOUR)
            .withMinute(ActiveWindow.START_MINUTE)
            .withSecond(0)
            .withNano(0)
        if (!nextStart.isAfter(now)) nextStart = nextStart.plusDays(1)
        var nextStop = now.withHour(ActiveWindow.END_HOUR)
            .withMinute(ActiveWindow.END_MINUTE)
            .withSecond(0)
            .withNano(0)
        if (!nextStop.isAfter(now)) nextStop = nextStop.plusDays(1)
        try {
            am.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                nextStart.atZone(zone).toInstant().toEpochMilli(),
                startPi,
            )
            am.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                nextStop.atZone(zone).toInstant().toEpochMilli(),
                stopPi,
            )
            Log.d("DailyScheduler", "next start=$nextStart next stop=$nextStop")
        } catch (e: SecurityException) {
            Log.e("DailyScheduler", "exact alarm denied", e)
        }
    }
}
