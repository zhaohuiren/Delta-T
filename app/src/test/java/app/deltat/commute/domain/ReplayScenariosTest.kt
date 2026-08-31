package app.deltat.commute.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class ReplayScenariosTest {
    private val engine = DefaultTimingEngine()
    private val checkpoints = (0..3).map { i -> Checkpoint("$i", i, listOf("A", "S1", "S2", "B")[i], 31.0, 121.0 + i * .001, preferredBearing = 90.0) }

    @Test fun twentyDeterministicReplayTracks() {
        val scenarios = listOf(
            Scenario("正常基准", 0.0, emptySet(), true),
            Scenario("S1前绕路", .00005, emptySet(), true),
            Scenario("S2前绕路", -.00005, emptySet(), true),
            Scenario("另一条道路1", .00010, emptySet(), true),
            Scenario("另一条道路2", -.00010, emptySet(), true),
            Scenario("节点附近堵车", .00001, emptySet(), true),
            Scenario("短缺口5秒", 0.0, emptySet(), true, 5),
            Scenario("缺口20秒", 0.0, emptySet(), true, 20),
            Scenario("缺口60秒", 0.0, emptySet(), true, 60),
            Scenario("精度退化后恢复", .00002, emptySet(), true),
            Scenario("轻微多路径", -.00002, emptySet(), true),
            Scenario("高架偏移", .00015, emptySet(), true),
            Scenario("高楼偏移", -.00015, emptySet(), true),
            Scenario("宽松方向", .00008, emptySet(), true),
            Scenario("慢速通过", 0.0, emptySet(), true, 12),
            Scenario("快速通过", 0.0, emptySet(), true, 3),
            Scenario("漏过S1", 0.0, setOf(1), false),
            Scenario("漏过S2", 0.0, setOf(2), false),
            Scenario("只经过起终点", 0.0, setOf(1, 2), false),
            Scenario("未经过终点", 0.0, setOf(3), false),
        )
        assertEquals(20, scenarios.size)
        scenarios.forEach { scenario ->
            val state = replay(scenario)
            assertEquals(scenario.name, scenario.completed, state.status == EngineStatus.COMPLETED)
        }
    }

    private fun replay(s: Scenario): EngineState {
        var state = EngineState()
        var seconds = 1L
        for ((index, cp) in checkpoints.withIndex()) {
            if (index in s.skipped) break
            val fixes = listOf(
                    LocationFix(cp.latitude + s.latOffset, cp.longitude - .0005, seconds * 1_000_000_000, seconds * 1_000, 8.0, 12.0, 90.0),
                    LocationFix(cp.latitude + s.latOffset, cp.longitude + .0002, (seconds + 2) * 1_000_000_000, (seconds + 2) * 1_000, 8.0, 12.0, 90.0),
            )
            fixes.forEach { state = engine.processFix(state, it, checkpoints).newState }
            seconds += s.gapSeconds
        }
        return state
    }

    private data class Scenario(val name: String, val latOffset: Double, val skipped: Set<Int>, val completed: Boolean, val gapSeconds: Long = 7)
}
