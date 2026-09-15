package com.flyfish233.spo2helper

import android.content.Context
import com.flyfish233.spo2helper.shared.Reading
import com.flyfish233.spo2helper.shared.Spo2Config
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Keeps the last reading from the watch and the SpO2 configuration, in memory and in SharedPreferences. */
object ReadingStore {
    private const val PREFS = "prefs"
    private const val KEY_LAST = "last_reading"
    private const val KEY_INTERVAL = "interval_minutes"
    private const val KEY_CONFIG = "spo2_config"

    private val _latest = MutableStateFlow<Reading?>(null)
    val latest: StateFlow<Reading?> = _latest

    private val _config = MutableStateFlow(Spo2Config())
    val config: StateFlow<Spo2Config> = _config

    fun load(context: Context) {
        val prefs = prefs(context)
        if (_latest.value == null) {
            prefs.getString(KEY_LAST, null)?.let { json ->
                _latest.value = runCatching { Reading.fromJson(json) }.getOrNull()
            }
        }
        prefs.getString(KEY_CONFIG, null)?.let { json ->
            runCatching { Spo2Config.fromJson(json) }.getOrNull()?.let { _config.value = it }
        }
    }

    fun save(context: Context, reading: Reading) {
        prefs(context).edit().putString(KEY_LAST, reading.toJson()).apply()
        _latest.value = reading
    }

    fun saveConfig(context: Context, config: Spo2Config) {
        prefs(context).edit().putString(KEY_CONFIG, config.toJson()).apply()
        _config.value = config
    }

    fun intervalMinutes(context: Context): Int = prefs(context).getInt(KEY_INTERVAL, 10)

    fun setIntervalMinutes(context: Context, minutes: Int) {
        prefs(context).edit().putInt(KEY_INTERVAL, minutes).apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
