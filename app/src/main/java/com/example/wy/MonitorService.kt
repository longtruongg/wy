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
import androidx.core.content.edit
import kotlin.time.Duration.Companion.milliseconds

object MonitorState {
    private const val PREFS = "monitor_state"
    private const val KEY_ENABLED = "enabled"

    fun setEnabled(ctx: Context, enabled: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit(commit = true) {
                putBoolean(KEY_ENABLED, enabled)
            }
    }

    fun isEnabled(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, false)
}

object KillRestart {
    private const val HEARTBEAT_REQUEST = 1003
    private const val REVIVE_REQUEST = 1006
    const val DELAY_MS = 1_000L

    /**
     * Armed while the service is alive. Samsung recents-swipe skips onDestroy
     * and does not restore START_STICKY, so this alarm is the restart.
     * AlarmManager starts the foreground service itself. A broadcast that then
     * calls startForegroundService is dropped after that swipe.
     */
    const val HEARTBEAT_MS = 60_000L

    fun schedule(ctx: Context) = scheduleRevive(ctx)

    fun scheduleHeartbeat(ctx: Context) = arm(ctx, HEARTBEAT_MS, heartbeatPending(ctx))

    fun scheduleRevive(ctx: Context) = arm(ctx, DELAY_MS, revivePending(ctx))

    private fun arm(ctx: Context, delayMs: Long, pi: PendingIntent) {
        if (!MonitorState.isEnabled(ctx) || !isWithinActiveHours()) return
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !am.canScheduleExactAlarms()) {
            Log.w("KillRestart", "exact alarm not allowed")
            return
        }
        legacyBroadcast(ctx)?.let { am.cancel(it) }
        try {
            am.setExactAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + delayMs,
                pi,
            )
            Log.i("KillRestart", "restart armed in ${delayMs / 1000}s")
        } catch (e: SecurityException) {
            Log.e("KillRestart", "restart not scheduled", e)
        }
    }

    fun cancel(ctx: Context) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(heartbeatPending(ctx))
        am.cancel(revivePending(ctx))
        legacyBroadcast(ctx)?.let { am.cancel(it) }
    }

    private fun serviceIntent(ctx: Context) =
        Intent(ctx, MonitorService::class.java)
            .putExtra(AlarmReceiver.EXTRA_ACTION, AlarmReceiver.ACTION_START)

    private fun heartbeatPending(ctx: Context): PendingIntent =
        PendingIntent.getForegroundService(
            ctx,
            HEARTBEAT_REQUEST,
            serviceIntent(ctx),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun revivePending(ctx: Context): PendingIntent =
        PendingIntent.getForegroundService(
            ctx,
            REVIVE_REQUEST,
            serviceIntent(ctx),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    /** Previous builds armed a broadcast under HEARTBEAT_REQUEST. Drop it so it cannot replace the service alarm. */
    private fun legacyBroadcast(ctx: Context): PendingIntent? =
        PendingIntent.getBroadcast(
            ctx,
            HEARTBEAT_REQUEST,
            Intent(ctx, AlarmReceiver::class.java)
                .putExtra(AlarmReceiver.EXTRA_ACTION, AlarmReceiver.ACTION_START),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
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
            .setContentTitle("Hi")               //dummy text
            .setContentText("Moring")//dummy text
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
                delay(POLL_INTERVAL_MS.milliseconds)
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

    // scan special application like Free Fire, LMHT: toc chien, garena lien quan
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
        Log.i(TAG,"day $day new long-opens: special app")
        for (pkg in TrackedApps.PACKAGES){
            val hits = GameSessions.closeSession(pkg,marks,until)
            if (hits.isEmpty()) continue
            Log.i(TAG, "day $day new long-opens: ${hits.size}")
            for (hit in hits) {
                val id = GameSessions.key(hit.packageName,hit.startMillis)
                if (DeliveryState.reportedKeys(this).contains(id)) continue
                val payLoad = GameSessionPayload(
                    appName = appLabel(this, hit.packageName),
                    `package` = hit.packageName,
                    sessionStart = hit.startMillis,
                    sessionEnd = hit.endMillis,
                    duration = hit.durationMillis,
                    deviceId = deviceId(this),

                )
                val store  = EventQueue.enqueue(
                    this, id, ApiPaths.GAME_SESSION, json.encodeToString(payLoad)
                )
                if(store) DeliveryState.addReported(this,id)
            }

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
                // Rows wait on disk. The 19:00 alarm sends them once home Wi-Fi can see the backend.
                EventQueue.maybeScheduleRetry(this)
            } finally {
                stopSelf()
            }
        }
    }

    private fun flushAsync(stopWhenIdle: Boolean) {
        thread(name = "flush-queue") {
            try {
                scanDueWindows()
                EventQueue.flushIfHome(this, FINISH_FLUSH_BUDGET_MS)
            } finally {
                if (stopWhenIdle && !isWithinActiveHours() && monitoringJob?.isActive != true) {
                    stopSelf()
                }
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
                description = "App usage "
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
            KillRestart.scheduleRevive(this)
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    @RequiresPermission(Manifest.permission.SCHEDULE_EXACT_ALARM)
    override fun onTaskRemoved(rootIntent: Intent?) {
        // Arm before super. A recents swipe kills the process as soon as this returns,
        // and Samsung does not restart a START_STICKY service.
        if (MonitorState.isEnabled(this) && isWithinActiveHours()) {
            KillRestart.scheduleRevive(this)
        }
        super.onTaskRemoved(rootIntent)
    }
////scan special app like Game { free fire, lien minh toc chien
//    private  fun scanWholeDay(day: LocalDate, zoneId: ZoneId){
//
//    val mark =
//    }
//

    companion object {
        private const val TAG = "MonitorService"
        private const val CHANNEL_ID = "monitor_channel"
        private const val NOTIFICATION_ID = 1
        private const val POLL_INTERVAL_MS = 30_000L
        private const val FINISH_FLUSH_BUDGET_MS = 45_000L
    }
}

object DailyScheduler {
    private const val REQUEST_START = 1001
    private const val REQUEST_STOP = 1002
    private const val REQUEST_UPLOAD = 1005

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
        val uploadPi = PendingIntent.getBroadcast(
            ctx,
            REQUEST_UPLOAD,
            Intent(ctx, AlarmReceiver::class.java)
                .putExtra(AlarmReceiver.EXTRA_ACTION, AlarmReceiver.ACTION_FLUSH),
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
        var nextUpload = now.withHour(HomeUpload.START_HOUR)
            .withMinute(HomeUpload.START_MINUTE)
            .withSecond(0)
            .withNano(0)
        if (!nextUpload.isAfter(now)) nextUpload = nextUpload.plusDays(1)
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
            am.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                nextUpload.atZone(zone).toInstant().toEpochMilli(),
                uploadPi,
            )
            Log.d("DailyScheduler", "next start=$nextStart next stop=$nextStop next upload=$nextUpload")
        } catch (e: SecurityException) {
            Log.e("DailyScheduler", "exact alarm denied", e)
        }
    }
}
