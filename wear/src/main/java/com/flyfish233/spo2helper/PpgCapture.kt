package com.flyfish233.spo2helper

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.PowerManager
import kotlinx.coroutines.delay
import java.io.File
import java.util.Locale

/**
 * Reads the watch's raw optical (PPG) sensor through the ordinary SensorManager.
 *
 * Pixel Watch exposes it as a vendor sensor named "AFE4950 PPG Sensor" with 16
 * values per event (the TI AFE4950 front end's time slots). Which slot is red
 * and which is infrared is not documented, so callers get every channel and
 * [Spo2Estimator] works it out.
 */
class PpgCapture(private val context: Context) {

    private val sensorManager = context.getSystemService(SensorManager::class.java)

    class Capture(val sensor: Sensor, val samples: List<FloatArray>, val timestampsNs: LongArray)

    /** The PPG sensor if this watch exposes one. */
    fun findSensor(): Sensor? = sensorManager.getSensorList(Sensor.TYPE_ALL).firstOrNull {
        it.name.contains("ppg", ignoreCase = true) || it.stringType.contains("ppg", ignoreCase = true)
    }

    /** Names of every sensor on the device, for diagnostics when no PPG is found. */
    fun sensorNames(): List<String> = sensorManager.getSensorList(Sensor.TYPE_ALL).map { it.name }

    /**
     * Captures [seconds] of samples at the fastest rate the sensor offers.
     * Returns null when there is no PPG sensor or the listener could not register
     * (usually a missing body-sensor permission).
     */
    suspend fun capture(seconds: Int): Capture? {
        val sensor = findSensor() ?: return null
        val samples = ArrayList<FloatArray>(seconds * 200)
        val stamps = ArrayList<Long>(seconds * 200)
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                // SensorEvent objects are recycled by the framework; copy the values.
                samples += event.values.copyOf()
                stamps += event.timestamp
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }

        val wakeLock = context.getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "spo2helper:ppg")
        wakeLock.acquire((seconds + 5) * 1000L)
        try {
            val ok = sensorManager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_FASTEST, 0)
            if (!ok) return null
            delay(seconds * 1000L)
        } finally {
            sensorManager.unregisterListener(listener)
            if (wakeLock.isHeld) wakeLock.release()
        }
        return Capture(sensor, samples, stamps.toLongArray())
    }

    /**
     * Writes the capture as CSV to app storage so it can be pulled with adb for
     * offline analysis. Overwrites the previous capture.
     */
    fun saveCsv(capture: Capture): File {
        val file = File(context.filesDir, "ppg-last.csv")
        file.bufferedWriter().use { w ->
            w.write("# ${capture.sensor.name} | ${capture.sensor.stringType} | vendor=${capture.sensor.vendor}\n")
            w.write("timestamp_ns")
            for (c in 0 until (capture.samples.firstOrNull()?.size ?: 0)) w.write(",ch$c")
            w.write("\n")
            capture.samples.forEachIndexed { i, v ->
                w.write(capture.timestampsNs[i].toString())
                for (x in v) w.write(String.format(Locale.US, ",%.1f", x))
                w.write("\n")
            }
        }
        return file
    }
}
