package com.alban.ebike.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alban.ebike.model.AltitudePoint
import com.alban.ebike.data.DistanceAltitudeProfile
import com.alban.ebike.model.RideUiState
import com.alban.ebike.model.TrackPoint
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

private val Ink = Color(0xFF071018)
private val Panel = Color(0xFF0D1C27)
private val Cyan = Color(0xFF64D8FF)
private val ElectricBlue = Color(0xFF248CFF)
private val Muted = Color(0xFF9BB2C2)

@Composable
fun DashboardScreen(
    state: RideUiState,
    circumferenceMm: Int,
    onAssociate: () -> Unit,
    onToggleMode: () -> Unit,
    onSaveCircumference: (Int) -> Unit,
) {
    var settingsOpen by remember { mutableStateOf(false) }
    val toggleMode by rememberUpdatedState(onToggleMode)
    MaterialTheme {
        Column(
            Modifier.fillMaxSize().background(Ink).padding(12.dp)
                .pointerInput(Unit) { detectTapGestures(onDoubleTap = { toggleMode() }) },
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(Modifier.fillMaxWidth().weight(0.42f)) {
                SpeedPanel(state, Modifier.fillMaxSize())
                Row(
                    Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    TextButton(onClick = onAssociate) {
                        Text("ESP32", color = if (state.bluetoothReady) Cyan else Color.Gray,
                            fontWeight = if (state.bluetoothReady) FontWeight.Bold else FontWeight.Normal)
                    }
                    TextButton(onClick = { settingsOpen = true }) {
                        Text("ROUE · $circumferenceMm mm", color = Cyan)
                    }
                }
            }
            AltitudePanel(
                state.profile,
                state.distanceM,
                state.altitudeM,
                state.inclinePercent,
                Modifier.fillMaxWidth().weight(0.23f),
            )
            TrackPanel(state, Modifier.fillMaxWidth().weight(0.35f))
        }
    }
    if (settingsOpen) {
        WheelSettingsDialog(circumferenceMm, { settingsOpen = false }, onSaveCircumference)
    }
}

@Composable
private fun SpeedPanel(state: RideUiState, modifier: Modifier = Modifier) {
    val modeColor = if (state.speedMode) Color(0xFFFF414D) else ElectricBlue
    val modeLight = if (state.speedMode) Color(0xFFFF8B83) else Cyan
    val animatedSpeed by animateFloatAsState(
        targetValue = state.displayedSpeedKmh ?: 0f,
        animationSpec = tween(420, easing = FastOutSlowInEasing),
        label = "GPS speed",
    )
    Box(
        modifier.clip(RoundedCornerShape(28.dp)).background(Panel),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize().padding(16.dp)) {
            val arcSize = min(size.width, size.height) * 0.86f
            val topLeft = Offset((size.width - arcSize) / 2f, (size.height - arcSize) / 2f)
            drawCircle(Brush.radialGradient(listOf(modeColor.copy(alpha = .16f), Color.Transparent),
                center = center, radius = arcSize * .65f), radius = arcSize * .65f)
            for (haloWidth in listOf(38f, 26f, 18f)) {
                drawArc(modeColor.copy(alpha = .06f), 132f, 276f, false, topLeft,
                    Size(arcSize, arcSize), style = Stroke(haloWidth, cap = StrokeCap.Round))
            }
            drawArc(modeColor.copy(alpha = .3f), 132f, 276f, false, topLeft, Size(arcSize, arcSize), style = Stroke(11f, cap = StrokeCap.Round))
            drawArc(
                Brush.sweepGradient(listOf(modeLight, modeColor, modeLight)),
                132f,
                (animatedSpeed / 55f).coerceIn(0f, 1f) * 276f,
                false,
                topLeft,
                Size(arcSize, arcSize),
                style = Stroke(11f, cap = StrokeCap.Round),
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(if (!state.bluetoothReady) "MODE —" else if (!state.modeSupported) "MODE INCONNU"
                else if (state.speedMode) "SPEED" else "STANDARD",
                color = modeLight, fontSize = 14.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
            Text(state.displayedSpeedSource, color = Cyan, fontSize = 13.sp, letterSpacing = 2.sp)
            Text(if (state.displayedSpeedKmh != null) "%.1f".format(animatedSpeed) else "—",
                color = Color.White, fontSize = 86.sp, fontWeight = FontWeight.Bold,
                maxLines = 1, softWrap = false)
            Text("km/h", color = Muted, fontSize = 16.sp)
        }
        Column(Modifier.align(Alignment.TopEnd).padding(top = 44.dp, end = 16.dp), horizontalAlignment = Alignment.End) {
            SmallSpeed("MOTEUR", state.motorSpeedKmh, ElectricBlue)
        }
        Column(Modifier.align(Alignment.BottomEnd).padding(bottom = 54.dp, end = 16.dp), horizontalAlignment = Alignment.End) {
            SmallSpeed("ROUE", state.wheelSpeedKmh, Cyan)
        }
        Text(
            state.gpsStatus,
            Modifier.align(Alignment.BottomCenter).padding(horizontal = 12.dp, vertical = 8.dp),
            color = Muted,
            fontSize = 10.sp,
            textAlign = TextAlign.Center,
            letterSpacing = 0.sp,
            lineHeight = 12.sp,
            maxLines = 3,
        )
    }
}

@Composable
private fun SmallSpeed(label: String, speed: Float, color: Color) {
    Text(label, color = Muted, fontSize = 9.sp, letterSpacing = 1.sp)
    Text("%.1f".format(speed), color = color, fontSize = 32.sp, fontWeight = FontWeight.Bold,
        maxLines = 1, softWrap = false)
}

@Composable
private fun AltitudePanel(profile: List<AltitudePoint>, distanceM: Double, altitude: Float?, incline: Float, modifier: Modifier = Modifier) {
    Row(modifier.clip(RoundedCornerShape(28.dp)).background(Panel).padding(16.dp)) {
        Column(Modifier.weight(1f)) {
            Text("ALTITUDE · 500 M", color = Muted, fontSize = 11.sp, letterSpacing = 1.sp)
            Text(altitude?.let { "%.0f m".format(it) } ?: "— m", color = Color.White, fontSize = 25.sp, fontWeight = FontWeight.Bold)
            Canvas(Modifier.fillMaxWidth().weight(1f).padding(top = 6.dp)) { drawAltitudeProfile(profile, distanceM) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("−500 m", color = Muted, fontSize = 9.sp)
                Text("Ici", color = Muted, fontSize = 9.sp)
            }
        }
        Column(Modifier.width(88.dp).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text("PENTE", color = Muted, fontSize = 10.sp)
            Text("%+.1f%%".format(incline), color = if (incline >= 0) Cyan else Color(0xFFFFB15C), fontSize = 24.sp, fontWeight = FontWeight.Bold)
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawAltitudeProfile(points: List<AltitudePoint>, distanceM: Double) {
    if (points.size < 2) return
    val low = points.minOf { it.altitudeM }
    val high = max(low + 1f, points.maxOf { it.altitudeM })
    val path = Path()
    points.forEachIndexed { index, point ->
        val x = DistanceAltitudeProfile.horizontalFraction(point.distanceM, distanceM) * size.width
        val y = size.height * .9f - ((point.altitudeM - low) / (high - low)) * size.height * .8f
        if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    clipRect {
        drawPath(path, Brush.horizontalGradient(listOf(Cyan, ElectricBlue)), style = Stroke(5f, cap = StrokeCap.Round))
    }
}

@Composable
private fun TrackPanel(state: RideUiState, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "trace animation")
    val phase by transition.animateFloat(0f, 1f, infiniteRepeatable(tween(2300), RepeatMode.Restart), label = "trace phase")
    Column(modifier.clip(RoundedCornerShape(28.dp)).background(Panel).padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column {
                Text("DISTANCE", color = Muted, fontSize = 11.sp, letterSpacing = 1.sp)
                Text("%.2f km".format(state.distanceM / 1000.0), color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.Bold)
            }
            Text("TRACE 3D", color = Cyan, fontSize = 11.sp, letterSpacing = 1.sp)
        }
        Canvas(Modifier.fillMaxSize().padding(top = 8.dp)) { drawTrack(state.track, phase) }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawTrack(points: List<TrackPoint>, phase: Float) {
    if (points.size < 2) return
    val latest = points.last()
    val previous = points[points.lastIndex - 1]
    val heading = atan2(latest.latitude - previous.latitude, latest.longitude - previous.longitude)
    val elevations = points.map { it.altitudeM }
    val elevationBase = elevations.minOrNull() ?: 0f
    val elevationSpan = max(1f, (elevations.maxOrNull() ?: elevationBase) - elevationBase)
    val coordinates = points.map { point ->
        val east = (point.longitude - latest.longitude) * 111_320.0 * cos(Math.toRadians(latest.latitude))
        val north = (point.latitude - latest.latitude) * 110_540.0
        val forward = east * cos(heading) + north * sin(heading)
        val side = -east * sin(heading) + north * cos(heading)
        val z = (point.altitudeM - elevationBase) / elevationSpan
        Offset(
            x = size.width * .5f + (side * 3.4).toFloat(),
            y = size.height * .80f - (forward * 3.0).toFloat() - z * size.height * .22f,
        )
    }
    val path = Path().apply {
        coordinates.forEachIndexed { index, point -> if (index == 0) moveTo(point.x, point.y) else lineTo(point.x, point.y) }
    }
    drawPath(path, Color(0xFF153242), style = Stroke(10f, cap = StrokeCap.Round))
    drawPath(path, Brush.linearGradient(listOf(ElectricBlue, Cyan)), style = Stroke(4f, cap = StrokeCap.Round))
    val head = coordinates.last()
    drawCircle(Cyan.copy(alpha = .35f + .55f * phase), radius = 12f + phase * 9f, center = head)
    drawCircle(Color.White, radius = 5f, center = head)
}

@Composable
private fun WheelSettingsDialog(currentCircumferenceMm: Int, onDismiss: () -> Unit, onSave: (Int) -> Unit) {
    var directMode by remember { mutableStateOf(true) }
    var value by remember(currentCircumferenceMm) { mutableStateOf(currentCircumferenceMm.toString()) }
    val calculated = value.toDoubleOrNull()?.let { if (directMode) it.toInt() else (Math.PI * it).toInt() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Calibrage roue") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (directMode) "Longueur parcourue par tour (mm)" else "Diamètre de roue (mm)")
                OutlinedTextField(value, { value = it }, singleLine = true, label = { Text("mm") })
                TextButton(onClick = { directMode = !directMode }) {
                    Text(if (directMode) "Saisir plutôt le diamètre" else "Saisir plutôt la longueur par tour")
                }
                Text("Circonférence envoyée : ${calculated ?: "—"} mm", color = Muted)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                calculated?.takeIf { it in 1000..4000 }?.let { onSave(it); onDismiss() }
            }) { Text("Enregistrer") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}
