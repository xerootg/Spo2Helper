package com.flyfish233.spo2helper

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context

object Notifications {
    const val CHANNEL_MONITOR = "monitor"
    const val CHANNEL_RESULTS = "results"
    const val ID_MONITOR = 12
    const val ID_RESULT = 13

    fun ensureChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_MONITOR,
                context.getString(R.string.channel_monitor),
                NotificationManager.IMPORTANCE_MIN,
            )
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_RESULTS,
                context.getString(R.string.channel_results),
                NotificationManager.IMPORTANCE_DEFAULT,
            )
        )
    }
}
