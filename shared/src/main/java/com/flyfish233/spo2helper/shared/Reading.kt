package com.flyfish233.spo2helper.shared

import org.json.JSONArray
import org.json.JSONObject

/** Per-channel statistics of one raw PPG capture. */
data class ChannelStats(
    val index: Int,
    /** Mean level of the channel (raw ADC units). */
    val dc: Double,
    /** Peak-to-peak amplitude at the dominant pulse frequency (raw ADC units). */
    val ac: Double,
    /** Frequency of the strongest component in the 0.6 to 3.5 Hz band. */
    val dominantHz: Double,
    /** Peak power divided by median power in that band. Higher means cleaner pulse. */
    val snr: Double,
    /** True if this channel shows a clean pulse at the shared heart rate. */
    val pulsatile: Boolean,
) {
    /** Perfusion index: AC relative to DC. */
    val perfusion: Double get() = if (dc == 0.0) 0.0 else ac / kotlin.math.abs(dc)

    fun toJson(): JSONObject = JSONObject().apply {
        put("index", index); put("dc", dc); put("ac", ac)
        put("dominantHz", dominantHz); put("snr", snr); put("pulsatile", pulsatile)
    }

    companion object {
        fun fromJson(o: JSONObject) = ChannelStats(
            index = o.getInt("index"), dc = o.getDouble("dc"), ac = o.getDouble("ac"),
            dominantHz = o.getDouble("dominantHz"), snr = o.getDouble("snr"),
            pulsatile = o.optBoolean("pulsatile", false),
        )
    }
}

/**
 * One spot check taken on the watch.
 *
 * [spo2Estimate] is computed on the watch from the raw red and infrared PPG
 * channels. It is not a calibrated medical measurement. [sleepSpo2Percent] is
 * the value Google Health wrote to Health Connect during sleep, for comparison.
 */
data class Reading(
    /** Wall-clock time the spot check finished, epoch millis. */
    val measuredAt: Long,
    /** Live heart rate from Health Services, or null. */
    val heartRateBpm: Int?,
    /** SpO2 estimated from raw PPG, or null. */
    val spo2Estimate: Double? = null,
    /** Ratio of ratios R the estimate was derived from, or null. */
    val ratio: Double? = null,
    /** Pulse rate derived from the PPG signal itself, or null. */
    val ppgPulseBpm: Int? = null,
    /** Channel indices used for red and infrared, or null. */
    val redChannel: Int? = null,
    val irChannel: Int? = null,
    /** Name of the raw sensor used, or null if none was found. */
    val sensorName: String? = null,
    /** Effective sample rate of the capture in Hz, or null. */
    val sampleRateHz: Double? = null,
    /** Per-channel statistics from the capture. */
    val channels: List<ChannelStats> = emptyList(),
    /** Latest sleep SpO2 from Health Connect, or null. */
    val sleepSpo2Percent: Double? = null,
    /** Time of that Health Connect record in epoch millis, or null. */
    val sleepSpo2Time: Long? = null,
    /** Human readable notes about anything that went wrong, or null. */
    val note: String? = null,
) {
    fun toJson(): String = JSONObject().apply {
        put("measuredAt", measuredAt)
        heartRateBpm?.let { put("heartRateBpm", it) }
        spo2Estimate?.let { put("spo2Estimate", it) }
        ratio?.let { put("ratio", it) }
        ppgPulseBpm?.let { put("ppgPulseBpm", it) }
        redChannel?.let { put("redChannel", it) }
        irChannel?.let { put("irChannel", it) }
        sensorName?.let { put("sensorName", it) }
        sampleRateHz?.let { put("sampleRateHz", it) }
        if (channels.isNotEmpty()) put("channels", JSONArray().also { arr -> channels.forEach { arr.put(it.toJson()) } })
        sleepSpo2Percent?.let { put("sleepSpo2Percent", it) }
        sleepSpo2Time?.let { put("sleepSpo2Time", it) }
        note?.let { put("note", it) }
    }.toString()

    fun toBytes(): ByteArray = toJson().toByteArray(Charsets.UTF_8)

    companion object {
        fun fromJson(json: String): Reading {
            val o = JSONObject(json)
            fun dbl(k: String) = if (o.has(k)) o.getDouble(k) else null
            fun int(k: String) = if (o.has(k)) o.getInt(k) else null
            fun lng(k: String) = if (o.has(k)) o.getLong(k) else null
            fun str(k: String) = if (o.has(k)) o.getString(k) else null
            val channels = o.optJSONArray("channels")?.let { arr ->
                (0 until arr.length()).map { ChannelStats.fromJson(arr.getJSONObject(it)) }
            } ?: emptyList()
            return Reading(
                measuredAt = o.getLong("measuredAt"),
                heartRateBpm = int("heartRateBpm"),
                spo2Estimate = dbl("spo2Estimate"),
                ratio = dbl("ratio"),
                ppgPulseBpm = int("ppgPulseBpm"),
                redChannel = int("redChannel"),
                irChannel = int("irChannel"),
                sensorName = str("sensorName"),
                sampleRateHz = dbl("sampleRateHz"),
                channels = channels,
                sleepSpo2Percent = dbl("sleepSpo2Percent"),
                sleepSpo2Time = lng("sleepSpo2Time"),
                note = str("note"),
            )
        }

        fun fromBytes(bytes: ByteArray): Reading = fromJson(String(bytes, Charsets.UTF_8))
    }
}
