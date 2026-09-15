package com.flyfish233.spo2helper

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.flyfish233.spo2helper.shared.Spo2Source

object Permissions {
    /** Permission Health Services needs for heart rate on this OS version. */
    val heartRate: String =
        if (Build.VERSION.SDK_INT >= 36) "android.permission.health.READ_HEART_RATE"
        else Manifest.permission.BODY_SENSORS

    fun hasHeartRate(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, heartRate) == PackageManager.PERMISSION_GRANTED

    /** Everything the watch app should ask for on first launch. */
    fun toRequest(context: Context): Array<String> {
        val list = mutableListOf(heartRate, Manifest.permission.POST_NOTIFICATIONS)
        if (Spo2Source(context).isAvailable()) list += Spo2Source(context).readPermission
        return list.toTypedArray()
    }
}
