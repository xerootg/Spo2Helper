package com.flyfish233.spo2helper

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.flyfish233.spo2helper.shared.Protocol
import com.flyfish233.spo2helper.shared.Reading
import com.flyfish233.spo2helper.shared.Spo2Source
import com.flyfish233.spo2helper.shared.WearLink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.time.Duration
import kotlin.math.roundToInt

/**
 * Foreground service that performs one spot check and reports it to the phone.
 * Started by [WatchListenerService] (phone trigger) or [MainActivity] (button).
 *
 * A spot check runs three things: a raw PPG capture that is turned into an SpO2
 * estimate, a Health Services heart-rate reading, and a Health Connect lookup
 * of the last sleep SpO2 for comparison.
 */
class MeasureService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannel()

        if (!Permissions.hasHeartRate(this)) {
            // Cannot start a "health" foreground service without the sensor permission.
            val note = getString(R.string.perm_missing)
            MeasurementBus.set(MeasurementState.Failed(note))
            notify(getString(R.string.notif_done_title), note)
            scope.launch {
                WearLink(this@MeasureService).broadcast(
                    Protocol.PATH_RESULT,
                    Reading(measuredAt = System.currentTimeMillis(), heartRateBpm = null, note = note).toBytes(),
                )
                stopSelf(startId)
            }
            return START_NOT_STICKY
        }

        if (job?.isActive == true) return START_NOT_STICKY

        try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildNotification(getString(R.string.notif_measuring), getString(R.string.state_measuring)),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH,
            )
        } catch (e: Exception) {
            // Background start refused (e.g. ForegroundServiceStartNotAllowedException).
            // The Data Layer binding keeps the process alive long enough for a short check.
            Log.w(TAG, "Could not enter foreground, measuring anyway", e)
        }

        job = scope.launch {
            val reading = runSpotCheck()
            MeasurementBus.set(MeasurementState.Sending)
            WearLink(this@MeasureService).broadcast(Protocol.PATH_RESULT, reading.toBytes())
            MeasurementBus.set(MeasurementState.Done(reading))
            ServiceCompat.stopForeground(this@MeasureService, ServiceCompat.STOP_FOREGROUND_REMOVE)
            notify(getString(R.string.notif_done_title), summarize(reading))
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    private suspend fun runSpotCheck(): Reading = coroutineScope {
        val notes = mutableListOf<String>()
        val config = Spo2Settings.load(this@MeasureService)
        MeasurementBus.set(MeasurementState.Measuring)

        // Raw PPG and Health Services heart rate run at the same time so the
        // check takes about as long as the capture itself.
        val ppgJob = async {
            val ppg = PpgCapture(this@MeasureService)
            try {
                val capture = ppg.capture(config.captureSeconds)
                if (capture == null) {
                    val sensor = ppg.findSensor()
                    notes += if (sensor == null) "No PPG sensor found. Sensors: " + ppg.sensorNames().joinToString(", ")
                    else "Could not register PPG listener (permission?)"
                    null
                } else {
                    runCatching { ppg.saveCsv(capture) }
                    capture
                }
            } catch (e: Exception) {
                notes += "PPG: ${e.message ?: e.javaClass.simpleName}"
                null
            }
        }
        val hrJob = async {
            val measurer = HeartRateMeasurer(this@MeasureService)
            try {
                if (measurer.isSupported()) measurer.measure(Duration.ofSeconds(config.captureSeconds.toLong() + 10)) else null
            } catch (e: Exception) {
                notes += "HR: ${e.message ?: e.javaClass.simpleName}"
                null
            }
        }

        val capture = ppgJob.await()
        val bpm = hrJob.await()
        if (bpm == null && notes.none { it.startsWith("HR") }) notes += getString(R.string.heart_rate_unavailable)

        val analysis = capture?.let { Spo2Estimator.analyze(it.samples, it.timestampsNs, config) }
        analysis?.note?.let { notes += it }

        MeasurementBus.set(MeasurementState.ReadingSpo2)
        val sleep = try {
            Spo2Source(this@MeasureService).latest()
        } catch (e: Exception) {
            notes += "Health Connect: ${e.message ?: e.javaClass.simpleName}"
            null
        }

        Reading(
            measuredAt = System.currentTimeMillis(),
            heartRateBpm = bpm,
            spo2Estimate = analysis?.spo2,
            ratio = analysis?.ratio,
            ppgPulseBpm = analysis?.pulseHz?.let { (it * 60).roundToInt() },
            redChannel = analysis?.redChannel,
            irChannel = analysis?.irChannel,
            sensorName = capture?.sensor?.name,
            sampleRateHz = analysis?.sampleRateHz,
            channels = analysis?.channels ?: emptyList(),
            sleepSpo2Percent = sleep?.percent,
            sleepSpo2Time = sleep?.time?.toEpochMilli(),
            note = notes.takeIf { it.isNotEmpty() }?.joinToString("; "),
        )
    }

    private fun summarize(r: Reading): String {
        val spo2 = r.spo2Estimate?.let { getString(R.string.spo2_est_fmt, it) } ?: getString(R.string.spo2_est_unavailable)
        val hr = r.heartRateBpm?.let { getString(R.string.heart_rate_fmt, it) } ?: getString(R.string.heart_rate_unavailable)
        return "$spo2 · $hr"
    }

    private fun buildNotification(title: String, text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun notify(title: String, text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(RESULT_NOTIFICATION_ID, buildNotification(title, text))
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, getString(R.string.channel_measure), NotificationManager.IMPORTANCE_LOW,
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "MeasureService"
        private const val CHANNEL_ID = "measure"
        private const val NOTIFICATION_ID = 1
        private const val RESULT_NOTIFICATION_ID = 2

        fun start(context: Context) {
            context.startForegroundService(Intent(context, MeasureService::class.java))
        }
    }
}
