package app.deltat.commute.map

import kotlin.math.hypot
import kotlin.math.max

data class MapPixel(val x: Float, val y: Float)
data class MapLabelPlacement(val order: Int, val anchor: MapPixel, val label: MapPixel, val slot: Int) {
    val displaced: Boolean get() = hypot(label.x - anchor.x, label.y - anchor.y) > 2f
}

/** Same screen-space layout in AMap and Canvas. Never changes geographical anchors. */
fun layoutMapLabels(
    anchors: Map<Int, MapPixel>, currentPosition: MapPixel?, width: Float, height: Float, density: Float,
    previousSlots: Map<Int, Int> = emptyMap(), priority: Int = 0,
): List<MapLabelPlacement> {
    val margin = 20f * density
    // Reserve bottom strip for the SDK logo/scale in both renderers.
    val bottom = max(margin, height - 48f * density)
    val right = max(margin, width - margin)
    val offsets = listOf(0f to 0f, 0f to -42f, 42f to 0f, -42f to 0f, 0f to 42f,
        42f to -42f, -42f to -42f, 42f to 42f, -42f to 42f,
        0f to -84f, 84f to 0f, -84f to 0f, 0f to 84f)
    val occupied = mutableListOf<MapPixel>()
    val placements = mutableListOf<MapLabelPlacement>()
    anchors.entries.sortedWith(compareBy<Map.Entry<Int, MapPixel>> { if (it.key == priority) -1 else it.key }).forEach { (order, anchor) ->
        val candidates = offsets.map { (dx, dy) -> MapPixel((anchor.x + dx * density).coerceIn(margin, right), (anchor.y + dy * density).coerceIn(margin, bottom)) }
        fun clearance(point: MapPixel): Float = minOf(
            occupied.minOfOrNull { hypot(point.x - it.x, point.y - it.y) - 34f * density } ?: Float.MAX_VALUE,
            currentPosition?.let { hypot(point.x - it.x, point.y - it.y) - 39f * density } ?: Float.MAX_VALUE,
        )
        val previous = previousSlots[order]?.takeIf { it in candidates.indices }
        // Keep a valid previous offset until the position is clearly away, preventing threshold chatter.
        val preferred = if (previous != null && previous != 0 && clearance(candidates[0]) < 10f * density) previous else 0
        val slot = (listOf(preferred) + candidates.indices).distinct().firstOrNull { clearance(candidates[it]) >= 0 }
            ?: candidates.indices.maxBy { clearance(candidates[it]) }
        val center = candidates[slot]
        placements += MapLabelPlacement(order, anchor, center, slot)
        occupied += center
    }
    return placements
}
