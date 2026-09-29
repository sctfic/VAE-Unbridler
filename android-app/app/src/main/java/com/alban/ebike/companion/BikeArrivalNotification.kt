package com.alban.ebike.companion

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.alban.ebike.MainActivity

/** Separate from the quiet, persistent GPS service notification. */
object BikeArrivalNotification {
    private const val CHANNEL_ID = "ebike_arrival"
    private const val NOTIFICATION_ID = 42

    fun show(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(
            CHANNEL_ID, "ESP32 à proximité", NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Invitation à ouvrir E-BikeCockpit lorsque la carte est détectée et le téléphone verrouillé"
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
        })
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED) {
            Log.w("EBikeAutoOpen", "Arrival notification requires notification permission")
            return
        }
        val open = PendingIntent.getActivity(context, NOTIFICATION_ID,
            Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        manager.notify(NOTIFICATION_ID, NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentTitle("Votre vélo est prêt")
            .setContentText("ESP32 détecté · Touchez pour ouvrir E-BikeCockpit")
            .setContentIntent(open)
            .addAction(android.R.drawable.ic_menu_view, "Ouvrir E-BikeCockpit", open)
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .build())
        Log.i("EBikeAutoOpen", "Arrival notification posted for locked/inactive screen")
    }

    fun dismiss(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
    }
}
