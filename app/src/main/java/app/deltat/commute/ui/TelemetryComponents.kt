@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package app.deltat.commute.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import app.deltat.commute.map.MapPixel
import app.deltat.commute.map.layoutMapLabels
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.deltat.commute.data.TrackPointEntity
import app.deltat.commute.data.CheckpointEntity
import app.deltat.commute.domain.Checkpoint
import app.deltat.commute.domain.CheckpointSelection
import app.deltat.commute.domain.Geometry
import app.deltat.commute.domain.LocationFix
import app.deltat.commute.domain.ResultColor
import app.deltat.commute.map.MapCameraPolicy
import app.deltat.commute.map.MapCheckpoint
import app.deltat.commute.map.MapScene
import app.deltat.commute.map.MapPhase
import kotlin.math.hypot
import kotlin.math.roundToInt

@Composable
fun TelemetryPanel(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier.clip(RoundedCornerShape(12.dp))
            .background(Surface).padding(16.dp),
        content = content,
    )
}

@Composable
fun TelemetryLabel(text: String, color: Color = Muted) {
    Text(text.uppercase(), color = color, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.1.sp)
}

@Composable
fun TelemetryRoute(
    points: List<LocationFix>,
    modifier: Modifier = Modifier,
    referencePoints: List<LocationFix> = emptyList(),
    routeSourcePoints: List<LocationFix> = emptyList(),
    checkpoints: List<Checkpoint> = emptyList(),
    currentFix: LocationFix? = points.lastOrNull(),
    referencePassElapsedNanos: List<Long> = emptyList(),
    actualPassElapsedNanos: List<Long> = emptyList(),
    sectorColors: List<ResultColor> = emptyList(),
    cameraPolicy: MapCameraPolicy = MapCameraPolicy.FULL_ROUTE_LOCKED,
    phase: MapPhase = MapPhase.OVERVIEW,
    nextCheckpointIndex: Int = 1,
    currentFixStale: Boolean = false,
) {
    val scene = remember(routeSourcePoints, referencePoints, points, checkpoints, currentFix, actualPassElapsedNanos, sectorColors, cameraPolicy, phase, nextCheckpointIndex, currentFixStale) {
        MapScene(
            routeSourceTrack = routeSourcePoints.ifEmpty { referencePoints },
            actualTrack = points,
            checkpoints = checkpoints.map(MapCheckpoint::from),
            currentFix = currentFix,
            actualPassElapsedNanos = actualPassElapsedNanos,
            sectorColors = sectorColors,
            cameraPolicy = cameraPolicy,
            phase = phase, nextCheckpointIndex = nextCheckpointIndex, currentFixStale = currentFixStale,
        )
    }
    RouteMap(
        scene = scene,
        modifier = modifier,
    )
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawRoutePath(points: List<Offset>, color: Color, widthDp: Float) {
    if (points.size < 2) return
    val path = Path().apply {
        moveTo(points.first().x, points.first().y)
        points.drop(1).forEach { lineTo(it.x, it.y) }
    }
    drawPath(path, color, style = Stroke(widthDp.dp.toPx(), cap = StrokeCap.Round))
}

private fun segmentColor(elapsed: Long, passes: List<Long>, colors: List<ResultColor>): Color {
    if (passes.isEmpty() || elapsed < passes.first()) return Color(0xFF4A5056)
    (0 until passes.lastIndex).firstOrNull { elapsed <= passes[it + 1] }?.let { sector ->
        return colors.getOrNull(sector)?.let(::telemetryColor) ?: TelemetryWhite
    }
    return if (passes.size < 4) TelemetryWhite else Color(0xFF4A5056)
}

fun telemetryColor(result: ResultColor): Color = when (result) {
    ResultColor.PURPLE -> Purple
    ResultColor.GREEN -> Green
    ResultColor.YELLOW -> Yellow
    ResultColor.NEUTRAL -> Color(0xFFD5DADE)
}

private data class RouteBounds(val latMin: Double, val latRange: Double, val lonMin: Double, val lonRange: Double)

private fun routeBounds(points: List<LocationFix>): RouteBounds {
    val latMin = points.minOf { it.latitude }
    val latMax = points.maxOf { it.latitude }
    val lonMin = points.minOf { it.longitude }
    val lonMax = points.maxOf { it.longitude }
    return RouteBounds(latMin, (latMax - latMin).coerceAtLeast(0.00001), lonMin, (lonMax - lonMin).coerceAtLeast(0.00001))
}

private fun projectFixes(points: List<LocationFix>, size: Size, bounds: RouteBounds): List<Offset> = points.map {
    val x = ((it.longitude - bounds.lonMin) / bounds.lonRange).toFloat()
    val y = (1.0 - (it.latitude - bounds.latMin) / bounds.latRange).toFloat()
    Offset(x * size.width, y * size.height)
}

@Composable
fun ZoomableCheckpointEditor(
    track: List<TrackPointEntity>,
    initialPositions: List<Double>,
    onSave: (List<CheckpointSelection>) -> Unit,
    modifier: Modifier = Modifier,
) {
    var activeIndex by rememberSaveable { mutableIntStateOf(0) }
    var positions by rememberSaveable(track.size, initialPositions) {
        mutableStateOf(if (initialPositions.size == 4) initialPositions else listOf(0.02, 0.34, 0.66, 0.98).map { it * (track.lastIndex.coerceAtLeast(1)) })
    }
    var zoom by rememberSaveable { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    var error by remember { mutableStateOf<String?>(null) }
    val density = LocalDensity.current
    val labels = listOf("A", "S1", "S2", "B")

    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            labels.forEachIndexed { index, label ->
                SegmentedButton(
                    shape = SegmentedButtonDefaults.itemShape(index, labels.size),
                    selected = index == activeIndex,
                    onClick = { activeIndex = index },
                    label = { Text(label) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Box(
            Modifier.fillMaxWidth().weight(1f).heightIn(min = 300.dp)
                .testTag("checkpoint-map")
                .clip(RoundedCornerShape(8.dp)).background(Color(0xFF303C47))
                .onSizeChanged { canvasSize = it }
                .pointerInput(track, zoom, pan, canvasSize) {
                    detectTapGestures { tap ->
                        val baseTap = inverseTransform(tap, canvasSize, zoom, pan)
                        val projected = projectTrack(track, canvasSize)
                        nearestPathPosition(baseTap, projected)?.let { value ->
                            positions = updateOrderedPosition(positions, activeIndex, value, track.lastIndex.toDouble())
                        }
                    }
                }
                .pointerInput(Unit) {
                    detectTransformGestures { _, panChange, zoomChange, _ ->
                        zoom = (zoom * zoomChange).coerceIn(1f, 32f)
                        pan += panChange
                    }
                },
        ) {
            val projected = remember(track, canvasSize) { projectTrack(track, canvasSize) }
            Canvas(
                Modifier.fillMaxSize().graphicsLayer(
                    scaleX = zoom, scaleY = zoom,
                    translationX = pan.x, translationY = pan.y,
                ),
            ) {
                val padded = projectTrack(track, IntSize(size.width.roundToInt(), size.height.roundToInt()))
                drawRoutePath(padded, TelemetryWhite, 2.5f)
            }
            val anchors = positions.mapIndexed { index, position -> index to transform(interpolateOffset(projected, position), canvasSize, zoom, pan).let { MapPixel(it.x,it.y) } }.toMap()
            val placements = layoutMapLabels(anchors,null,(canvasSize.width-with(density){64.dp.toPx()}).coerceAtLeast(1f),canvasSize.height.toFloat(),density.density,priority=activeIndex)
            Canvas(Modifier.fillMaxSize()) {
                placements.forEach { placement ->
                    val anchor=Offset(placement.anchor.x,placement.anchor.y)
                    drawCircle(TelemetryWhite,3.dp.toPx(),anchor)
                    if(placement.visible)drawLine(Muted,anchor,Offset(placement.label.x,placement.label.y),1.dp.toPx())
                }
            }
            if (projected.isNotEmpty()) placements.filter { it.visible }.forEach { placement ->
                val index=placement.order
                val transformed=Offset(placement.anchor.x,placement.anchor.y)
                val label=Offset(placement.label.x,placement.label.y)
                val markerSize=with(density){48.dp.roundToPx()}
                Box(Modifier.offset { IntOffset((label.x-markerSize/2).roundToInt(),(label.y-markerSize/2).roundToInt()) }
                    .testTag("checkpoint-marker-${labels[index]}").size(48.dp)
                    .pointerInput(index,projected,zoom,pan) {
                        var pointer=transformed
                        detectDragGestures(onDragStart={activeIndex=index;pointer=transformed},onDrag={change,amount ->
                            change.consume();pointer+=amount
                            nearestPathPosition(inverseTransform(pointer,canvasSize,zoom,pan),projected)?.let{value-> positions=updateOrderedPosition(positions,index,value,track.lastIndex.toDouble()) }
                        })
                    },contentAlignment=Alignment.Center) {
                    Box(Modifier.size(36.dp,28.dp).background(if(index==activeIndex)ActionRed else Color(0xFF202B35),RoundedCornerShape(6.dp)),contentAlignment=Alignment.Center) { Text(labels[index],color=TelemetryWhite,fontWeight=FontWeight.Bold,fontSize=12.sp) }
                }
            }
            Column(Modifier.align(Alignment.TopEnd).padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val toolElevation = FloatingActionButtonDefaults.elevation(
                    defaultElevation = 0.dp, pressedElevation = 0.dp,
                    focusedElevation = 0.dp, hoveredElevation = 0.dp,
                )
                SmallFloatingActionButton(
                    onClick = { zoom = (zoom * 1.5f).coerceAtMost(32f) },
                    containerColor = Color(0xFF1B1E23), contentColor = TelemetryWhite, elevation = toolElevation,
                ) { Icon(Icons.Default.Add, "放大") }
                SmallFloatingActionButton(
                    onClick = { zoom = (zoom / 1.5f).coerceAtLeast(1f) },
                    containerColor = Color(0xFF1B1E23), contentColor = TelemetryWhite, elevation = toolElevation,
                ) { Icon(Icons.Default.Remove, "缩小") }
                SmallFloatingActionButton(
                    onClick = { zoom = 1f; pan = Offset.Zero },
                    containerColor = Color(0xFF1B1E23), contentColor = TelemetryWhite, elevation = toolElevation,
                ) { Icon(Icons.Default.CenterFocusStrong, "适应全图") }
            }
            Text("${"%.1f".format(zoom)}×", modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp).testTag("checkpoint-zoom"), color = Muted, fontFamily = FontFamily.Monospace)
        }

        val current = positions.getOrNull(activeIndex) ?: 0.0
        val currentPoint = track.getOrNull(current.roundToInt().coerceIn(0, track.lastIndex.coerceAtLeast(0)))
        TelemetryPanel(Modifier.fillMaxWidth()) {
            TelemetryLabel("当前 ${labels[activeIndex]}", RacingRed)
            Text("轨迹位置 ${if (track.size > 1) "%.1f".format(current / track.lastIndex * 100) else "0.0"}% · GPS ${currentPoint?.accuracyMeters?.let { "%.1f m".format(it) } ?: "--"}", color = Muted)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { positions = nudgeByMeters(track, positions, activeIndex, -5.0) }, modifier = Modifier.weight(1f)) { Text("◀ 5m") }
                OutlinedButton(onClick = { positions = nudgeByMeters(track, positions, activeIndex, 5.0) }, modifier = Modifier.weight(1f)) { Text("5m ▶") }
            }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { activeIndex = (activeIndex - 1).coerceAtLeast(0) }, enabled = activeIndex > 0, modifier = Modifier.weight(1f)) { Text("上一步") }
            Button(
                colors = ButtonDefaults.buttonColors(containerColor = ActionRed, contentColor = Color.White),
                shape = RoundedCornerShape(8.dp),
                onClick = {
                    if (activeIndex < 3) activeIndex++ else {
                        val validation = validateCheckpointPositions(track, positions)
                        if (validation != null) error = validation else onSave(positions.map { positionToSelection(track, it) })
                    }
                },
                enabled = track.size >= 4,
                modifier = Modifier.weight(1f),
            ) { Text(if (activeIndex < 3) "下一步" else "保存新版本") }
        }
    }
}

private fun projectTrack(track: List<TrackPointEntity>, size: IntSize): List<Offset> {
    if (track.isEmpty() || size.width == 0 || size.height == 0) return emptyList()
    val latitude = track.map { it.latitude }.average()
    val longitudeFactor = kotlin.math.cos(Math.toRadians(latitude))
    val minX=track.minOf { it.longitude*longitudeFactor }; val maxX=track.maxOf { it.longitude*longitudeFactor }
    val minY=track.minOf { it.latitude }; val maxY=track.maxOf { it.latitude }
    val pad=minOf(size.width,size.height)*.12f
    val scale=minOf((size.width-2*pad)/(maxX-minX).coerceAtLeast(.000001),(size.height-2*pad)/(maxY-minY).coerceAtLeast(.000001))
    val left=(size.width-(maxX-minX)*scale)/2;val top=(size.height-(maxY-minY)*scale)/2
    return track.map { Offset((left+(it.longitude*longitudeFactor-minX)*scale).toFloat(),(top+(maxY-it.latitude)*scale).toFloat()) }

}

private fun transform(point: Offset, size: IntSize, zoom: Float, pan: Offset): Offset {
    val center = Offset(size.width / 2f, size.height / 2f)
    return center + (point - center) * zoom + pan
}

private fun inverseTransform(point: Offset, size: IntSize, zoom: Float, pan: Offset): Offset {
    val center = Offset(size.width / 2f, size.height / 2f)
    return center + (point - center - pan) / zoom
}

private fun nearestPathPosition(point: Offset, path: List<Offset>): Double? {
    if (path.size < 2) return null
    var bestDistance = Float.POSITIVE_INFINITY
    var bestPosition = 0.0
    path.zipWithNext().forEachIndexed { index, (a, b) ->
        val delta = b - a
        val lengthSquared = delta.x * delta.x + delta.y * delta.y
        val ratio = if (lengthSquared == 0f) 0f else (((point - a).x * delta.x + (point - a).y * delta.y) / lengthSquared).coerceIn(0f, 1f)
        val projected = a + delta * ratio
        val distance = hypot(point.x - projected.x, point.y - projected.y)
        if (distance < bestDistance) { bestDistance = distance; bestPosition = index + ratio.toDouble() }
    }
    return bestPosition
}

private fun interpolateOffset(path: List<Offset>, position: Double): Offset {
    if (path.isEmpty()) return Offset.Zero
    val index = position.toInt().coerceIn(0, (path.lastIndex - 1).coerceAtLeast(0))
    val ratio = (position - index).toFloat().coerceIn(0f, 1f)
    return if (index >= path.lastIndex) path.last() else path[index] + (path[index + 1] - path[index]) * ratio
}

private fun updateOrderedPosition(positions: List<Double>, index: Int, value: Double, maximum: Double): List<Double> {
    val min = if (index == 0) 0.0 else positions[index - 1] + 0.001
    val max = if (index == positions.lastIndex) maximum else positions[index + 1] - 0.001
    return positions.toMutableList().also { it[index] = value.coerceIn(min, max) }
}

private fun nudgeByMeters(track: List<TrackPointEntity>, positions: List<Double>, active: Int, meters: Double): List<Double> {
    if (track.size < 2) return positions
    val cumulative = MutableList(track.size) { 0.0 }
    for (index in 1 until track.size) {
        cumulative[index] = cumulative[index - 1] + Geometry.distanceMeters(
            track[index - 1].latitude, track[index - 1].longitude,
            track[index].latitude, track[index].longitude,
        )
    }
    val current = positions[active]
    val currentIndex = current.toInt().coerceIn(0, track.lastIndex - 1)
    val currentDistance = cumulative[currentIndex] + (cumulative[currentIndex + 1] - cumulative[currentIndex]) * (current - currentIndex)
    val target = (currentDistance + meters).coerceIn(0.0, cumulative.last())
    val segment = (0 until cumulative.lastIndex).firstOrNull { target <= cumulative[it + 1] } ?: cumulative.lastIndex - 1
    val length = (cumulative[segment + 1] - cumulative[segment]).coerceAtLeast(0.001)
    val position = segment + ((target - cumulative[segment]) / length)
    return updateOrderedPosition(positions, active, position, track.lastIndex.toDouble())
}

private fun validateCheckpointPositions(track: List<TrackPointEntity>, positions: List<Double>): String? {
    if (positions.size != 4 || positions.zipWithNext().any { it.first >= it.second }) return "计时点必须按 A、S1、S2、B 排列"
    val points = positions.map { pos -> track[pos.roundToInt().coerceIn(0, track.lastIndex)] }
    val bearing = Geometry.bearingDegrees(points.first().latitude, points.first().longitude, points.last().latitude, points.last().longitude)
    val origin = Checkpoint("axis", 0, "A", points.first().latitude, points.first().longitude)
    val projections = points.map { Geometry.signedProgressMeters(it.latitude, it.longitude, origin, bearing) }
    if (projections.zipWithNext().any { (a, b) -> b - a < 100.0 }) return "相邻进度线至少需要间隔 100 米"
    return null
}

private fun positionToSelection(track: List<TrackPointEntity>, position: Double): CheckpointSelection {
    val index = position.toInt().coerceIn(0, (track.lastIndex - 1).coerceAtLeast(0))
    return CheckpointSelection(track[index].id, (position - index).coerceIn(0.0, 1.0))
}

fun initialCheckpointPositions(track: List<TrackPointEntity>, checkpoints: List<CheckpointEntity>): List<Double> {
    if (track.size < 2 || checkpoints.size != 4) return emptyList()
    val projected = track.map { point -> LocationFix(point.latitude, point.longitude, point.elapsedRealtimeNanos, point.wallTimeMillis, point.accuracyMeters) }
    return checkpoints.sortedBy { it.orderIndex }.map { checkpoint ->
        var bestDistance = Double.POSITIVE_INFINITY
        var bestPosition = 0.0
        projected.zipWithNext().forEachIndexed { index, (a, b) ->
            val (ax, ay) = Geometry.toLocalMeters(a.latitude, a.longitude, checkpoint.latitude, checkpoint.longitude)
            val (bx, by) = Geometry.toLocalMeters(b.latitude, b.longitude, checkpoint.latitude, checkpoint.longitude)
            val dx = bx - ax; val dy = by - ay
            val lengthSquared = dx * dx + dy * dy
            val ratio = if (lengthSquared == 0.0) 0.0 else (-(ax * dx + ay * dy) / lengthSquared).coerceIn(0.0, 1.0)
            val distance = hypot(ax + ratio * dx, ay + ratio * dy)
            if (distance < bestDistance) { bestDistance = distance; bestPosition = index + ratio }
        }
        bestPosition
    }
}
