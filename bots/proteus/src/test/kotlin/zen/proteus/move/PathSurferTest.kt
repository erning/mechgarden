package zen.proteus.move

import zen.proteus.aim.Features
import zen.proteus.core.Angles
import zen.proteus.core.Battlefield
import zen.proteus.move.danger.DangerEstimator
import zen.proteus.move.danger.EnemyWave
import zen.proteus.state.BotState
import zen.proteus.wave.Wave
import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PathSurferTest {
    private val field = Battlefield(800.0, 600.0)
    private val pathSurfer = PathSurfer(field)

    private val enemy =
        BotState(100, 400.0, 550.0, PI, 8.0, 80.0, 0.0)
    private val self =
        BotState(100, 400.0, 250.0, PI / 2.0, 8.0, 80.0, 0.0)

    private val entry =
        EnemyWave(
            Wave(400.0, 550.0, 3.0, 95, PI, 1.0),
            DoubleArray(Features.COUNT),
            self,
            null,
        )

    private fun estimatorHotAt(vararg gfs: Double): DangerEstimator {
        val estimator = DangerEstimator()
        for (gf in gfs) {
            repeat(8) { estimator.onWaveResolved(entry, gf, true) }
        }
        return estimator
    }

    private fun planWith(danger: DangerEstimator): PathSurfer.Plan =
        pathSurfer.plan(self, enemy, listOf(entry), danger, emptyMap(), 1.0, 100, null)

    /** Replays a plan through the same physics the search uses and returns the
     *  GF interval it would cover on the wave. */
    private fun coveredByPlan(plan: PathSurfer.Plan): DoubleArray {
        val sim = MovementSim(field)
        val state = MovementSim.State(self.x, self.y, self.headingRadians, self.velocity)
        var lo = Double.POSITIVE_INFINITY
        var hi = Double.NEGATIVE_INFINITY
        var time = 100L
        val wave = entry.wave
        for (option in plan.options) {
            time++
            val r1 = wave.radius(time)
            val interval = wave.intersection(state.x, state.y, r1 - wave.speed, r1)
            if (interval != null) {
                val gf = wave.gfInterval(interval[0], interval[1])
                lo = minOf(lo, gf[0])
                hi = maxOf(hi, gf[1])
            } else if (r1 > wave.distanceTo(state.x, state.y) + 26.0) {
                break
            }
            val biasRadians = DistanceBand.dampedBiasRadians(state.distanceTo(enemy))
            val tangentRadians =
                Angles.absoluteBearingRadians(state.x, state.y, enemy.x, enemy.y) +
                    plan.line * (PI / 2.0 + biasRadians)
            sim.step(state, tangentRadians, option * 8.0, plan.line)
        }
        return doubleArrayOf(lo, hi)
    }

    @Test
    fun `avoids the hot side`() {
        // Continuing east covers GF [-0.79, -0.57]; heat there must be dodged.
        val plan = planWith(estimatorHotAt(-0.7))
        val (lo, hi) = coveredByPlan(plan)
        assertTrue(lo > -0.7 || hi < -0.7, "plan covers the hot GF: [$lo, $hi]")
        assertTrue(plan.danger < 0.5, "plan danger ${plan.danger} should be near zero")
    }

    @Test
    fun `finds the calm pocket when both wings are hot`() {
        val plan = planWith(estimatorHotAt(-0.7, 0.4))
        assertTrue(plan.danger < 0.5, "plan danger ${plan.danger} should be near zero")
    }

    @Test
    fun `never worse than going straight`() {
        val hot = estimatorHotAt(-0.8, -0.4, 0.0, 0.4, 0.8)
        val plan = planWith(hot)
        val straightDanger =
            coveredByPlan(PathSurfer.Plan(1.0, IntArray(40) { 1 }, 0.0)).let { (lo, hi) ->
                hot.danger(entry, self.x, self.y, lo, hi, null)
            }
        assertTrue(
            plan.danger <= straightDanger + 1e-9,
            "plan danger ${plan.danger} worse than going straight: $straightDanger",
        )
    }

    @Test
    fun `empty danger still yields a complete plan`() {
        val plan = planWith(DangerEstimator())
        assertTrue(plan.options.isNotEmpty())
        assertEquals(0.0, plan.danger, 1e-12)
    }
}
