package com.alban.ebike.ui

import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import android.content.res.Configuration
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.alban.ebike.model.RideUiState
import com.alban.ebike.scene.*
import com.alban.ebike.terrain.*
import kotlinx.coroutines.*
import kotlin.math.*

@Composable
internal fun TerrainScene(state: RideUiState, modifier: Modifier, metric: RouteMetric,
    window: SceneWindow, onWindowChange: (SceneWindow) -> Unit, onReset: () -> Unit, onStatus: (String) -> Unit) {
    val context = LocalContext.current
    val preferences = remember { context.getSharedPreferences("scene-layers", android.content.Context.MODE_PRIVATE) }
    var optionsOpen by rememberSaveable { mutableStateOf(false) }
    var layers by remember { mutableStateOf(listOf("contours", "water", "roads", "paths", "buildings")
        .associateWith { preferences.getBoolean(it, true) }) }
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val view = remember { RideSceneView(context) }
    val repository = remember { TerrainRepository(context.applicationContext) }
    val mapRepository = remember { MapFeatureRepository(context.applicationContext) }
    var mapArea by remember { mutableStateOf<MapFeatureArea?>(null) }
    var mapMesh by remember { mutableStateOf(MapFeatureMesh()) }
    val latestState by rememberUpdatedState(state)
    val report by rememberUpdatedState(onStatus)
    val reset by rememberUpdatedState(onReset)
    val detail = TerrainDetail.forWindow(window)
    val selectedTrack = remember(state.track, window) { TrackWindow.select(state.track, window) }
    SideEffect {
        view.onResetRequested = { reset() }
        view.onOptionsRequested = { optionsOpen = true }
        view.onViewModeRequested = { onWindowChange(window.next()) }
        view.updateMovement(state.distanceM, state.inclinePercent.takeIf { state.inclineValid } ?: 0f)
    }
    var terrain by remember { mutableStateOf<TerrainGrid?>(null) }
    var heading by remember { mutableDoubleStateOf(0.0) }
    var cameraPose by remember { mutableStateOf(CameraPose()) }
    var active by remember { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    DisposableEffect(lifecycle, view) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) { active = true; view.resumeScene() }
            if (event == Lifecycle.Event.ON_PAUSE) { active = false; view.pauseScene() }
        }
        lifecycle.addObserver(observer)
        if (active) view.resumeScene()
        onDispose { lifecycle.removeObserver(observer); view.pauseScene() }
    }
    LaunchedEffect(active, window) {
        if (!active) return@LaunchedEffect
        while (isActive) {
            val current = latestState
            val position = current.position ?: current.track.lastOrNull()
            if (position == null) report("EN ATTENTE DE POSITION · GRILLE")
            else {
                val points = TrackWindow.select(current.track, window).ifEmpty { listOf(position) }
                val offsets = points.map { GeoFrame.local(it.latitude, it.longitude, position.latitude, position.longitude) }
                val east = (offsets.minOf { it.east } + offsets.maxOf { it.east }) / 2
                val north = (offsets.minOf { it.north } + offsets.maxOf { it.north }) / 2
                val center = GeoFrame.coordinate(east, north, position.latitude, position.longitude)
                val radius = max(550.0, offsets.maxOf { max(abs(it.east - east), abs(it.north - north)) } * 2.3 + 150)
                val old = terrain
                val covered = old != null && IgnElevationPolicy.contains(old.originLat, old.originLon,
                    old.halfSizeM, center.first, center.second, radius) &&
                    IgnElevationPolicy.adequateResolution(old.halfSizeM, old.size, radius * 1.35, detail.sourceSize)
                if (!covered) {
                    report("CHARGEMENT DU RELIEF…")
                    // Keep a spatial margin so ordinary movement reuses the same in-memory/disk grid.
                    val loaded = repository.load(center.first, center.second, radius * 1.35, detail)
                    // Never erase a valid relief while the network or next area is unavailable.
                    if (loaded.real || old == null) terrain = loaded
                    report(if (!loaded.real && old?.real == true) "RELIEF CONSERVÉ · ZONE SUIVANTE INDISPONIBLE" else loaded.status)
                }
            }
            delay(5000)
        }
    }
    LaunchedEffect(active) {
        if (!active) return@LaunchedEffect
        while (isActive) { cameraPose = view.cameraPose(); delay(50) }
    }
    LaunchedEffect(active, terrain) {
        val grid = terrain ?: return@LaunchedEffect
        if (!active || !grid.real) return@LaunchedEffect
        while (isActive) {
            val loaded = mapRepository.load(grid)
            if (loaded != null) { mapArea = loaded; break }
            delay(120_000)
        }
    }
    LaunchedEffect(active, mapArea, terrain, detail) {
        if (!active) return@LaunchedEffect
        mapMesh = withContext(Dispatchers.Default) { MapFeatureProjection.build(mapArea, terrain, detail) }
    }
    LaunchedEffect(active, selectedTrack, terrain, state.speedMode, metric, detail, mapMesh, layers) {
        if (!active) return@LaunchedEffect
        heading = GeoFrame.heading(selectedTrack, heading)
        val next = withContext(Dispatchers.Default) { RideSceneMesh.build(selectedTrack, terrain, state.speedMode, heading, metric, detail) }
        fun visible(key: String, data: FloatArray) = if (layers[key] == true) data else floatArrayOf()
        view.submit(next.copy(contours = visible("contours", next.contours),
            roads = visible("roads", mapMesh.roads), waterways = visible("water", mapMesh.water),
            paths = visible("paths", mapMesh.paths), buildings = visible("buildings", mapMesh.buildings)))
    }
    Box(modifier) {
        AndroidView(factory = { view }, modifier = Modifier.fillMaxSize())
        Box(Modifier.fillMaxWidth().height(124.dp).background(Brush.verticalGradient(
            listOf(Color(0xFF050A11).copy(alpha = .96f), Color(0xFF050A11).copy(alpha = .58f), Color.Transparent))))
        CompassRose(cameraPose, Modifier.align(Alignment.TopEnd)
            .padding(top = 2.dp).offset(x = 4.dp)
            .size(116.dp, if (landscape) 96.dp else 110.dp))
    }
    if (optionsOpen) AlertDialog(onDismissRequest = { optionsOpen = false },
        title = { Text("Affichage 3D") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                listOf("contours" to "Lignes de niveau", "water" to "Cours d’eau", "roads" to "Routes",
                    "paths" to "Chemins · VTT et à pied", "buildings" to "Bâtiments").forEach { (key, label) ->
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(
                        value = layers[key] == true, role = Role.Checkbox, onValueChange = { checked ->
                            layers = layers + (key to checked)
                            preferences.edit().putBoolean(key, checked).apply()
                        }), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = layers[key] == true, onCheckedChange = null)
                        Text(label, Modifier.padding(start = 8.dp))
                    }
                }
                Text("Chemins et contours de bâtiments selon les données disponibles et le niveau de zoom.")
            }
        }, confirmButton = { TextButton(onClick = { optionsOpen = false }) { Text("Fermer") } })
}

@Composable
private fun CompassRose(pose: CameraPose, modifier: Modifier) {
    Canvas(modifier) {
        val center = Offset(size.width / 2, size.height * .44f)
        val radius = min(size.width * .29f, size.height * .30f)
        val flatten = kotlin.math.sin(pose.tilt).toFloat().coerceIn(.32f, 1f)
        fun project(angle: Double, distance: Float): Offset = center + Offset(
            sin(angle - pose.heading).toFloat() * distance,
            -cos(angle - pose.heading).toFloat() * distance * flatten)
        drawOval(Color(0xC005101B), center - Offset(radius, radius * flatten),
            androidx.compose.ui.geometry.Size(radius * 2, radius * 2 * flatten))
        for (scale in listOf(1f, .86f)) drawOval(Color(0x9969E3F5), center - Offset(radius * scale, radius * scale * flatten),
            androidx.compose.ui.geometry.Size(radius * 2 * scale, radius * 2 * scale * flatten), style = Stroke(.7.dp.toPx()))
        for (tick in 0 until 72) {
            val angle = tick * Math.PI / 36
            drawLine(Color(0x9969E3F5), project(angle, radius * if (tick % 6 == 0) .77f else .91f),
                project(angle, radius), strokeWidth = if (tick % 6 == 0) 1.dp.toPx() else .5.dp.toPx())
        }
        // Two contrasting facets per point give the rose a machined, dimensional appearance.
        for (point in 0 until 8) {
            val angle = point * Math.PI / 4
            val tip = project(angle, radius * if (point % 2 == 0) .74f else .43f)
            for (side in listOf(-1, 1)) {
                val shoulder = project(angle + side * Math.PI / 2, radius * .115f)
                val facet = Path().apply {
                    moveTo(center.x, center.y); lineTo(shoulder.x, shoulder.y)
                    lineTo(tip.x, tip.y); close()
                }
                val color = if (point == 0) {
                    if (side == 1) Color(0xFFFF647C) else Color(0xFF952B47)
                } else if (side == 1) Color(0xFFCCF6FF) else Color(0xFF39768D)
                drawPath(facet, color)
            }
        }
        drawCircle(Color(0xFFE9F7FF), 1.8.dp.toPx(), center)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = android.graphics.Paint.Align.CENTER; textSize = 11.dp.toPx(); typeface = android.graphics.Typeface.DEFAULT_BOLD
            setShadowLayer(3.dp.toPx(), 0f, 0f, android.graphics.Color.BLACK)
        }
        listOf("N" to 0.0, "E" to Math.PI / 2, "S" to Math.PI, "O" to -Math.PI / 2).forEach { (label, direction) ->
            val edge = project(direction, radius)
            val delta = edge - center
            val length = sqrt(delta.x * delta.x + delta.y * delta.y).coerceAtLeast(1f)
            val end = edge + delta * (13.dp.toPx() / length)
            paint.color = if (label == "N") android.graphics.Color.rgb(255, 78, 101) else android.graphics.Color.rgb(137, 169, 188)
            drawContext.canvas.nativeCanvas.drawText(label, end.x, end.y + paint.textSize * .35f, paint)
        }
    }
}
