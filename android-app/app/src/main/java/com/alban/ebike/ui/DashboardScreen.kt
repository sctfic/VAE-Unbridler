package com.alban.ebike.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import com.alban.ebike.scene.RouteMetric
import com.alban.ebike.scene.RouteColors
import com.alban.ebike.scene.SceneWindow
import kotlinx.coroutines.delay
import kotlin.math.*

private val Ink = Color(0xFF050A11)
private val Cyan = Color(0xFF69E3F5)
private val Muted = Color(0xFF89A9BC)
private val White = Color(0xFFE9F7FF)

@Composable
fun DashboardScreen(state: RideUiState, circumferenceMm: Int, onAssociate: () -> Unit,
    onToggleMode: () -> Unit, onSaveCircumference: (Int) -> Unit, onResetRide: () -> Unit = {}) {
    var settingsOpen by remember { mutableStateOf(false) }
    var resetOpen by remember { mutableStateOf(false) }
    var sourcesOpen by remember { mutableStateOf(false) }
    var metric by rememberSaveable { mutableStateOf(RouteMetric.SPEED) }
    var legendSelection by remember { mutableIntStateOf(0) }
    var legendVisible by remember { mutableStateOf(false) }
    fun selectMetric(value: RouteMetric) { metric = value; legendSelection++ }
    LaunchedEffect(legendSelection) {
        if (legendSelection == 0) return@LaunchedEffect
        legendVisible = true
        delay(5000)
        legendVisible = false
    }
    var profileMode by rememberSaveable { mutableIntStateOf(0) }
    var sceneWindow by rememberSaveable { mutableStateOf(SceneWindow.ALL) }
    val profileWindow = when (profileMode) { 1 -> state.distanceM.coerceAtLeast(1.0); 2 -> 2000.0; else -> 500.0 }
    val context = LocalContext.current
    val diagnosticPreferences = remember { context.getSharedPreferences("scene-layers", android.content.Context.MODE_PRIVATE) }
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
        Column(Modifier.fillMaxSize().background(Ink)
            .windowInsetsPadding(WindowInsets.statusBars.union(WindowInsets.displayCutout))) {
         if (landscape) {
          Box(Modifier.fillMaxSize()) {
           TerrainScene(state, Modifier.align(Alignment.TopEnd).fillMaxWidth(.6f).fillMaxHeight(), metric,
               sceneWindow, { sceneWindow = it }, onReset = { resetOpen = true },
               debugVisible = debugVisible, onDebugChange = setDebugVisible) { terrainStatus = it }
          Row(Modifier.fillMaxWidth().fillMaxHeight(.8f)) {
           Column(Modifier.weight(.4f).fillMaxHeight()) {
            Row(Modifier.fillMaxWidth().height(32.dp).padding(horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = onAssociate, contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Text("●  ESP32", color = if (state.bluetoothReady) Cyan else Color.Gray,
                        fontSize = 11.sp, fontWeight = if (state.bluetoothReady) FontWeight.Bold else FontWeight.Normal)
                }
                TextButton(onClick = { settingsOpen = true }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Text("ROUE $circumferenceMm", color = Muted, fontSize = 10.sp)
                }
            }
            Column(Modifier.fillMaxWidth().weight(1f)
                .pointerInput(Unit) { detectTapGestures(onTap = { selectMetric(RouteMetric.SPEED) }, onDoubleTap = { toggle() }) }) {
                SpeedGauge(state, accent, Modifier.fillMaxWidth().weight(1f))
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
                        Metric("ALTITUDE", state.altitudeM?.let { "%.0f".format(it) } ?: "—", "m", Modifier.weight(1f).clickable { selectMetric(RouteMetric.ALTITUDE) }, Alignment.Start)
                        Metric("PENTE", if (state.inclineValid) "%+.1f".format(state.inclinePercent) else "—", "%", Modifier.weight(1f).clickable { selectMetric(RouteMetric.GRADE) }, Alignment.CenterHorizontally)
                        Spacer(Modifier.weight(1f))
                    }
                    if (legendVisible) {
                    val range = RouteColors.range(state.track, metric)
                    val unit = when (metric) { RouteMetric.SPEED -> "km/h"; RouteMetric.ALTITUDE -> "m"; RouteMetric.GRADE -> "%" }
                    Text("${metric.label} · ${range.first.toInt()} → ${range.second.toInt()} $unit · gris : sans mesure",
                        color = Cyan, fontSize = 9.sp)
                    Box(Modifier.padding(top = 3.dp).fillMaxWidth().height(3.dp).background(Brush.horizontalGradient(
                        (0..3).map { i -> RouteColors.palette(i / 3f).let { Color(it[0], it[1], it[2]) } })))
                    }
                }
                Metric("DISTANCE · ${sceneWindow.label}", "%.2f".format(state.distanceM / 1000), "km",
                    Modifier.align(Alignment.BottomStart).padding(start = 14.dp, bottom = 2.dp), Alignment.Start)
                Column(Modifier.align(Alignment.BottomEnd).padding(end = 10.dp, bottom = 4.dp).fillMaxWidth(.68f),
                    horizontalAlignment = Alignment.End) {
                            Text(terrainStatus, color = Muted, fontSize = 7.sp, maxLines = 1,
                                overflow = TextOverflow.Ellipsis, textAlign = TextAlign.End)
                }
            }
          }
          Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(.2f)
              .background(Brush.verticalGradient(listOf(Ink.copy(alpha = .55f), Ink.copy(alpha = .92f))))
              .clickable { profileMode = (profileMode + 1) % 3 }) {
              AltitudeRibbon(state, accent, Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 2.dp), profileWindow)
              ProfileFooter(profileMode, accent, { sourcesOpen = true },
                  Modifier.align(Alignment.BottomCenter).padding(horizontal = 14.dp))
          }
          }
         } else {
            Row(Modifier.fillMaxWidth().height(38.dp).padding(horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = onAssociate, contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Text("●  ESP32", color = if (state.bluetoothReady) Cyan else Color.Gray,
                        fontSize = 11.sp, fontWeight = if (state.bluetoothReady) FontWeight.Bold else FontWeight.Normal)
                }
                Text("E-BikeCockpit", color = Muted, fontSize = 9.sp, letterSpacing = 1.5.sp)
                TextButton(onClick = { settingsOpen = true }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Text("ROUE $circumferenceMm", color = Muted, fontSize = 10.sp)
                }
            }
            Column(Modifier.fillMaxWidth().weight(.38f)
                .pointerInput(Unit) { detectTapGestures(onTap = { selectMetric(RouteMetric.SPEED) }, onDoubleTap = { toggle() }) }) {
                SpeedGauge(state, accent, Modifier.fillMaxWidth().weight(1f))
                if (debugVisible) Text(state.gpsStatus, Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                    color = Muted, fontSize = 9.sp, lineHeight = 11.sp, maxLines = 3,
                    overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
            }
            Separator(accent)
            Box(Modifier.fillMaxWidth().weight(.62f).clipToBounds()) {
                TerrainScene(state, Modifier.fillMaxSize(), metric, sceneWindow, { sceneWindow = it }, onReset = { resetOpen = true },
                    debugVisible = debugVisible, onDebugChange = setDebugVisible) { terrainStatus = it }
                Column(Modifier.align(Alignment.TopStart).fillMaxWidth().padding(end = 116.dp)
                    .padding(horizontal = 14.dp, vertical = 12.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Metric("ALTITUDE", state.altitudeM?.let { "%.0f".format(it) } ?: "—", "m", Modifier.weight(1f).clickable { selectMetric(RouteMetric.ALTITUDE) }, Alignment.Start)
                        Metric("PENTE", if (state.inclineValid) "%+.1f".format(state.inclinePercent) else "—", "%", Modifier.weight(1f).offset(x = (-12).dp).clickable { selectMetric(RouteMetric.GRADE) }, Alignment.CenterHorizontally)
                    }
                    if (legendVisible) {
                        val range = RouteColors.range(state.track, metric)
                        val unit = when (metric) { RouteMetric.SPEED -> "km/h"; RouteMetric.ALTITUDE -> "m"; RouteMetric.GRADE -> "%" }
                        Text("${metric.label} · ${range.first.toInt()} → ${range.second.toInt()} $unit · gris : sans mesure", color = Cyan, fontSize = 9.sp)
                        Box(Modifier.padding(top = 3.dp).fillMaxWidth().height(3.dp).background(Brush.horizontalGradient(
                            (0..3).map { i -> RouteColors.palette(i / 3f).let { Color(it[0], it[1], it[2]) } })))
                    }
                }
                Metric("DISTANCE · ${sceneWindow.label}", "%.2f".format(state.distanceM / 1000), "km",
                    Modifier.align(Alignment.BottomStart).padding(start = 14.dp, bottom = 108.dp), Alignment.Start)
                Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Ink.copy(alpha = .85f), Ink)))
                    .padding(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 1.dp)) {
                    Column(Modifier.fillMaxWidth().clickable { profileMode = (profileMode + 1) % 3 }) {
                        Text(terrainStatus, color = Muted, fontSize = 7.sp, letterSpacing = .3.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.End,
                            modifier = Modifier.fillMaxWidth())
                        Box(Modifier.fillMaxWidth().height(104.dp)) {
                            AltitudeRibbon(state, accent, Modifier.fillMaxSize().padding(top = 3.dp), profileWindow)
                            ProfileFooter(profileMode, accent, { sourcesOpen = true }, Modifier.align(Alignment.BottomCenter))
                        }
                    }
                }
            }
         }
        }
        if (settingsOpen) WheelSettingsDialog(circumferenceMm, { settingsOpen = false }, onSaveCircumference)
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

@Composable private fun ProfileFooter(profileMode: Int, accent: Color, onSources: () -> Unit, modifier: Modifier) {
    val context = LocalContext.current
    Row(modifier.fillMaxWidth().height(28.dp)
        .background(Brush.verticalGradient(listOf(Color.Transparent, Ink.copy(alpha = .65f)))),
        horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(if (profileMode == 1) "DÉPART" else if (profileMode == 2) "−2 km" else "−500 m", fontSize = 8.sp, color = Muted)
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("IGN / MAPZEN · © OpenStreetMap ⓘ", color = Muted, fontSize = 7.sp, lineHeight = 8.sp,
                modifier = Modifier.clickable(onClick = onSources))
            Text("Auteur Lopez Alban 2026 · GitHub ↗", color = Muted.copy(alpha = .85f),
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

@Composable private fun Metric(label: String, value: String, unit: String, modifier: Modifier, alignment: Alignment.Horizontal) {
    Column(modifier, horizontalAlignment = alignment) {
        Text(label, color = Muted, fontSize = 9.sp, letterSpacing = 1.5.sp)
        Text(value, color = White, fontSize = if (value.length > 5) 30.sp else 37.sp,
            fontWeight = FontWeight.Light, maxLines = 1, softWrap = false)
        Text(unit, color = Cyan, fontSize = 12.sp, modifier = Modifier.offset(y = (-7).dp))
    }
}

@Composable private fun SpeedGauge(state: RideUiState, accent: Color, modifier: Modifier) {
    val speed by animateFloatAsState(state.displayedSpeedKmh ?: 0f, tween(420), label = "speed")
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
            drawArc(Brush.sweepGradient(listOf(accent, White, accent), center), 135f, (speed / 60).coerceIn(0f, 1f) * 270, false,
                origin, diameter, style = Stroke(4.dp.toPx(), cap = StrokeCap.Round))
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
            Text(if (!state.bluetoothReady) "MODE —" else if (!state.modeSupported) "MODE INCONNU" else if (state.speedMode) "TURBO" else "STANDARD",
                color = accent, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 3.sp)
            Text(state.displayedSpeedSource, color = Muted, fontSize = 10.sp, letterSpacing = 2.sp)
            Text(if (state.displayedSpeedKmh == null) "—" else speed.roundToInt().toString(), color = White,
                fontSize = numberSize,
                fontWeight = FontWeight.Bold, letterSpacing = (-3).sp, maxLines = 1, softWrap = false)
            Text("km/h", color = Muted, fontSize = 13.sp, letterSpacing = 2.sp,
                modifier = Modifier.offset(y = (-8).dp))
        }
        if (state.bluetoothReady) {
            SmallSpeed("MOTEUR", state.motorSpeedKmh, accent, Modifier.align(Alignment.TopEnd).padding(top = 1.dp, end = 14.dp))
            SmallSpeed("ROUE", state.wheelSpeedKmh, Cyan, Modifier.align(Alignment.TopStart).padding(top = 1.dp, start = 14.dp), Alignment.Start)
        }
    }
}

@Composable private fun SmallSpeed(label: String, value: Float, color: Color, modifier: Modifier,
    alignment: Alignment.Horizontal = Alignment.End) {
    Column(modifier, horizontalAlignment = alignment) {
        Text(label, color = Muted, fontSize = 9.sp, letterSpacing = 1.5.sp)
        Text(if (value.isFinite()) value.roundToInt().toString() else "—", color = color, fontSize = 39.sp, fontWeight = FontWeight.Medium, maxLines = 1)
        Text("km/h", color = Muted.copy(alpha = .7f), fontSize = 9.sp,
            modifier = Modifier.offset(y = (-5).dp))
    }
}

@Composable private fun AltitudeRibbon(state: RideUiState, accent: Color, modifier: Modifier, windowM: Double) {
    val points = remember(state.profile, state.distanceM, windowM) { DistanceAltitudeProfile.visible(state.profile, state.distanceM, windowM) }
    val pulse by rememberInfiniteTransition(label = "profile marker").animateFloat(0f, 1f,
        infiniteRepeatable(tween(2200, easing = LinearEasing), RepeatMode.Restart), label = "profile marker pulse")
    Canvas(modifier) {
        repeat(3) { i -> val y = size.height * (i + 1) / 4; drawLine(Muted.copy(alpha = .13f), Offset(0f, y), Offset(size.width, y)) }
        if (points.size < 2) return@Canvas
        val minPoint = points.minBy { it.altitudeM }; val maxPoint = points.maxBy { it.altitudeM }
        val low = minPoint.altitudeM; val actualHigh = maxPoint.altitudeM; val high = max(low + 3, actualHigh)
        fun screen(point: com.alban.ebike.model.AltitudePoint): Offset {
            val x = DistanceAltitudeProfile.horizontalFraction(point.distanceM, state.distanceM, windowM) * size.width
            val y = size.height * .9f - (point.altitudeM - low) / (high - low) * size.height * .75f
            return Offset(x.coerceIn(0f, size.width), y)
        }
        val locations = points.map(::screen)
        fun color(altitude: Float, alpha: Float = 1f): Color {
            val rgb = RouteColors.palette(((altitude - low) / (actualHigh - low).coerceAtLeast(1f)).coerceIn(0f, 1f))
            return Color(rgb[0], rgb[1], rgb[2], alpha)
        }
        clipRect {
            for (index in 1 until points.size) if (!points[index].segmentStart) {
                val segmentColor = color((points[index - 1].altitudeM + points[index].altitudeM) / 2)
                drawLine(segmentColor.copy(alpha = .16f), locations[index - 1], locations[index], 7.dp.toPx(), StrokeCap.Round)
                drawLine(segmentColor, locations[index - 1], locations[index], 1.8.dp.toPx(), StrokeCap.Round)
            }
            val current = locations.last().copy(x = locations.last().x.coerceIn(4.dp.toPx(), size.width - 4.dp.toPx()))
            drawCircle(color(points.last().altitudeM, .25f * (1 - pulse)), 8.dp.toPx() + 11.dp.toPx() * pulse, current)
            drawCircle(Color.White, 3.2.dp.toPx(), current)
            drawCircle(color(points.last().altitudeM), 2.1.dp.toPx(), current)
        }
        val labelPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.rgb(233, 247, 255); textSize = 8.dp.toPx(); typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
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
    }
}

@Composable private fun WheelSettingsDialog(currentCircumferenceMm: Int, onDismiss: () -> Unit, onSave: (Int) -> Unit) {
    var directMode by remember { mutableStateOf(true) }
    var value by remember { mutableStateOf(currentCircumferenceMm.toString()) }
    val calculated = value.toDoubleOrNull()?.let { if (directMode) it.toInt() else (Math.PI * it).toInt() }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Calibrage roue") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (directMode) "Longueur parcourue par tour (mm)" else "Diamètre de roue (mm)")
            OutlinedTextField(value, { value = it }, singleLine = true, label = { Text("mm") })
            TextButton(onClick = { directMode = !directMode }) { Text(if (directMode) "Saisir le diamètre" else "Saisir la longueur par tour") }
            Text("Circonférence : ${calculated ?: "—"} mm")
        }
    }, confirmButton = { TextButton(onClick = { calculated?.takeIf { it in 1000..4000 }?.let { onSave(it); onDismiss() } }) { Text("Enregistrer") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } })
}

@Composable private fun TerrainSourcesDialog(onDismiss: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Relief & données") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Text("Priorité : © IGN, RGE ALTI® 1 m — ressource ign_rge_alti_par_territoires de la Géoplateforme. Licence Ouverte Etalab. Acquisition variable selon la zone (LiDAR, photogrammétrie, etc.).\n\n" +
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
