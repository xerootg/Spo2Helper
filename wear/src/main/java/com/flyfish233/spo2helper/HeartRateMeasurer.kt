package com.flyfish233.spo2helper

import android.content.Context
import androidx.health.services.client.HealthServices
import androidx.health.services.client.MeasureCallback
import androidx.health.services.client.data.Availability
import androidx.health.services.client.data.DataPointContainer
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.DeltaDataType
import androidx.health.services.client.data.HeartRateAccuracy
import androidx.health.services.client.data.HeartRateAccuracy.SensorStatus
import androidx.health.services.client.getCapabilities
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Duration

/**
 * Takes a single heart-rate reading through Health Services, which is the only
 * vital Pixel Watch exposes for on-demand measurement.
 */
class HeartRateMeasurer(context: Context) {

    private val measureClient = HealthServices.getClient(context).measureClient

    private data class Sample(val bpm: Int, val trusted: Boolean)

    suspend fun isSupported(): Boolean =
        measureClient.getCapabilities().supportedDataTypesMeasure.contains(DataType.HEART_RATE_BPM)

    /**
     * Waits for a medium- or high-accuracy sample, falling back to the last
     * non-zero sample seen when [timeout] expires. Null if nothing usable came in.
     */
    suspend fun measure(timeout: Duration): Int? {
        var fallback: Int? = null
        val trusted = withTimeoutOrNull(timeout.toMillis()) {
            samples()
                .filter { it.bpm > 0 }
                .onEach { fallback = it.bpm }
                .first { it.trusted }
        }
        return trusted?.bpm ?: fallback
    }

    private fun samples() = callbackFlow {
        val callback = object : MeasureCallback {
            override fun onAvailabilityChanged(dataType: DeltaDataType<*, *>, availability: Availability) {
                // Off-body / unavailable states just mean no samples arrive; the timeout handles it.
            }

            override fun onDataReceived(data: DataPointContainer) {
                for (point in data.getData(DataType.HEART_RATE_BPM)) {
                    val status = (point.accuracy as? HeartRateAccuracy)?.sensorStatus
                    val good = status == SensorStatus.ACCURACY_MEDIUM || status == SensorStatus.ACCURACY_HIGH
                    trySend(Sample(point.value.toInt(), good))
                }
            }

            override fun onRegistrationFailed(throwable: Throwable) {
                close(throwable)
            }
        }
        measureClient.registerMeasureCallback(DataType.HEART_RATE_BPM, callback)
        awaitClose { measureClient.unregisterMeasureCallbackAsync(DataType.HEART_RATE_BPM, callback) }
    }
}
