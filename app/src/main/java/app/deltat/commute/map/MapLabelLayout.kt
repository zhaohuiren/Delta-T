package app.deltat.commute.map

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/** Coordinates are pixels. Labels never modify their geographical anchor. */
data class MapPixel(val x: Float, val y: Float)
data class MapLabelPlacement(val order: Int, val anchor: MapPixel, val label: MapPixel, val slot: Int, val visible: Boolean = true) {
    val displaced: Boolean get() = hypot(label.x - anchor.x, label.y - anchor.y) > 2f
}

fun layoutMapLabels(
    anchors: Map<Int, MapPixel>, currentPosition: MapPixel?, width: Float, height: Float, density: Float,
    previousSlots: Map<Int, Int> = emptyMap(), priority: Int = 0,
): List<MapLabelPlacement> {
    require(density > 0 && density.isFinite())
    val halfWidth = 18f * density
    val halfHeight = 14f * density
    val margin = 6f * density
    // Badge/top controls and SDK logo/scale remain clear in both renderers.
    val left = halfWidth + margin
    val right = max(left, width - halfWidth - margin)
    val top = 48f * density + halfHeight
    val bottom = max(top, height - 48f * density - halfHeight)
    val offsets = listOf(-42f to 0f, 42f to 0f, 0f to -42f, 0f to 42f,
        -42f to -42f, 42f to -42f, -42f to 42f, 42f to 42f,
        -84f to 0f, 84f to 0f, 0f to -84f, 0f to 84f,
        -84f to -84f, 84f to -84f, -84f to 84f, 84f to 84f)
    val occupied = mutableListOf<MapPixel>()
    return anchors.entries.sortedWith(compareBy<Map.Entry<Int, MapPixel>> {
        when (it.key) { priority -> 0; 0 -> 1; 3 -> 2; else -> 3 }
    }.thenBy { it.key }).map { (order, anchor) ->
        val candidates = offsets.map { (dx, dy) -> MapPixel((anchor.x + dx * density).coerceIn(left, right), (anchor.y + dy * density).coerceIn(top, bottom)) }
        fun clearance(point: MapPixel): Float {
            val labels = occupied.minOfOrNull { max(abs(point.x - it.x) - 42f * density, abs(point.y - it.y) - 34f * density) } ?: Float.MAX_VALUE
            // Rotating arrow (32dp + outline) fits inside this circle. Distance is
            // measured to the label rectangle, not merely between marker centres.
            val arrow = currentPosition?.let {
                hypot(max(0f, abs(point.x - it.x) - halfWidth), max(0f, abs(point.y - it.y) - halfHeight)) - 26f * density
            } ?: Float.MAX_VALUE
            return minOf(labels, arrow)
        }
        val previous = previousSlots[order]?.takeIf { it in candidates.indices && clearance(candidates[it]) >= 0 }
        val slot = previous ?: candidates.indices.firstOrNull { clearance(candidates[it]) >= 4f * density }
            ?: candidates.indices.maxBy { clearance(candidates[it]) }
        val point = candidates[slot]
        val visible = clearance(point) >= 0 && width >= 2 * left && height >= top + 48f * density + halfHeight
        if (visible) occupied += point
        MapLabelPlacement(order, anchor, point, slot, visible)
    }
}
