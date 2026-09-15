package com.alban.ebike.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Looper
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

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.locations.forEach(RideStateStore::ingestLocation)
        }
    }

    fun start() {
        if (started || ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED) return
        started = true
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1_000)
            .setMinUpdateIntervalMillis(500)
            .setMinUpdateDistanceMeters(0.5f)
            .setWaitForAccurateLocation(false)
            .build()
        client.requestLocationUpdates(request, callback, Looper.getMainLooper())
    }

    fun stop() {
        if (!started) return
        started = false
        client.removeLocationUpdates(callback)
    }
}
