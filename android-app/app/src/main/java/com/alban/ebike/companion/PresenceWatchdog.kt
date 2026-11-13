package com.alban.ebike.companion

import android.content.Context

/** Suppress repeated presence callbacks after dismissal until the ESP leaves or the user opens the app. */
object PresenceWatchdog {
    private fun preferences(context: Context) = context.getSharedPreferences("presence-watchdog", Context.MODE_PRIVATE)
    fun blocked(context: Context) = preferences(context).getBoolean("dismissed", false)
    fun dismiss(context: Context) { preferences(context).edit().putBoolean("dismissed", true).commit() }
    fun rearm(context: Context) { preferences(context).edit().putBoolean("dismissed", false).commit() }
}
