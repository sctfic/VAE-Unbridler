package com.alban.ebike.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Looper
import android.os.Handler
import android.os.SystemClock
import android.location.LocationManager
import com.google.android.gms.location.LocationAvailability
import android.util.Log
import androidx.core.content.ContextCompat
import com.alban.ebike.data.RideStateStore
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority

class RideLocationEngine(context: Context) {
    private val appContext = context.applicationContext
    private val client: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(appContext)
    private var started = false
    private var lastFixMs = 0L
    private var lastHeartbeatMs = 0L
    private val locationManager = appContext.getSystemService(LocationManager::class.java)
    init {
        GpsDebugLog.initialize(appContext)
        RideStateStore.movingThresholdKmh = appContext.getSharedPreferences("ride-options", Context.MODE_PRIVATE)
            .getFloat("moving-threshold", 4f).coerceIn(0f, 20f)
    }
    private val handler = Handler(Looper.getMainLooper())
    private val staleCheck = object : Runnable {
        override fun run() {
            if (!started) return
            RideStateStore.expireGpsSpeed()
            val now = SystemClock.elapsedRealtime()
            if (now - lastHeartbeatMs >= 5000) {
                lastHeartbeatMs = now
                val status = "GPS système=${locationManager.isLocationEnabled} · " +
                    "dernier fix ${if (lastFixMs == 0L) "jamais" else "${(now - lastFixMs) / 1000}s"}"
                if (lastFixMs == 0L || now - lastFixMs > 4000) RideStateStore.setGpsStatus(status)
                else GpsDebugLog.record(status)
            }
            handler.postDelayed(this, 1000)
        }
    }

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            lastFixMs = SystemClock.elapsedRealtime()
            result.locations.forEach(RideStateStore::ingestLocation)
        }
        override fun onLocationAvailability(availability: LocationAvailability) {
            GpsDebugLog.record("provider available=${availability.isLocationAvailable}")
        }
    }

    fun start() {
        if (started) return
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED) {
            RideStateStore.setGpsStatus("GPS : permission précise absente")
            return
        }
        GpsDebugLog.record("START model=${android.os.Build.MODEL} api=${android.os.Build.VERSION.SDK_INT} " +
            "locationEnabled=${locationManager.isLocationEnabled}")
        lastFixMs = 0L
        started = true
        RideStateStore.resetGpsSpeed()
        handler.removeCallbacks(staleCheck)
        handler.post(staleCheck)
        RideStateStore.setGpsStatus("Recherche GPS · sortir à ciel ouvert")
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1_000)
            .setMinUpdateIntervalMillis(500)
            .setWaitForAccurateLocation(false)
            .build()
        try {
            client.requestLocationUpdates(request, callback, Looper.getMainLooper())
                .addOnSuccessListener { GpsDebugLog.record("SUBSCRIBED high accuracy interval=1000ms") }
                .addOnFailureListener {
                    started = false
                    RideStateStore.setGpsStatus("GPS indisponible · vérifier la localisation")
                    Log.e("EBikeGPS", "Location subscription failed", it)
                    GpsDebugLog.record("SUBSCRIBE FAILED ${it.javaClass.simpleName}: ${it.message}")
                }
        } catch (error: SecurityException) {
            GpsDebugLog.record("SECURITY ${error.message}")
            started = false
            RideStateStore.setGpsStatus("Autorisation GPS nécessaire")
        }
    }

    fun stop() {
        GpsDebugLog.record("STOP location service")
        if (!started) return
        started = false
        handler.removeCallbacks(staleCheck)
        RideStateStore.resetGpsSpeed()
        client.removeLocationUpdates(callback)
    }
}
