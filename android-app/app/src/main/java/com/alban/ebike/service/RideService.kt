package com.alban.ebike.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.alban.ebike.MainActivity
import com.alban.ebike.bluetooth.BikeGattClient
import com.alban.ebike.data.BikeSettingsStore
import com.alban.ebike.data.RideStateStore
import com.alban.ebike.location.RideLocationEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import androidx.core.content.ContextCompat
import android.Manifest

class RideService : Service() {
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(serviceJob + Dispatchers.IO)
    private lateinit var gattClient: BikeGattClient
    private lateinit var locationEngine: RideLocationEngine
    private lateinit var settings: BikeSettingsStore
    private var notificationStarted = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        locationEngine = RideLocationEngine(this)
        settings = BikeSettingsStore(this)
        gattClient = BikeGattClient(
            context = this,
            onReady = {
                RideStateStore.setBluetoothReady(true)
                if (canRecordLocation()) locationEngine.start() // GPS starts automatically once BLE is live.
                serviceScope.launch { gattClient.setWheelCircumference(settings.circumferenceMm.first()) }
                updateNotification("Connecté — GPS actif")
            },
            onTelemetry = RideStateStore::ingestTelemetry,
            onDisconnected = {
                RideStateStore.setBluetoothReady(false)
                locationEngine.stop()
                updateNotification("Recherche de l’ESP32")
            },
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startAsForeground()
        gattClient.start()
        when (intent?.action) {
            ACTION_SET_CIRCUMFERENCE -> {
                intent.getIntExtra(EXTRA_CIRCUMFERENCE_MM, -1)
                    .takeIf { it in 1000..4000 }
                    ?.let(gattClient::setWheelCircumference)
            }
            else -> Unit
        }
        return START_STICKY
    }

    override fun onDestroy() {
        gattClient.stop()
        locationEngine.stop()
        serviceJob.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startAsForeground() {
        if (notificationStarted) return
        notificationStarted = true
        var serviceTypes = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        if (canRecordLocation()) serviceTypes = serviceTypes or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification("Recherche de l’ESP32"),
            serviceTypes,
        )
    }

    private fun updateNotification(message: String) {
        if (!notificationStarted) return
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification(message))
    }

    private fun notification(message: String) = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.ic_menu_compass)
        .setContentTitle("E-Bike")
        .setContentText(message)
        .setContentIntent(
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ),
        )
        .setOngoing(true)
        .build()

    private fun createNotificationChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "E-Bike enregistrement", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun canRecordLocation(): Boolean {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED) return false
        // Automatic start from a companion callback needs all-the-time location
        // permission on Android 10+. The onboarding screen asks for it once.
        return android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
    }

    companion object {
        private const val CHANNEL_ID = "ebike_ride"
        private const val NOTIFICATION_ID = 41
        private const val ACTION_SET_CIRCUMFERENCE = "com.alban.ebike.SET_CIRCUMFERENCE"
        private const val EXTRA_CIRCUMFERENCE_MM = "circumference_mm"

        fun start(context: Context) {
            val intent = Intent(context, RideService::class.java)
            androidx.core.content.ContextCompat.startForegroundService(context, intent)
        }

        fun setWheelCircumference(context: Context, circumferenceMm: Int) {
            val intent = Intent(context, RideService::class.java).apply {
                action = ACTION_SET_CIRCUMFERENCE
                putExtra(EXTRA_CIRCUMFERENCE_MM, circumferenceMm)
            }
            androidx.core.content.ContextCompat.startForegroundService(context, intent)
        }
    }
}
