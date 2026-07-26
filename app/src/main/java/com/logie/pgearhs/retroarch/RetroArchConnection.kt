package com.logie.pgearhs.retroarch

import android.content.Context

/** Host/port for RetroArch's UDP Network Command Interface, persisted across sessions. */
object RetroArchConnection {

    private const val PREFS_NAME = "pgearhs_settings"
    private const val KEY_HOST = "retroarch_host"
    private const val KEY_PORT = "retroarch_port"

    const val DEFAULT_PORT = 55355

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getHost(context: Context): String = prefs(context).getString(KEY_HOST, "127.0.0.1") ?: "127.0.0.1"

    fun setHost(context: Context, host: String) {
        prefs(context).edit().putString(KEY_HOST, host).apply()
    }

    fun getPort(context: Context): Int = prefs(context).getInt(KEY_PORT, DEFAULT_PORT)

    fun setPort(context: Context, port: Int) {
        prefs(context).edit().putInt(KEY_PORT, port).apply()
    }
}
