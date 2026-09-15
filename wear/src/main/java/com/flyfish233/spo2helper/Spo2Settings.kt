package com.flyfish233.spo2helper

import android.content.Context
import com.flyfish233.spo2helper.shared.Spo2Config

/** Persists the channel assignment and calibration the phone pushes to the watch. */
object Spo2Settings {
    private const val PREFS = "spo2"
    private const val KEY = "config"

    fun load(context: Context): Spo2Config {
        val json = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
        return json?.let { runCatching { Spo2Config.fromJson(it) }.getOrNull() } ?: Spo2Config()
    }

    fun save(context: Context, config: Spo2Config) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, config.toJson()).apply()
    }
}
