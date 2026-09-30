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
import androidx.compose.ui.unit.sp
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
    window: SceneWindow, onWindowChange: (SceneWindow) -> Unit, onReset: () -> Unit,
    debugVisible: Boolean, onDebugChange: (Boolean) -> Unit, onStatus: (String) -> Unit) {
    val context = LocalContext.current
    val terrainModel = remember(context) {
        androidx.lifecycle.ViewModelProvider(context.terrainActivity())[TerrainViewModel::class.java]
    }
    val diagnostics = terrainModel.diagnostics
    val steps by diagnostics.steps.collectAsState()
    var debugNow by remember { mutableLongStateOf(LoadDiagnostics.now()) }
    val geometryCache = terrainModel.geometryCache
    val preferences = remember { context.getSharedPreferences("scene-layers", android.content.Context.MODE_PRIVATE) }
    var optionsOpen by rememberSaveable { mutableStateOf(false) }
    var layers by remember { mutableStateOf(listOf("contours", "water", "roads", "paths", "buildings", "parcels")
        .associateWith { preferences.getBoolean(it, it != "parcels") }) }
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val view = remember { RideSceneView(context) }
    val mapArea by terrainModel.mapArea.collectAsState()
    val parcels by terrainModel.parcels.collectAsState()
    var parcelMesh by remember { mutableStateOf(ParcelMesh()) }
    var parcelLabels by remember { mutableStateOf(emptyList<ScreenLabel>()) }
    var mapMesh by remember { mutableStateOf(MapFeatureMesh()) }
    val latestState by rememberUpdatedState(state)
    val report by rememberUpdatedState(onStatus)
    val reset by rememberUpdatedState(onReset)
    val detail = TerrainDetail.forWindow(window)
    val selectedTrack = remember(state.track, window) { TrackWindow.select(state.track, window) }
    SideEffect {
        view.onFrameReady = { milliseconds ->
            diagnostics.report("GPU", "Buffers + commandes de rendu : ${milliseconds} ms")
            diagnostics.end("GPU")
        }
        view.onResetRequested = { reset() }
        view.onOptionsRequested = { optionsOpen = true }
        view.onViewModeRequested = { onWindowChange(window.next()) }
        view.updateMovement(state.distanceM, state.inclinePercent.takeIf { state.inclineValid } ?: 0f)
    }
    val terrain by terrainModel.terrain.collectAsState()
    LaunchedEffect(parcels, terrain) {
        parcelMesh = withContext(Dispatchers.Default) { ParcelGeometry.build(parcels, terrain) }
    }
    LaunchedEffect(layers["parcels"]) {
        val p = state.position ?: state.track.lastOrNull()
        if (p != null) terrainModel.requestParcels(p.latitude, p.longitude, layers["parcels"] == true)
    }
    LaunchedEffect(terrain) { terrain?.let { report(it.status) } }
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
    val hasPosition = state.position != null || state.track.isNotEmpty()
    LaunchedEffect(active, window, hasPosition) {
        if (!active) return@LaunchedEffect
        while (isActive) {
            val current = latestState
            val position = current.position ?: current.track.lastOrNull()
            if (position == null) {
                report("EN ATTENTE DE POSITION · GRILLE")
                if (steps["GPS"] == null) diagnostics.begin("GPS", "Attente de position GPS")
            }
            else {
                diagnostics.report("GPS", "Position disponible"); diagnostics.end("GPS")
                val points = TrackWindow.select(current.track, window).ifEmpty { listOf(position) }
                val offsets = points.map { GeoFrame.local(it.latitude, it.longitude, position.latitude, position.longitude) }
                val east = (offsets.minOf { it.east } + offsets.maxOf { it.east }) / 2
                val north = (offsets.minOf { it.north } + offsets.maxOf { it.north }) / 2
                val center = GeoFrame.coordinate(east, north, position.latitude, position.longitude)
                val radius = max(550.0, offsets.maxOf { max(abs(it.east - east), abs(it.north - north)) } * 2.3 + 150)
                terrainModel.request(center.first, center.second, radius * 1.35, position.latitude, position.longitude)
                terrainModel.requestParcels(position.latitude, position.longitude, layers["parcels"] == true)
            }
            delay(5000)
        }
    }
    LaunchedEffect(active) {
        if (!active) return@LaunchedEffect
        while (isActive) { cameraPose = view.cameraPose(); parcelLabels = view.parcelLabels(); debugNow = LoadDiagnostics.now(); delay(100) }
    }
    LaunchedEffect(active, mapArea, terrain, detail) {
        if (!active) return@LaunchedEffect
        diagnostics.begin("OBJ", "Projection objets · recherche cache")
        mapMesh = withContext(Dispatchers.IO) {
            val area = mapArea; val grid = terrain
            val key = if (area != null && grid?.real == true) GeometryCache.key(grid, detail, area) else null
            val cached = key?.let { geometryCache.read(it) }?.takeIf { it.first.size == 4 }
            if (cached != null) {
                diagnostics.report("OBJ", "Objets projetés · cache ${cached.second}")
                MapFeatureMesh(cached.first[0], cached.first[1], cached.first[2], cached.first[3])
            } else {
                diagnostics.report("OBJ", if (key == null) "En attente relief / OSM" else "Projection routes / eau / bâtiments")
                MapFeatureProjection.build(area, grid, detail).also {
                    if (key != null) geometryCache.write(key, listOf(it.roads, it.water, it.paths, it.buildings))
                }
            }
        }
        diagnostics.end("OBJ")
    }
    LaunchedEffect(active, selectedTrack, terrain, state.speedMode, metric, detail, mapMesh, layers, parcelMesh) {
        if (!active) return@LaunchedEffect
        diagnostics.begin("3D", "Préparation scène")
        heading = GeoFrame.heading(selectedTrack, heading)
        val next = withContext(Dispatchers.IO) { RideSceneMesh.build(selectedTrack, terrain, state.speedMode, heading, metric, detail,
            geometryCache) { diagnostics.report("3D", it) } }
        diagnostics.end("3D")
        fun visible(key: String, data: FloatArray) = if (layers[key] == true) data else floatArrayOf()
        diagnostics.begin("GPU", "Attente envoi au rendu")
        view.submit(next.copy(contours = visible("contours", next.contours),
            roads = visible("roads", mapMesh.roads), waterways = visible("water", mapMesh.water),
            paths = visible("paths", mapMesh.paths), buildings = visible("buildings", mapMesh.buildings),
            parcels = visible("parcels", parcelMesh.lines), parcelLabels = if (layers["parcels"] == true) parcelMesh.labels else emptyList()))
    }
    Box(modifier) {
        AndroidView(factory = { view }, modifier = Modifier.fillMaxSize())
        if (layers["parcels"] == true) Canvas(Modifier.fillMaxSize()) {
            val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.rgb(255, 207, 147); textSize = 10.dp.toPx()
                textAlign = android.graphics.Paint.Align.CENTER
                setShadowLayer(2.dp.toPx(), 0f, 0f, android.graphics.Color.BLACK)
            }
            val occupied = ArrayList<android.graphics.RectF>()
            parcelLabels.forEach { label ->
                val x = label.x * size.width; val y = label.y * size.height
                val half = paint.measureText(label.text) / 2
                val box = android.graphics.RectF(x - half - 3, y - paint.textSize, x + half + 3, y + 3)
                if (occupied.none { android.graphics.RectF.intersects(it, box) }) {
                    drawContext.canvas.nativeCanvas.drawText(label.text, x, y, paint); occupied.add(box)
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(124.dp).background(Brush.verticalGradient(
            listOf(Color(0xFF050A11).copy(alpha = .96f), Color(0xFF050A11).copy(alpha = .58f), Color.Transparent))))
        CompassRose(cameraPose, Modifier.align(Alignment.TopEnd)
            .padding(top = 2.dp).offset(x = 4.dp)
            .size(116.dp, if (landscape) 96.dp else 110.dp))
        if (debugVisible) Column(Modifier.align(Alignment.CenterStart).padding(start = 10.dp)
            .background(Color(0xB3050A11)).padding(5.dp)) {
            Text("DEBUG CHARGEMENT 3D", fontSize = 9.sp, color = Color(0xFF69E3F5))
            listOf("GPS", "ALT", "OSM", "CAD", "OBJ", "3D", "GPU").forEach { lane ->
                val step = steps[lane]
                val duration = step?.let { ((it.end ?: debugNow) - it.start).coerceAtLeast(0) / 1000.0 }
                Text("$lane · ${step?.message ?: "en attente"}" + (duration?.let { " · %.2f s".format(it) } ?: ""),
                    fontSize = 8.sp, lineHeight = 10.sp, color = Color(0xFFB5CED8), maxLines = 2)
            }
        }
    }
    if (optionsOpen) AlertDialog(onDismissRequest = { optionsOpen = false },
        title = { Text("Affichage 3D") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                listOf("contours" to "Lignes de niveau", "water" to "Cours d’eau", "roads" to "Routes",
                    "paths" to "Chemins · VTT et à pied", "buildings" to "Bâtiments",
                    "parcels" to "Parcelles cadastrales · section / numéro").forEach { (key, label) ->
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(
                        value = layers[key] == true, role = Role.Checkbox, onValueChange = { checked ->
                            layers = layers + (key to checked)
                            preferences.edit().putBoolean(key, checked).apply()
                        }), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = layers[key] == true, onCheckedChange = null)
                        Text(label, Modifier.padding(start = 8.dp))
                    }
                }
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(
                    value = debugVisible, role = Role.Checkbox, onValueChange = onDebugChange),
                    verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = debugVisible, onCheckedChange = null)
                    Text("Diagnostic GPS et chargement 3D", Modifier.padding(start = 8.dp))
                }
                Text("Chemins et contours de bâtiments selon les données disponibles et le niveau de zoom.")
            }
        }, confirmButton = { TextButton(onClick = { optionsOpen = false }) { Text("Fermer") } })
}

private tailrec fun android.content.Context.terrainActivity(): androidx.activity.ComponentActivity = when (this) {
    is androidx.activity.ComponentActivity -> this
    is android.content.ContextWrapper -> baseContext.terrainActivity()
    else -> error("Terrain requires an activity ViewModel owner")
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
