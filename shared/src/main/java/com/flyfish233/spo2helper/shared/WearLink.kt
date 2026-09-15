package com.flyfish233.spo2helper.shared

import android.content.Context
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.tasks.await

/** Thin wrapper over the Wearable Data Layer MessageClient. */
class WearLink(private val context: Context) {

    /**
     * Sends [payload] on [path] to every connected node.
     * Returns the number of nodes the message was handed to.
     */
    suspend fun broadcast(path: String, payload: ByteArray = ByteArray(0)): Int {
        val nodes = Wearable.getNodeClient(context).connectedNodes.await()
        val messageClient = Wearable.getMessageClient(context)
        var sent = 0
        for (node in nodes) {
            try {
                messageClient.sendMessage(node.id, path, payload).await()
                sent++
            } catch (_: Exception) {
                // Node went away between listing and sending; try the rest.
            }
        }
        return sent
    }
}
