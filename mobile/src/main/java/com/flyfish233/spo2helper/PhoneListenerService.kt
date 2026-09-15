package com.flyfish233.spo2helper

import android.app.PendingIntent
import android.app.NotificationManager
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.flyfish233.spo2helper.shared.Protocol
import com.flyfish233.spo2helper.shared.Reading
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService

/** Receives spot-check results from the watch, stores them and shows a notification. */
class PhoneListenerService : WearableListenerService() {

    override fun onMessageReceived(messageEvent: MessageEvent) {
        if (messageEvent.path != Protocol.PATH_RESULT) return
        val reading = runCatching { Reading.fromBytes(messageEvent.data) }.getOrNull() ?: return
        ReadingStore.save(this, reading)
        showResult(reading)
    }

    private fun showResult(reading: Reading) {
        Notifications.ensureChannels(this)
        val spo2 = reading.spo2Estimate?.let { getString(R.string.result_spo2_short_fmt, it) }
            ?: getString(R.string.spo2_est_unavailable)
        val hr = reading.heartRateBpm?.let { getString(R.string.result_hr_short_fmt, it) }
            ?: getString(R.string.heart_rate_unavailable)
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(this, Notifications.CHANNEL_RESULTS)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.result_notif_title))
            .setContentText("$spo2 · $hr")
            .setStyle(NotificationCompat.BigTextStyle().bigText(listOfNotNull(spo2, hr, reading.note).joinToString("\n")))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(Notifications.ID_RESULT, notification)
    }
}
