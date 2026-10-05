package com.alban.ebike.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import android.content.res.Configuration
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alban.ebike.data.DistanceAltitudeProfile
import com.alban.ebike.model.RideUiState
import com.alban.ebike.model.RideReadout
import com.alban.ebike.model.formatRideDuration
import com.alban.ebike.scene.RouteMetric
import com.alban.ebike.scene.RouteColors
import com.alban.ebike.scene.SceneWindow
import kotlinx.coroutines.delay
import kotlin.math.*

internal val LocalSunlight = staticCompositionLocalOf { 0f }

private val Ink = Color(0xFF050A11)
private val Cyan = Color(0xFF69E3F5)
private val Muted = Color(0xFF89A9BC)
private val White = Color(0xFFE9F7FF)

@Composable
fun DashboardScreen(state: RideUiState, circumferenceMm: Int, onAssociate: () -> Unit,
    onToggleMode: () -> Unit, onSaveCircumference: (Int) -> Unit, onResetRide: () -> Unit = {}) {
    val Muted = lerp(Color(0xFF89A9BC), Color.White, LocalSunlight.current)
    var selectedPoint by remember { mutableStateOf<com.alban.ebike.model.TrackPoint?>(null) }
    var profileDragging by remember { mutableStateOf(false) }
    var sceneTouching by remember { mutableStateOf(false) }
    LaunchedEffect(profileDragging, sceneTouching, selectedPoint) {
        if (!profileDragging && !sceneTouching && selectedPoint != null) {
            delay(3000)
            selectedPoint = null
        }
    }
    LaunchedEffect(state.track.isEmpty()) { if (state.track.isEmpty()) selectedPoint = null }
    val readout = RideReadout.from(state, selectedPoint)
    var settingsOpen by remember { mutableStateOf(false) }
    var resetOpen by remember { mutableStateOf(false) }
    var sourcesOpen by remember { mutableStateOf(false) }
    val displayPreferences = remember { com.alban.ebike.data.BikeProfiles.preferences("display") }
    var metric by rememberSaveable { mutableStateOf(runCatching { RouteMetric.valueOf(displayPreferences.getString("metric", "SPEED")!!) }.getOrDefault(RouteMetric.SPEED)) }
    var legendSelection by remember { mutableIntStateOf(0) }
    var legendVisible by remember { mutableStateOf(false) }
    fun selectMetric(value: RouteMetric) { metric = value; legendSelection++ }
    LaunchedEffect(legendSelection) {
        if (legendSelection == 0) return@LaunchedEffect
        legendVisible = true
        delay(5000)
        legendVisible = false
    }
    var profileMode by rememberSaveable { mutableIntStateOf(displayPreferences.getInt("profile-mode", 0)) }
    var sceneWindow by rememberSaveable { mutableStateOf(runCatching { SceneWindow.valueOf(displayPreferences.getString("scene-window", "ALL")!!) }.getOrDefault(SceneWindow.ALL)) }
    LaunchedEffect(metric, profileMode, sceneWindow) {
        displayPreferences.edit().putString("metric", metric.name).putInt("profile-mode", profileMode).putString("scene-window", sceneWindow.name).apply()
    }
    val profileWindow = when (profileMode) { 1 -> state.distanceM.coerceAtLeast(1.0); 2 -> 2000.0; else -> 500.0 }
    val context = LocalContext.current
    val diagnosticPreferences = remember { com.alban.ebike.data.BikeProfiles.preferences("scene-layers") }
    var debugVisible by remember { mutableStateOf(diagnosticPreferences.getBoolean("debug", false)) }
    val setDebugVisible: (Boolean) -> Unit = { enabled ->
        debugVisible = enabled
        diagnosticPreferences.edit().putBoolean("debug", enabled).apply()
    }
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    var terrainStatus by remember { mutableStateOf("EN ATTENTE DE POSITION · GRILLE") }
    val toggle by rememberUpdatedState(onToggleMode)
    val accent by animateColorAsState(if (state.speedMode) Color(0xFFFF4E65) else Color(0xFF4ECFFF), tween(600), label = "mode tint")
    MaterialTheme(colorScheme = darkColorScheme(primary = Cyan, background = Ink, surface = Color(0xFF10212D))) {
        Column(Modifier.fillMaxSize().background(Ink)) {
         if (landscape) {
          Box(Modifier.fillMaxSize()) {
           TerrainScene(state, Modifier.align(Alignment.TopEnd).fillMaxWidth(.6f).fillMaxHeight(), metric,
               sceneWindow, { sceneWindow = it }, onReset = { resetOpen = true },
               debugVisible = debugVisible, onDebugChange = setDebugVisible, selectedPoint = selectedPoint, profileDragging = profileDragging, onSceneTouch = { sceneTouching = it }) { terrainStatus = it }
          Row(Modifier.fillMaxWidth().fillMaxHeight(.8f)) {
           Column(Modifier.weight(.4f).fillMaxHeight()) {
            Row(Modifier.fillMaxWidth().height(60.dp).windowInsetsPadding(WindowInsets.statusBars.only(WindowInsetsSides.Top).union(WindowInsets.displayCutout.only(WindowInsetsSides.Start))).padding(horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = onAssociate, contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Text("●  ${com.alban.ebike.data.BikeProfiles.name()}", color = if (state.bluetoothReady) Cyan else Color.Gray,
                        fontSize = 11.sp, fontWeight = if (state.bluetoothReady) FontWeight.Bold else FontWeight.Normal)
                }
                SettingsButton(circumferenceMm, state.bluetoothReady, state.resting && state.calibration?.significant(circumferenceMm) == true) { settingsOpen = true }
            }
            Column(Modifier.fillMaxWidth().weight(1f)
                .pointerInput(Unit) { detectTapGestures(onTap = { selectMetric(RouteMetric.SPEED) }, onDoubleTap = { toggle() }) }) {
                SpeedGauge(state, readout, accent, Modifier.fillMaxWidth().weight(1f), metric == RouteMetric.SPEED)
                if (debugVisible) Text(state.gpsStatus, Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                    color = Muted, fontSize = 8.sp, lineHeight = 9.sp, maxLines = 2,
                    overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
            }
           }
            Box(Modifier.width(1.dp).fillMaxHeight().background(accent.copy(alpha = .25f)))
            Box(Modifier.weight(.6f).fillMaxHeight().clipToBounds()) {
                Column(Modifier.align(Alignment.TopStart).fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 12.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Metric("ALTITUDE · ${readout.altitudeSource ?: "—"}", readout.altitudeM?.let { "%.0f".format(it) } ?: "—", "m", Modifier.weight(1f).clickable { selectMetric(RouteMetric.ALTITUDE) }, Alignment.Start,
                            active = metric == RouteMetric.ALTITUDE, uncertainty = readout.altitudeUncertaintyM?.let { "±%.1f m".format(it) + if (readout.altitudeSource == "IGN") " (pos.)" else "" })
                        Metric("PENTE · ${readout.altitudeSource ?: "—"}", readout.gradePercent?.let { "%+.1f".format(it) } ?: "—", "%", Modifier.weight(1f).clickable { selectMetric(RouteMetric.GRADE) }, Alignment.CenterHorizontally, active = metric == RouteMetric.GRADE)
                        Spacer(Modifier.weight(1f))
                    }
                }
                Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
                    RideFooterMetrics(readout, sceneWindow, Modifier.padding(horizontal = 14.dp))
                    Text(terrainStatus, color = Muted, fontSize = 7.sp, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, textAlign = TextAlign.End,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp))
                    if (legendVisible) RouteLegend(state, metric, Modifier.fillMaxWidth())
                }
            }
          }
          Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(.2f)
              .background(Brush.verticalGradient(listOf(Ink.copy(alpha = .55f), Ink.copy(alpha = .92f))))
              .clickable { selectedPoint = null; profileMode = (profileMode + 1) % 3 }) {
              AltitudeRibbon(state, accent, Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 2.dp), profileWindow, selectedPoint, { profileDragging = it }) { selectedPoint = it }
              ProfileFooter(profileMode, accent, { sourcesOpen = true },
                  Modifier.align(Alignment.BottomCenter).padding(horizontal = 14.dp))
          }
          }
         } else {
            Row(Modifier.fillMaxWidth().height(60.dp).windowInsetsPadding(WindowInsets.statusBars.only(WindowInsetsSides.Top).union(WindowInsets.displayCutout.only(WindowInsetsSides.Start))).padding(horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = onAssociate, contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Text("●  ${com.alban.ebike.data.BikeProfiles.name()}", color = if (state.bluetoothReady) Cyan else Color.Gray,
                        fontSize = 11.sp, fontWeight = if (state.bluetoothReady) FontWeight.Bold else FontWeight.Normal)
                }
                Text("E-BikeCockpit", color = Muted, fontSize = 9.sp, letterSpacing = 1.5.sp)
                SettingsButton(circumferenceMm, state.bluetoothReady, state.resting && state.calibration?.significant(circumferenceMm) == true) { settingsOpen = true }
            }
            Column(Modifier.fillMaxWidth().weight(.38f)
                .pointerInput(Unit) { detectTapGestures(onTap = { selectMetric(RouteMetric.SPEED) }, onDoubleTap = { toggle() }) }) {
                SpeedGauge(state, readout, accent, Modifier.fillMaxWidth().weight(1f), metric == RouteMetric.SPEED)
                if (debugVisible) Text(state.gpsStatus, Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                    color = Muted, fontSize = 9.sp, lineHeight = 11.sp, maxLines = 3,
                    overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
            }
            Separator(accent)
            Box(Modifier.fillMaxWidth().weight(.62f).clipToBounds()) {
                TerrainScene(state, Modifier.fillMaxSize(), metric, sceneWindow, { sceneWindow = it }, onReset = { resetOpen = true },
                    debugVisible = debugVisible, onDebugChange = setDebugVisible, selectedPoint = selectedPoint, profileDragging = profileDragging, onSceneTouch = { sceneTouching = it }) { terrainStatus = it }
                Column(Modifier.align(Alignment.TopStart).fillMaxWidth().padding(end = 116.dp)
                    .padding(horizontal = 14.dp, vertical = 12.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Metric("ALTITUDE · ${readout.altitudeSource ?: "—"}", readout.altitudeM?.let { "%.0f".format(it) } ?: "—", "m", Modifier.weight(1f).clickable { selectMetric(RouteMetric.ALTITUDE) }, Alignment.Start,
                            active = metric == RouteMetric.ALTITUDE, uncertainty = readout.altitudeUncertaintyM?.let { "±%.1f m".format(it) + if (readout.altitudeSource == "IGN") " (pos.)" else "" })
                        Metric("PENTE · ${readout.altitudeSource ?: "—"}", readout.gradePercent?.let { "%+.1f".format(it) } ?: "—", "%", Modifier.weight(1f).offset(x = (-12).dp).clickable { selectMetric(RouteMetric.GRADE) }, Alignment.CenterHorizontally, active = metric == RouteMetric.GRADE)
                    }
                }
                Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Ink.copy(alpha = .85f), Ink)))
                    .padding(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 1.dp)) {
                    RideFooterMetrics(readout, sceneWindow, Modifier.fillMaxWidth())
                    Column(Modifier.fillMaxWidth().clickable { selectedPoint = null; profileMode = (profileMode + 1) % 3 }) {
                        Text(terrainStatus, color = Muted, fontSize = 7.sp, letterSpacing = .3.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.End,
                            modifier = Modifier.fillMaxWidth())
                        if (legendVisible) RouteLegend(state, metric, Modifier.fillMaxWidth())
                        Box(Modifier.fillMaxWidth().height(104.dp)) {
                            AltitudeRibbon(state, accent, Modifier.fillMaxSize().padding(top = 3.dp), profileWindow, selectedPoint, { profileDragging = it }) { selectedPoint = it }
                            ProfileFooter(profileMode, accent, { sourcesOpen = true }, Modifier.align(Alignment.BottomCenter))
                        }
                    }
                }
            }
         }
        }
        if (settingsOpen) WheelSettingsDialog(circumferenceMm, state, { settingsOpen = false }, onSaveCircumference)
        if (resetOpen) AlertDialog(onDismissRequest = { resetOpen = false },
            title = { Text("Réinitialiser le trajet ?") },
            text = { Text("Le tracé, la distance et le profil d’altitude seront remis à zéro. Les anciens fichiers de trajet seront conservés.") },
            confirmButton = { TextButton(onClick = {
                onResetRide(); resetOpen = false
            }) { Text("Reset") } },
            dismissButton = { TextButton(onClick = { resetOpen = false }) { Text("Annuler") } })
        if (sourcesOpen) TerrainSourcesDialog { sourcesOpen = false }
    }
}

@Composable private fun RideFooterMetrics(readout: RideReadout, window: SceneWindow, modifier: Modifier) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Metric(if (readout.historical) "DISTANCE · POINT" else "DISTANCE · ${window.label}",
            "%.2f".format(readout.distanceM / 1000), "km", Modifier.weight(1f), Alignment.Start)
        Metric("DÉNIVELÉ +", readout.elevationGainM?.let { "%.0f".format(it) } ?: "—",
            "m", Modifier.weight(1f), Alignment.End)
    }
}

@Composable private fun RouteLegend(state: RideUiState, metric: RouteMetric, modifier: Modifier) {
    val Muted = lerp(Color(0xFF89A9BC), Color.White, LocalSunlight.current)
    val range = RouteColors.range(state.track, metric)
    val unit = when (metric) { RouteMetric.SPEED -> "km/h"; RouteMetric.ALTITUDE -> "m"; RouteMetric.GRADE -> "%" }
    Column(modifier.fillMaxWidth()) {
        Text("${metric.label} · ${range.first.toInt()} → ${range.second.toInt()} $unit · gris : sans mesure · magenta : repos",
            color = Cyan, fontSize = 8.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 4.dp).background(Ink.copy(alpha = .7f)))
        Box(Modifier.fillMaxWidth().height(3.dp).background(Brush.horizontalGradient(
            (0..3).map { i -> RouteColors.palette(i / 3f).let { Color(it[0], it[1], it[2]) } })))
    }
}

@Composable private fun SettingsButton(circumferenceMm: Int, bluetoothReady: Boolean, calibrationAvailable: Boolean, onClick: () -> Unit) {
    val Muted = lerp(Color(0xFF89A9BC), Color.White, LocalSunlight.current)
    val opacity = if (calibrationAvailable) {
        val pulse by rememberInfiniteTransition(label = "wheel calibration").animateFloat(.4f, 1f,
            infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "settings pulse")
        pulse
    } else 1f
    TextButton(onClick = onClick, modifier = Modifier.graphicsLayer { alpha = opacity },
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Setting", color = Cyan, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, lineHeight = 15.sp)
            if (calibrationAvailable) Text("calibration de roue", color = Cyan, fontSize = 9.sp, lineHeight = 13.sp)
            else if (bluetoothReady) Text("ROUE $circumferenceMm", color = Muted, fontSize = 10.sp, lineHeight = 13.sp)
        }
    }
}

@Composable private fun ProfileFooter(profileMode: Int, accent: Color, onSources: () -> Unit, modifier: Modifier) {
    val Muted = lerp(Color(0xFF89A9BC), Color.White, LocalSunlight.current)
    val context = LocalContext.current
    Row(modifier.fillMaxWidth().height(28.dp)
        .background(Brush.verticalGradient(listOf(Color.Transparent, Ink.copy(alpha = .65f)))),
        horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(if (profileMode == 1) "DÉPART" else if (profileMode == 2) "−2 km" else "−500 m", fontSize = 8.sp, color = Muted)
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("IGN / MAPZEN · © OpenStreetMap ⓘ", color = Muted, fontSize = 7.sp, lineHeight = 8.sp,
                modifier = Modifier.clickable(onClick = onSources))
            Text("Auteur Lopez Alban 2026 · v${com.alban.ebike.BuildConfig.VERSION_NAME} · GitHub ↗", color = Muted.copy(alpha = .85f),
                fontSize = 7.sp, lineHeight = 8.sp, maxLines = 1, modifier = Modifier.clickable {
                    runCatching { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,
                        android.net.Uri.parse("https://github.com/sctfic/VAE-Unbridler"))) }
                        .onFailure { android.widget.Toast.makeText(context, "Aucun navigateur disponible", android.widget.Toast.LENGTH_SHORT).show() }
                })
        }
        Text("ICI", fontSize = 8.sp, color = accent)
    }
}

@Composable private fun Separator(accent: Color) {
    Canvas(Modifier.fillMaxWidth().height(1.dp)) {
        drawLine(Brush.horizontalGradient(listOf(Color.Transparent, accent.copy(alpha = .65f), Color.Transparent)),
            Offset.Zero, Offset(size.width, 0f), strokeWidth = 1.dp.toPx())
    }
}

private fun Modifier.metricHalo(active: Boolean): Modifier = if (!active) this else drawBehind {
    val radius = maxOf(size.width, size.height) * .65f
    if (radius > 0f) drawCircle(Brush.radialGradient(listOf(Cyan.copy(alpha = .38f), Cyan.copy(alpha = .12f), Color.Transparent), center, radius), radius, center)
}

@Composable private fun Metric(label: String, value: String, unit: String, modifier: Modifier, alignment: Alignment.Horizontal, uncertainty: String? = null, active: Boolean = false) {
    val Muted = lerp(Color(0xFF89A9BC), Color.White, LocalSunlight.current)
    Column(modifier, horizontalAlignment = alignment) {
        Text(label, color = Muted, fontSize = 9.sp, letterSpacing = 1.5.sp)
        Text(value, modifier = Modifier.metricHalo(active), color = White, fontSize = if (value.length > 5) 30.sp else 37.sp,
            fontWeight = FontWeight.Light, maxLines = 1, softWrap = false)
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.offset(y = (-7).dp)) {
            Text(unit, color = Cyan, fontSize = 12.sp)
            uncertainty?.let { Text("  $it", color = Muted, fontSize = 9.sp, maxLines = 1) }
        }
    }
}

@Composable private fun SpeedGauge(state: RideUiState, readout: RideReadout, accent: Color, modifier: Modifier, active: Boolean = false) {
    val Muted = lerp(Color(0xFF89A9BC), Color.White, LocalSunlight.current)
    val animatedSpeed by animateFloatAsState(readout.speedKmh ?: 0f,
        if (readout.historical) snap() else tween(420), label = "speed")
    val speed = if (readout.historical) readout.speedKmh ?: 0f else animatedSpeed
    val maxSpeedKmh = if (state.bluetoothReady) 60f else 120f
    BoxWithConstraints(modifier) {
        val numberSize = min(94f, maxHeight.value * .34f).coerceAtLeast(40f).sp
        Canvas(Modifier.fillMaxSize()) {
            val center = Offset(size.width * .50f, size.height * .51f)
            val radius = min(size.width * .36f, size.height * .46f)
            val origin = center - Offset(radius, radius)
            val diameter = Size(radius * 2, radius * 2)
            drawCircle(Brush.radialGradient(listOf(accent.copy(alpha = .18f), accent.copy(alpha = .025f), Color.Transparent), center, radius * 1.23f), radius * 1.23f, center)
            drawOval(accent.copy(alpha = .04f), Offset(center.x - radius, center.y + radius * .78f), Size(radius * 2, radius * .20f))
            for (width in listOf(25f, 16f, 10f)) drawArc(accent.copy(alpha = .055f), 135f, 270f, false, origin, diameter, style = Stroke(width.dp.toPx(), cap = StrokeCap.Round))
            drawArc(accent.copy(alpha = .35f), 135f, 270f, false, origin, diameter, style = Stroke(2.dp.toPx()))
            drawArc(Brush.sweepGradient(listOf(accent, White, accent), center), 135f, (speed / maxSpeedKmh).coerceIn(0f, 1f) * 270, false,
                origin, diameter, style = Stroke(7.dp.toPx(), cap = StrokeCap.Round))
            val inner = radius * .89f
            drawArc(accent.copy(alpha = .12f), 135f, 270f, false, center - Offset(inner, inner), Size(inner * 2, inner * 2), style = Stroke(1f))
            repeat(41) { tick ->
                val a = Math.toRadians(135.0 + tick * 6.75)
                val r = radius * 1.075f
                val length = if (tick % 5 == 0) 6.dp.toPx() else 2.5.dp.toPx()
                drawLine(accent.copy(alpha = if (tick % 5 == 0) .7f else .25f),
                    center + Offset(cos(a).toFloat() * r, sin(a).toFloat() * r),
                    center + Offset(cos(a).toFloat() * (r + length), sin(a).toFloat() * (r + length)), strokeWidth = 1.dp.toPx())
            }
        }
        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(if (readout.historical) "POINT DU PARCOURS" else if (!state.bluetoothReady) "MODE —" else if (!state.modeSupported) "MODE INCONNU" else if (state.speedMode) "TURBO" else "STANDARD",
                color = accent, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 3.sp)
            Text(readout.speedSource, color = Muted, fontSize = 10.sp, letterSpacing = 2.sp)
            Text(if (readout.speedKmh == null) "—" else speed.roundToInt().toString(), color = White,
                fontSize = numberSize,
                modifier = Modifier.metricHalo(active).then(speedGlitch(readout.speedApproximate && readout.speedKmh != null)),
                fontWeight = FontWeight.Bold, letterSpacing = (-3).sp, maxLines = 1, softWrap = false)
            Text("km/h", color = Muted, fontSize = 13.sp, letterSpacing = 2.sp,
                modifier = Modifier.offset(y = (-8).dp))
        }
        Column(Modifier.align(Alignment.BottomStart).padding(start = 14.dp, bottom = 6.dp)) {
            val compactText = androidx.compose.ui.text.TextStyle(
                platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false))
            Text(if (readout.historical) "TEMPS AU POINT" else "ACTIVITÉ",
                color = Muted, fontSize = 8.sp, lineHeight = 10.sp, style = compactText)
            Text(readout.movingTimeMs?.let(::formatRideDuration) ?: "—",
                color = if (readout.resting) Muted else White, fontSize = 28.sp, lineHeight = 29.sp, style = compactText,
                fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
            Box(Modifier.height(24.dp)) {
                if (readout.resting && readout.restTimeMs != null) Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("REPOS", color = Muted, fontSize = 8.sp, style = compactText)
                    Spacer(Modifier.width(4.dp))
                    Text(formatRideDuration(readout.restTimeMs), color = Muted,
                        fontSize = 16.sp, lineHeight = 20.sp, style = compactText, maxLines = 1)
                }
            }
        }
        if (!readout.historical && state.bluetoothReady) {
            SmallSpeed("MOTEUR", state.motorSpeedKmh, accent, Modifier.align(Alignment.TopEnd).padding(top = 1.dp, end = 14.dp))
            SmallSpeed("ROUE", state.wheelSpeedKmh, Cyan, Modifier.align(Alignment.TopStart).padding(top = 1.dp, start = 14.dp), Alignment.Start)
        }
    }
}

@Composable private fun SmallSpeed(label: String, value: Float, color: Color, modifier: Modifier,
    alignment: Alignment.Horizontal = Alignment.End) {
    val Muted = lerp(Color(0xFF89A9BC), Color.White, LocalSunlight.current)
    Column(modifier, horizontalAlignment = alignment) {
        Text(label, color = Muted, fontSize = 9.sp, letterSpacing = 1.5.sp)
        Text(if (value.isFinite()) value.roundToInt().toString() else "—", color = color, fontSize = 39.sp, fontWeight = FontWeight.Medium, maxLines = 1)
        Text("km/h", color = Muted.copy(alpha = .7f), fontSize = 9.sp,
            modifier = Modifier.offset(y = (-5).dp))
    }
}

@Composable private fun AltitudeRibbon(state: RideUiState, accent: Color, modifier: Modifier, windowM: Double,
    selected: com.alban.ebike.model.TrackPoint?, onDraggingChange: (Boolean) -> Unit,
    onSelect: (com.alban.ebike.model.TrackPoint) -> Unit) {
    val Muted = lerp(Color(0xFF89A9BC), Color.White, LocalSunlight.current)
    val sunlight = rememberAmbientLight()
    val profileTrack = remember(state.track, state.distanceM, windowM) {
        val first = state.track.indexOfFirst { it.distanceM >= state.distanceM - windowM }
        if (first < 0) emptyList() else state.track.drop((first - 1).coerceAtLeast(0))
    }
    val points = profileTrack.map { com.alban.ebike.model.AltitudePoint(it.distanceM, it.altitudeM,
        it.segmentStart || it.altitudeSegmentStart || !it.altitudeValid) }
    val pulse by rememberInfiniteTransition(label = "profile marker").animateFloat(0f, 1f,
        infiniteRepeatable(tween(2200, easing = LinearEasing), RepeatMode.Restart), label = "profile marker pulse")
    val latestState by rememberUpdatedState(state)
    val selectPoint by rememberUpdatedState(onSelect)
    val draggingChanged by rememberUpdatedState(onDraggingChange)
    val currentWindow by rememberUpdatedState(windowM)
    Canvas(modifier.pointerInput(Unit) {
        fun select(x: Float) {
            val current = latestState
            val distance = current.distanceM - currentWindow + (x / size.width).coerceIn(0f, 1f) * currentWindow
            current.track.filter { it.altitudeValid && it.distanceM >= current.distanceM - currentWindow }
                .minByOrNull { abs(it.distanceM - distance) }?.let(selectPoint)
        }
        try {
            detectDragGestures(
                onDragStart = { draggingChanged(true); select(it.x) },
                onDragEnd = { draggingChanged(false) },
                onDragCancel = { draggingChanged(false) },
                onDrag = { change, _ -> change.consume(); select(change.position.x) })
        } finally { draggingChanged(false) }
    }) {
        repeat(3) { i -> val y = size.height * (i + 1) / 4; drawLine(Muted.copy(alpha = .13f), Offset(0f, y), Offset(size.width, y)) }
        if (points.size < 2) return@Canvas
        val validPoints = points.filterIndexed { index, _ -> profileTrack[index].altitudeValid }
        if (validPoints.isEmpty()) return@Canvas
        val minPoint = validPoints.minBy { it.altitudeM }; val maxPoint = validPoints.maxBy { it.altitudeM }
        val actualLow = minPoint.altitudeM; val actualHigh = maxPoint.altitudeM
        val low = profileTrack.filter { it.altitudeValid }.minOf { it.altitudeM - (it.altitudeUncertaintyM ?: 0f) }
        val high = max(low + 3, profileTrack.filter { it.altitudeValid }.maxOf { it.altitudeM + (it.altitudeUncertaintyM ?: 0f) })
        fun screen(point: com.alban.ebike.model.AltitudePoint): Offset {
            val x = DistanceAltitudeProfile.horizontalFraction(point.distanceM, state.distanceM, windowM) * size.width
            // Reserve the footer and the ring radius, so low-altitude selections stay visible.
            val top = 12.dp.toPx()
            val bottom = (size.height - 38.dp.toPx()).coerceAtLeast(top + 1f)
            val y = bottom - (point.altitudeM - low) / (high - low) * (bottom - top)
            return Offset(x.coerceIn(0f, size.width), y)
        }
        val locations = points.map(::screen)
        fun color(altitude: Float, alpha: Float = 1f): Color {
            val rgb = RouteColors.palette(((altitude - actualLow) / (actualHigh - actualLow).coerceAtLeast(1f)).coerceIn(0f, 1f))
            return Color(rgb[0], rgb[1], rgb[2], alpha)
        }
        clipRect {
            for (index in 1 until points.size) if (!points[index].segmentStart) {
                if (profileTrack[index - 1].altitudeValid && profileTrack[index].altitudeValid) {
                    val resting = profileTrack[index - 1].resting
                    val segmentColor = if (resting) Color.Magenta else color((points[index - 1].altitudeM + points[index].altitudeM) / 2)
                    val a = locations[index - 1]; val b = locations[index]
                    val firstError = profileTrack[index - 1].altitudeUncertaintyM
                    val secondError = profileTrack[index].altitudeUncertaintyM
                    if (firstError != null && secondError != null) {
                        val upperA = screen(points[index - 1].copy(altitudeM = points[index - 1].altitudeM + firstError))
                        val upperB = screen(points[index].copy(altitudeM = points[index].altitudeM + secondError))
                        val lowerA = screen(points[index - 1].copy(altitudeM = points[index - 1].altitudeM - firstError))
                        val lowerB = screen(points[index].copy(altitudeM = points[index].altitudeM - secondError))
                        val envelope = androidx.compose.ui.graphics.Path().apply {
                            moveTo(upperA.x, upperA.y); lineTo(upperB.x, upperB.y)
                            lineTo(lowerB.x, lowerB.y); lineTo(lowerA.x, lowerA.y); close()
                        }
                        drawPath(envelope, if (resting) Color.Magenta.copy(alpha = .1f) else Muted.copy(alpha = .12f))
                    }
                    drawLine(Color.Black.copy(alpha = .65f), a, b, (2.5f + sunlight).dp.toPx(), StrokeCap.Round)
                    drawLine(segmentColor, a, b, (1.1f + sunlight * .7f).dp.toPx(), StrokeCap.Round)
                    if (resting && (b - a).getDistance() < 1.dp.toPx()) drawCircle(Color.Magenta, 2.dp.toPx(), b)
                }
            }
            if (profileTrack.last().altitudeValid) {
            val current = locations.last().copy(x = locations.last().x.coerceIn(4.dp.toPx(), size.width - 4.dp.toPx()))
            drawCircle(color(points.last().altitudeM, .25f * (1 - pulse)), 8.dp.toPx() + 11.dp.toPx() * pulse, current)
            drawCircle(Color.White, 3.2.dp.toPx(), current)
            drawCircle(color(points.last().altitudeM), 2.1.dp.toPx(), current)
            }
        }
        val labelPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.rgb(233, 247, 255); textSize = 8.dp.toPx(); typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        val currentMeasurement = selected ?: state.position
        val error = currentMeasurement?.altitudeUncertaintyM
        val source = currentMeasurement?.altitudeSource
        val errorLabel = if (error != null) "${source ?: "ALTITUDE"} ±%.1f m".format(error) +
            if (source == "IGN") " · position" else "" else "INCERTITUDE NON FOURNIE"
        drawContext.canvas.nativeCanvas.drawText(errorLabel, 2.dp.toPx(), labelPaint.textSize + 1.dp.toPx(), labelPaint)
        fun extremum(point: com.alban.ebike.model.AltitudePoint, label: String, above: Boolean) {
            val at = screen(point)
            drawCircle(color(point.altitudeM), 3.dp.toPx(), at)
            val labelY = (at.y + if (above) -8.dp.toPx() else 13.dp.toPx()).coerceIn(labelPaint.textSize, size.height - 2.dp.toPx())
            val text = "$label ${point.altitudeM.roundToInt()} m"
            val width = labelPaint.measureText(text)
            val labelX = (at.x - width / 2).coerceIn(1.dp.toPx(), size.width - width - 1.dp.toPx())
            drawLine(color(point.altitudeM, .8f), at, Offset(at.x, labelY + if (above) 2.dp.toPx() else -labelPaint.textSize), 1.dp.toPx())
            drawContext.canvas.nativeCanvas.drawText(text, labelX, labelY, labelPaint)
        }
        extremum(maxPoint, "MAX", true)
        if (minPoint !== maxPoint) extremum(minPoint, "MIN", false)
        selected?.takeIf { it.distanceM in (state.distanceM - windowM)..state.distanceM }?.let {
            drawSelectionMarker(screen(com.alban.ebike.model.AltitudePoint(it.distanceM, it.altitudeM)))
        }
    }
}

@Composable private fun WheelSettingsDialog(currentCircumferenceMm: Int, state: RideUiState, onDismiss: () -> Unit, onSave: (Int) -> Unit) {
    val Muted = lerp(Color(0xFF89A9BC), Color.White, LocalSunlight.current)
    val context = LocalContext.current
    val preferences = remember { com.alban.ebike.data.BikeProfiles.preferences("ride-options") }
    val profileId by com.alban.ebike.data.BikeProfiles.activeId.collectAsState()
    var bikeName by remember { mutableStateOf(com.alban.ebike.data.BikeProfiles.name(profileId)) }
    var threshold by remember { mutableFloatStateOf(preferences.getFloat("moving-threshold", 4f)) }
    var gradePoints by remember { mutableFloatStateOf(preferences.getInt("grade-points", 10).coerceIn(3, 30).toFloat()) }
    var directMode by remember { mutableStateOf(true) }
    var value by remember { mutableStateOf(currentCircumferenceMm.toString()) }
    val calculated = value.toDoubleOrNull()?.let { if (directMode) it.toInt() else (Math.PI * it).toInt() }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Roue, chronomètre et pente") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (profileId != com.alban.ebike.data.BikeProfiles.OFFLINE) {
                OutlinedTextField(bikeName, { bikeName = it.take(40) }, singleLine = true, label = { Text("Nom du VAE") })
                Text("Profil : $profileId", fontSize = 10.sp)
                Text("VAE mémorisés : ${com.alban.ebike.data.BikeProfiles.knownNames().joinToString()}", fontSize = 11.sp)
            }
            state.calibration?.takeIf { it.significant(currentCircumferenceMm) }?.let { proposal ->
                Text("Calibration de roue", color = Cyan, fontWeight = FontWeight.Bold)
                Text("${currentCircumferenceMm} mm → ${proposal.circumferenceMm} mm · %+.2f %%".format(proposal.differencePercent(currentCircumferenceMm)))
                Text("Segment : %.0f m · rectitude %.1f %% · GPS ≤ 7 m".format(proposal.distanceM, proposal.straightness * 100), fontSize = 12.sp)
                Button(enabled = state.resting && state.bluetoothReady, onClick = {
                    onSave(proposal.circumferenceMm)
                    onDismiss()
                }) { Text("Appliquer cette calibration") }
                if (!state.bluetoothReady) Text("Connecter l’ESP32 pour appliquer.", fontSize = 12.sp)
                else if (!state.resting) Text("Application possible pendant une pause.", fontSize = 12.sp)
                HorizontalDivider()
            }
            Text(if (directMode) "Longueur parcourue par tour (mm)" else "Diamètre de roue (mm)")
            OutlinedTextField(value, { value = it }, singleLine = true, label = { Text("mm") })
            TextButton(onClick = { directMode = !directMode }) { Text(if (directMode) "Saisir le diamètre" else "Saisir la longueur par tour") }
            Text("Circonférence : ${calculated ?: "—"} mm")
            Text("Chronomètre : vitesse > ${threshold.toInt()} km/h")
            Slider(value = threshold, onValueChange = { threshold = it.roundToInt().toFloat() }, valueRange = 0f..20f, steps = 19)
            Text("Pente : ${gradePoints.toInt()} derniers points GPS")
            Slider(value = gradePoints, onValueChange = { gradePoints = it.roundToInt().toFloat() }, valueRange = 3f..30f, steps = 26)
            Text("Points valides en déplacement. Plus de points : pente plus stable, mais moins réactive.")
        }
    }, confirmButton = { TextButton(onClick = { calculated?.takeIf { it in 1000..4000 }?.let { onSave(it)
                com.alban.ebike.data.BikeProfiles.rename(bikeName, profileId)
                preferences.edit().putFloat("moving-threshold", threshold).putInt("grade-points", gradePoints.toInt()).apply()
                if (profileId == com.alban.ebike.data.BikeProfiles.activeId.value) {
                    com.alban.ebike.data.RideStateStore.movingThresholdKmh = threshold
                    com.alban.ebike.data.RideStateStore.gradePointCount = gradePoints.toInt()
                }
                onDismiss() } }) { Text("Enregistrer") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } })
}

@Composable private fun TerrainSourcesDialog(onDismiss: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Relief & données") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Text("Altitude GPS : conversion au niveau moyen de la mer sur Android 14 et versions suivantes ; GPS WGS84 indique une altitude brute non comparable directement à IGN. IGN utilise le référentiel altimétrique national et représente le sol : ponts et passages en hauteur peuvent différer. Les deux références corrigées restent légèrement différentes.\n\nProfil d’altitude : le trait fin indique l’altitude filtrée, la bande transparente représente l’incertitude disponible. GPS : précision verticale annoncée par Android, sans garantie de limite absolue. IGN : variation d’altitude du terrain dans la zone d’incertitude horizontale GPS ; l’erreur verticale propre au modèle IGN n’est pas fournie et s’y ajoute. La résolution du MNT n’est pas sa précision.\n\n" +
                "Priorité : © IGN, RGE ALTI® 1 m — ressource ign_rge_alti_par_territoires de la Géoplateforme. Licence Ouverte Etalab. Acquisition variable selon la zone (LiDAR, photogrammétrie, etc.).\n\n" +
                "Routes (gris clair) et cours d’eau (bleu) : © contributeurs OpenStreetMap, licence ODbL, via Overpass. Projection sur le sol IGN ; les tunnels sont omis et les ponts ne représentent pas leur hauteur réelle. Toutes les couches cochées restent actives en grande vue, dans une zone centrale limitée à environ 24 km de côté avant marge et avec un budget de géométrie par couche. Cache local de 64 Mio ; le serveur reçoit l’emprise consultée. Hors connexion, seules les zones déjà en cache sont disponibles.\n\n" +
                "Cadastre optionnel : Parcellaire Express (PCI), IGN / DGFiP, Licence Ouverte Etalab. Références section / numéro, sans nom de propriétaire. Couverture locale autour du GPS ; cache de 64 Mio et limite de 2 000 parcelles par zone. L’activation transmet cette zone à IGN. Ce rendu n’a pas de valeur juridique.\n\n" +
                "Détail adapté à la vue : 500 m, courbes tous les 5 m ; 2 km, tous les 10 m ; parcours complet, tous les 20 m. Le pas horizontal réellement utilisé dépend de la zone et apparaît dans le statut. Le cache plus détaillé est réutilisé. La source RGE ALTI à 1 m ne signifie pas que chaque sommet affiché est espacé de 1 m. Le relief est exagéré ×1,8 uniquement à l’écran.\n\n" +
                "Repli : Terrain Tiles / Mapzen (AWS) pour les points non couverts ou si l’IGN est indisponible. Sans données, grille neutre. Trace projetée sur le sol, sans représentation spécifique des ponts et tunnels.\n\n" +
                "Cache local : 64 Mio IGN + 64 Mio Mapzen. Les requêtes IGN transmettent les coordonnées d’une grille couvrant la zone visible ; AWS reçoit les numéros des tuiles. Ces fournisseurs connaissent donc la zone consultée, mais aucun journal ni historique GPS ne leur est envoyé.\n\n" +
                "Sources du repli : USGS (SRTM, GMTED2010, 3DEP), NOAA (ETOPO1), Copernicus / Union européenne (EU-DEM), et contributeurs régionaux.")
            TextButton(onClick = {
                runCatching { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,
                    android.net.Uri.parse("https://www.openstreetmap.org/copyright"))) }
            }) { Text("© contributeurs OpenStreetMap · licence ODbL ↗") }
            TextButton(onClick = {
                runCatching { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,
                    android.net.Uri.parse("https://cartes.gouv.fr/aide/fr/guides-utilisateur/utiliser-les-services-de-la-geoplateforme/calcul-altimetrique/"))) }
                    .onFailure { android.widget.Toast.makeText(context, "Aucun navigateur disponible", android.widget.Toast.LENGTH_SHORT).show() }
            }) { Text("Source IGN / Géoplateforme ↗") }
            TextButton(onClick = {
                runCatching { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,
                    android.net.Uri.parse("https://github.com/tilezen/joerd/blob/master/docs/attribution.md"))) }
                    .onFailure { android.widget.Toast.makeText(context, "Aucun navigateur disponible", android.widget.Toast.LENGTH_SHORT).show() }
            }) { Text("Attributions et licences complètes ↗") }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("Fermer") } })
}
