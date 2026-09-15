package com.flyfish233.spo2helper.shared

import org.json.JSONObject

/**
 * How the watch turns raw PPG channels into an SpO2 estimate.
 *
 * SpO2 = [a] - [b] * R, where R = (AC_red/DC_red) / (AC_ir/DC_ir). The classic
 * uncalibrated constants are a=110, b=25. [redChannel]/[irChannel] of -1 means
 * "pick automatically".
 */
data class Spo2Config(
    val redChannel: Int = AUTO,
    val irChannel: Int = AUTO,
    val a: Double = 110.0,
    val b: Double = 25.0,
    val captureSeconds: Int = 20,
) {
    fun toJson(): String = JSONObject().apply {
        put("redChannel", redChannel)
        put("irChannel", irChannel)
        put("a", a)
        put("b", b)
        put("captureSeconds", captureSeconds)
    }.toString()

    fun toBytes(): ByteArray = toJson().toByteArray(Charsets.UTF_8)

    companion object {
        const val AUTO = -1

        fun fromJson(json: String): Spo2Config {
            val o = JSONObject(json)
            return Spo2Config(
                redChannel = o.optInt("redChannel", AUTO),
                irChannel = o.optInt("irChannel", AUTO),
                a = o.optDouble("a", 110.0),
                b = o.optDouble("b", 25.0),
                captureSeconds = o.optInt("captureSeconds", 20),
            )
        }

        fun fromBytes(bytes: ByteArray): Spo2Config = fromJson(String(bytes, Charsets.UTF_8))
    }
}
