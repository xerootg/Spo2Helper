package com.flyfish233.spo2helper

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.flyfish233.spo2helper.shared.Protocol
import com.flyfish233.spo2helper.shared.WearLink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.minutes

/**
 * Foreground service that asks the watch for a spot check every N minutes.
 * The interval is read from [ReadingStore] so it survives a START_STICKY restart.
 */
class MonitorService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Notifications.ensureChannels(this)
        val minutes = ReadingStore.intervalMinutes(this).coerceAtLeast(1)

        ServiceCompat.startForeground(
            this,
            Notifications.ID_MONITOR,
            buildNotification(minutes),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
        )
        _running.value = true

        job?.cancel()
        job = scope.launch {
            val link = WearLink(this@MonitorService)
            while (isActive) {
                runCatching { link.broadcast(Protocol.PATH_MEASURE) }
                delay(minutes.minutes)
            }
        }
        return START_STICKY
    }

    private fun buildNotification(minutes: Int): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, Notifications.CHANNEL_MONITOR)
            .setContentTitle(getString(R.string.monitor_service_title))
            .setContentText(getString(R.string.monitor_service_text_fmt, minutes))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        job?.cancel()
        scope.cancel()
        _running.value = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private val _running = MutableStateFlow(false)
        val running: StateFlow<Boolean> = _running

        fun start(context: Context) {
            context.startForegroundService(Intent(context, MonitorService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, MonitorService::class.java))
        }
    }
}
