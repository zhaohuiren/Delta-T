package app.deltat.commute.domain

/** Editor and service must accept exactly the same progress-line ordering. */
fun validCheckpointSequence(checkpoints: List<Checkpoint>): Boolean {
    val ordered = checkpoints.sortedBy { it.orderIndex }
    if (ordered.map { it.orderIndex } != listOf(0, 1, 2, 3)) return false
    val a = ordered.first(); val b = ordered.last()
    val axis = Geometry.axisAlignedRouteBearing(a.latitude, a.longitude, b.latitude, b.longitude)
    val positions = ordered.map { Geometry.signedProgressMeters(it.latitude, it.longitude, a, axis) }
    return positions.all { it.isFinite() } && positions.zipWithNext().all { (from, to) -> to - from >= 100.0 }
}
