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
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.alban.ebike.MainActivity
import com.alban.ebike.companion.DashboardLauncher
import com.alban.ebike.companion.BikeArrivalNotification
import com.alban.ebike.bluetooth.BikeGattClient
import com.alban.ebike.data.BikeSettingsStore
import com.alban.ebike.data.RideStateStore
import com.alban.ebike.location.RideLocationEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import androidx.core.content.ContextCompat
import android.Manifest

class RideService : Service() {
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(serviceJob + Dispatchers.IO)
    private lateinit var gattClient: BikeGattClient
    private lateinit var locationEngine: RideLocationEngine
    private lateinit var settings: BikeSettingsStore
    private lateinit var wakeLock: PowerManager.WakeLock
    @Volatile private var connectedBike: String? = null
    private var wheelInitialized = false
    private var shuttingDown = false
    private var notificationStarted = false
    private var dashboardOpenedForConnection = false
    @Volatile private var locationStarted = false
    private var associationPending = false

    override fun onCreate() {
        super.onCreate()
        com.alban.ebike.data.RideTrackJournal.initialize(this)
        createNotificationChannel()
        locationEngine = RideLocationEngine(this)
        settings = BikeSettingsStore(this)
        serviceScope.launch {
            val source = com.alban.ebike.terrain.IgnTerrainSource(this@RideService)
            val tileStore = com.alban.ebike.terrain.ElevationTileStore(java.io.File(filesDir, "elevation-tiles-v1"))
            var grid: com.alban.ebike.terrain.TerrainGrid? = null
            while (isActive) {
                val position = RideStateStore.state.value.position
                if (position != null && locationStarted) {
                    val current = grid
                    val p = current?.let { com.alban.ebike.scene.GeoFrame.local(position.latitude, position.longitude, it.originLat, it.originLon) }
                    if (current == null || p == null || current.sample(p.east, p.north) == null ||
                        kotlin.math.abs(p.east) > current.halfSizeM - 80 || kotlin.math.abs(p.north) > current.halfSizeM - 80) {
                        val cached = com.alban.ebike.terrain.RideElevation.cachedGrid(tileStore, position.latitude, position.longitude)
                        if (cached != null) {
                            grid = cached
                            RideStateStore.setElevationGrid(cached)
                        } else {
                            val loaded = source.load(position.latitude, position.longitude,
                                com.alban.ebike.terrain.RideElevation.HALF_SIZE, com.alban.ebike.terrain.RideElevation.SIZE)
                            if (loaded != null && loaded.halfSizeM * 2 / (loaded.size - 1) <= 5.01) {
                                grid = com.alban.ebike.terrain.TerrainGrid(loaded.originLat, loaded.originLon,
                                    loaded.halfSizeM, loaded.size, loaded.heights, "IGN", true)
                                RideStateStore.setElevationGrid(grid)
                            }
                        }
                    }
                }
                kotlinx.coroutines.delay(2000)
            }
        }
        wakeLock = getSystemService(PowerManager::class.java).newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "$packageName:ride",
        ).apply { setReferenceCounted(false) }
        serviceScope.launch(Dispatchers.Main.immediate) {
            while (kotlinx.coroutines.currentCoroutineContext().isActive) {
                RideStateStore.expireGpsSpeed()
                kotlinx.coroutines.delay(1000)
            }
        }
        serviceScope.launch {
            while (kotlinx.coroutines.currentCoroutineContext().isActive) {
                kotlinx.coroutines.delay(3000)
                val ride = RideStateStore.state.value
                if (ride.resting && ride.restTimeMs >= 3000) RideStateStore.evaluateWheelCalibration()
            }
        }
        gattClient = BikeGattClient(
            context = this,
            onReady = { address ->
                if (associationPending || shuttingDown) return@BikeGattClient
                connectedBike = address
                wheelInitialized = false
                RideStateStore.setBluetoothReady(false)
                RideStateStore.activateBike(address)
                com.alban.ebike.data.BikeProfiles.activate(address)
                val options = com.alban.ebike.data.BikeProfiles.preferences("ride-options")
                RideStateStore.movingThresholdKmh = options.getFloat("moving-threshold", 4f)
                RideStateStore.gradePointCount = options.getInt("grade-points", 10)
                RideStateStore.setBluetoothReady(false)
                acquireWakeLock()
                updateNotification(statusMessage())
                openDashboard()
            },
            onTelemetry = { address, telemetry ->
                if (connectedBike == address && !shuttingDown) {
                    if (!wheelInitialized && telemetry.circumferenceMm in 1000..4000) {
                        val saved = settings.initializeWheel(telemetry.circumferenceMm, address)
                        wheelInitialized = true
                        if (saved != telemetry.circumferenceMm) gattClient.setWheelCircumference(saved, expectedAddress = address)
                    }
                    RideStateStore.setBluetoothReady(wheelInitialized)
                    RideStateStore.ingestTelemetry(telemetry)
                }
            },
            onStatus = RideStateStore::setBluetoothStatus,
            onDisconnected = {
                connectedBike = null
                wheelInitialized = false
                BikeArrivalNotification.dismiss(this)
                RideStateStore.setBluetoothReady(false)
                if (!locationStarted) releaseWakeLock()
                dashboardOpenedForConnection = false
                updateNotification(statusMessage())
            },
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopForDismissal()
            return START_NOT_STICKY
        }
        if (intent?.getBooleanExtra(EXTRA_VISIBLE, false) == true) {
            com.alban.ebike.companion.PresenceWatchdog.rearm(this)
        } else if (com.alban.ebike.companion.PresenceWatchdog.blocked(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        val allowLocation = canRecordLocation(intent?.getBooleanExtra(EXTRA_VISIBLE, false) == true)
        if (!startAsForeground(allowLocation)) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_ASSOCIATE) {
            associationPending = true
            // The single-connection peripheral must advertise for Android's
            // association picker; release our direct GATT connection first.
            gattClient.stop()
            RideStateStore.setBluetoothReady(false)
            dashboardOpenedForConnection = false
            updateNotification("Association ESP32 en cours")
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_RESUME) associationPending = false
        if (associationPending) return START_NOT_STICKY
        gattClient.start()
        if (allowLocation) {
            locationEngine.start()
            locationStarted = true
            acquireWakeLock()
        } else {
            RideStateStore.setGpsStatus("Autorisation GPS nécessaire · ouvrir l’application")
        }
        updateNotification(statusMessage())
        when (intent?.action) {
            ACTION_SET_CIRCUMFERENCE -> {
                intent.getIntExtra(EXTRA_CIRCUMFERENCE_MM, -1)
                    .takeIf { it in 1000..4000 }
                    ?.let { gattClient.setWheelCircumference(it, expectedAddress = intent.getStringExtra(EXTRA_BIKE_ID)) }
            }
            ACTION_TOGGLE_MODE -> {
                val state = RideStateStore.state.value
                val bike = intent.getStringExtra(EXTRA_BIKE_ID)
                if (state.bluetoothReady && state.modeSupported && bike == connectedBike) {
                    gattClient.setWheelCircumference(settings.currentCircumference(bike!!), !state.speedMode, bike)
                }
            }
            else -> Unit
        }
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        stopForDismissal()
        super.onTaskRemoved(rootIntent)
    }

    private fun stopForDismissal() {
        com.alban.ebike.companion.PresenceWatchdog.dismiss(this)
        shutdown()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun shutdown() {
        if (shuttingDown) {
            return
        }
        shuttingDown = true
        connectedBike = null
        notificationStarted = false
        BikeArrivalNotification.dismiss(this)
        gattClient.stop()
        locationEngine.stop()
        RideStateStore.setBluetoothReady(false)
        RideStateStore.setBluetoothStatus("Service arrêté")
        RideStateStore.setGpsStatus("GPS arrêté")
        releaseWakeLock()
        RideStateStore.suspendTimers()
        serviceJob.cancel()
    }

    override fun onDestroy() {
        shutdown()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startAsForeground(allowLocation: Boolean): Boolean {
        var serviceTypes = 0
        if (android.os.Build.VERSION.SDK_INT < 31 ||
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
            serviceTypes = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        }
        if (allowLocation) serviceTypes = serviceTypes or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        if (serviceTypes == 0) return false
        notificationStarted = true
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification("Recherche de l’ESP32"),
            serviceTypes,
        )
        return true
    }

    private fun statusMessage(): String =
        (if (locationStarted) "GPS actif" else "GPS en attente") +
            (if (RideStateStore.state.value.bluetoothReady) " · ESP32 connecté" else " · recherche ESP32")

    private fun updateNotification(message: String) {
        if (!notificationStarted) return
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification(message))
    }

    private fun notification(message: String) = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.ic_menu_compass)
        .setContentTitle("E-BikeCockpit")
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
        .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Arrêter", PendingIntent.getService(
            this, 1, Intent(this, RideService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        ))
        .build()

    private fun createNotificationChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "E-BikeCockpit enregistrement", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun canRecordLocation(fromVisibleActivity: Boolean): Boolean {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED) return false
        // Automatic start from a companion callback needs all-the-time location
        // permission on Android 10+. The onboarding screen asks for it once.
        return fromVisibleActivity || locationStarted ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
    }

    @android.annotation.SuppressLint("WakelockTimeout")
    private fun acquireWakeLock() {
        if (!wakeLock.isHeld) wakeLock.acquire()
    }

    private fun releaseWakeLock() {
        if (wakeLock.isHeld) wakeLock.release()
    }

    private fun openDashboard() {
        if (dashboardOpenedForConnection) return
        dashboardOpenedForConnection = DashboardLauncher.openIfUnlocked(this)
    }

    companion object {
        private const val ACTION_TOGGLE_MODE = "com.alban.ebike.TOGGLE_MODE"
        fun toggleMode(context: Context, bikeId: String = com.alban.ebike.data.BikeProfiles.activeId.value) {
            ContextCompat.startForegroundService(context, Intent(context, RideService::class.java)
                .setAction(ACTION_TOGGLE_MODE).putExtra(EXTRA_BIKE_ID, bikeId).putExtra(EXTRA_VISIBLE, context is android.app.Activity))
        }
        private const val CHANNEL_ID = "ebike_ride"
        private const val NOTIFICATION_ID = 41
        private const val ACTION_SET_CIRCUMFERENCE = "com.alban.ebike.SET_CIRCUMFERENCE"
        private const val EXTRA_CIRCUMFERENCE_MM = "circumference_mm"
        private const val EXTRA_BIKE_ID = "bike_profile"
        private const val EXTRA_VISIBLE = "visible_activity"
        private const val ACTION_STOP = "com.alban.ebike.STOP"
        private const val ACTION_ASSOCIATE = "com.alban.ebike.ASSOCIATE"
        private const val ACTION_RESUME = "com.alban.ebike.RESUME_AFTER_ASSOCIATION"

        fun resumeAfterAssociation(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, RideService::class.java)
                .setAction(ACTION_RESUME).putExtra(EXTRA_VISIBLE, context is android.app.Activity))
        }

        fun prepareAssociation(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, RideService::class.java)
                .setAction(ACTION_ASSOCIATE).putExtra(EXTRA_VISIBLE, true))
        }

        fun start(context: Context) {
            val intent = Intent(context, RideService::class.java)
                .putExtra(EXTRA_VISIBLE, context is android.app.Activity)
            androidx.core.content.ContextCompat.startForegroundService(context, intent)
        }

        fun setWheelCircumference(context: Context, circumferenceMm: Int, bikeId: String = com.alban.ebike.data.BikeProfiles.activeId.value) {
            val intent = Intent(context, RideService::class.java).apply {
                action = ACTION_SET_CIRCUMFERENCE
                putExtra(EXTRA_BIKE_ID, bikeId)
                putExtra(EXTRA_VISIBLE, context is android.app.Activity)
                putExtra(EXTRA_CIRCUMFERENCE_MM, circumferenceMm)
            }
            androidx.core.content.ContextCompat.startForegroundService(context, intent)
        }
    }
}
