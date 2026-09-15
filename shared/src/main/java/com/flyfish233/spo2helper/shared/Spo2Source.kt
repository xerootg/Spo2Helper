package com.flyfish233.spo2helper.shared

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Duration
import java.time.Instant

/** Latest SpO2 value found in Health Connect. */
data class Spo2Sample(val percent: Double, val time: Instant)

/**
 * Reads the most recent blood-oxygen record from Health Connect.
 *
 * Works the same on the phone (Android 14+) and on the watch (Wear OS 6+).
 * Google Health / Fitbit writes Pixel Watch sleep SpO2 here once the user has
 * connected it to Health Connect.
 */
class Spo2Source(private val context: Context) {

    val readPermission: String = HealthPermission.getReadPermission(OxygenSaturationRecord::class)

    /** True if Health Connect is present on this device. */
    fun isAvailable(): Boolean =
        HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE

    suspend fun hasPermission(): Boolean {
        if (!isAvailable()) return false
        val client = HealthConnectClient.getOrCreate(context)
        return client.permissionController.getGrantedPermissions().contains(readPermission)
    }

    /**
     * Newest record within [lookBack] of now, or null when Health Connect is
     * missing, permission is not granted, or there is no data.
     */
    suspend fun latest(lookBack: Duration = Duration.ofDays(7)): Spo2Sample? {
        if (!hasPermission()) return null
        val client = HealthConnectClient.getOrCreate(context)
        val now = Instant.now()
        val response = client.readRecords(
            ReadRecordsRequest(
                recordType = OxygenSaturationRecord::class,
                timeRangeFilter = TimeRangeFilter.between(now.minus(lookBack), now),
                ascendingOrder = false,
                pageSize = 1,
            )
        )
        val record = response.records.firstOrNull() ?: return null
        return Spo2Sample(record.percentage.value, record.time)
    }
}
