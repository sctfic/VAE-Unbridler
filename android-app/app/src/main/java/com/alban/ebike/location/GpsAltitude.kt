package com.alban.ebike.location

import android.location.Location
import android.os.Build

/** Prefer mean sea level altitude when the location provider supplies it. */
object GpsAltitude {
    fun meters(location: Location): Double? =
        if (Build.VERSION.SDK_INT >= 34 && location.hasMslAltitude()) location.mslAltitudeMeters
        else location.altitude.takeIf { location.hasAltitude() }

    fun accuracy(location: Location): Float? =
        if (Build.VERSION.SDK_INT >= 34 && location.hasMslAltitude())
            location.mslAltitudeAccuracyMeters.takeIf { location.hasMslAltitudeAccuracy() }
        else location.verticalAccuracyMeters.takeIf { location.hasVerticalAccuracy() }

    fun source(location: Location): String =
        if (Build.VERSION.SDK_INT >= 34 && location.hasMslAltitude()) "GPS" else "GPS WGS84"
}
