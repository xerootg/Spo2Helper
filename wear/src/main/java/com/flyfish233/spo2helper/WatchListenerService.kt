package com.flyfish233.spo2helper

import com.flyfish233.spo2helper.shared.Protocol
import com.flyfish233.spo2helper.shared.Spo2Config
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService

/** Receives the phone's messages: a "measure" trigger or a new SpO2 configuration. */
class WatchListenerService : WearableListenerService() {
    override fun onMessageReceived(messageEvent: MessageEvent) {
        when (messageEvent.path) {
            Protocol.PATH_MEASURE -> MeasureService.start(this)
            Protocol.PATH_CONFIG -> runCatching { Spo2Config.fromBytes(messageEvent.data) }
                .getOrNull()?.let { Spo2Settings.save(this, it) }
        }
    }
}
