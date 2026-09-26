package com.alban.ebike.companion

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import com.alban.ebike.MainActivity

/** Called only in response to companion presence or a new BLE connection. */
object DashboardLauncher {
    private var lastAttemptMs = -10_000L

    @Synchronized
    fun openIfUnlocked(context: Context): Boolean {
        if (!context.getSystemService(PowerManager::class.java).isInteractive ||
            context.getSystemService(KeyguardManager::class.java).isKeyguardLocked) {
            BikeArrivalNotification.show(context)
            return true
        }
        val now = SystemClock.elapsedRealtime()
        if (now - lastAttemptMs < 5000) return true
        return try {
            context.startActivity(Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP)
            })
            lastAttemptMs = now
            Log.i("EBikeAutoOpen", "Dashboard requested from device presence/connection")
            true
        } catch (error: RuntimeException) {
            Log.w("EBikeAutoOpen", "Dashboard launch failed; notification remains available", error)
            false
        }
    }
}
