package com.alban.ebike

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.alban.ebike.model.*
import com.alban.ebike.scene.GeoFrame
import com.alban.ebike.ui.DashboardScreen
import kotlin.math.*

/** Separate debug-only preview. Never injects GPS or sends BLE commands. */
class CockpitPreviewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).hide(WindowInsetsCompat.Type.systemBars())
        val track = (0..120).map { i ->
            val coord = GeoFrame.coordinate(sin(i / 24.0) * 230, i * 9.0 - 540, 45.923, 6.87)
            TrackPoint(coord.first, coord.second, 1035f + i * .25f + sin(i / 15.0).toFloat() * 12,
                i * 1000L, i * 11.0, i == 0, 25f + sin(i / 20.0).toFloat() * 20,
                (2.27 + cos(i / 15.0) * 7.27).toFloat())
        }
        setContent {
            var speedMode by remember { mutableStateOf(intent.getBooleanExtra("speed", false)) }
            val state = RideUiState(bluetoothReady = true, modeSupported = true, speedMode = speedMode,
                gpsSpeedValid = true, gpsSpeedKmh = 28.6f, wheelSpeedKmh = 28.4f,
                motorSpeedKmh = if (speedMode) 22.84f else 28.4f, altitudeM = track.last().altitudeM,
                inclinePercent = track.last().gradePercent ?: 0f, inclineValid = true,
                distanceM = 1320.0, track = track, position = track.last(),
                gpsStatus = "APERÇU · DONNÉES DE DÉMONSTRATION · GPS / BLE NON MODIFIÉS",
                profile = track.map { AltitudePoint(it.distanceM, it.altitudeM, it.segmentStart) })
            DashboardScreen(state, 2360, {}, { speedMode = !speedMode }, {})
        }
    }
}
