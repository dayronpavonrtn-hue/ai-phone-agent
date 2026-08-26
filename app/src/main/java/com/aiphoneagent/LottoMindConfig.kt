package com.aiphoneagent

import android.content.Context

object LottoMindConfig {
    private const val PREFS = "lottomind"
    private const val KEY_BASE_URL = "base_url"
    private const val KEY_ENABLED = "enabled"

    fun baseUrl(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_BASE_URL, "http://192.168.1.243:3000")
            ?.trimEnd('/')
            ?: "http://192.168.1.243:3000"

    fun setBaseUrl(context: Context, value: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_BASE_URL, value.trim().trimEnd('/')).apply()
    }

    fun enabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun lastDrawKey(context: Context, game: String): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString("last_draw_$game", null)

    fun setLastDrawKey(context: Context, game: String, key: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString("last_draw_$game", key).apply()
    }
}
