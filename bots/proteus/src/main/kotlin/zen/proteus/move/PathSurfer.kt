package zen.proteus.move

import robocode.Rules
import zen.proteus.core.Angles
import zen.proteus.core.Battlefield
import zen.proteus.move.danger.EmpiricalDanger
import zen.proteus.state.BotState
import zen.proteus.wave.Wave
import java.util.PriorityQueue
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Path surfing: best-first search over per-tick movement options along an orbit
 * tangent — +1 forward, 0 stop, -1 reverse — evaluated with exact physics
 * against the nearest waves. Velocity is signed, so reversing along a tangent
 * costs braking time instead of a 180-degree turn; the two tangent lines (orbit
 * directions) are separate root branches because they end in opposite headings.
 *
 * Search control: nodes are expanded lowest-danger-first with a depth tie-break,
 * so a complete plan (all waves passed) appears quickly and is then improved;
 * since covered danger only grows along a path, a node's danger is an admissible
 * lower bound and anything worse than the best complete plan is pruned. Last
 * tick's plan is re-seeded with a small priority bonus (plan reuse). The node
 * budget caps work per tick, so the search can never skip turns — on exhaustion
 * the best partial plan executes its first step.
 */
internal class PathSurfer(
    private val field: Battlefield,
) {
    /** A movement plan: per-tick options along one tangent [line] (+1/-1). */
    data class Plan(
        val line: Double,
        val options: IntArray,
        val danger: Double,
    )

    private class Node(
        val parent: Node?,
        val option: Int,
        val line: Double,
        val state: MovementSim.State,
        val time: Long,
        val depth: Int,
        val coveredLo: DoubleArray,
        val coveredHi: DoubleArray,
        val done: BooleanArray,
        val danger: Double,
        val finished: Boolean,
        val reused: Boolean,
    )

    fun plan(
        self: BotState,
        enemy: BotState,
        waves: List<Wave>,
        dangerModel: EmpiricalDanger,
        shadows: Map<Wave, List<DoubleArray>>,
        orbitDirection: Double,
        time: Long,
        previous: Plan?,
    ): Plan {
        val sim = MovementSim(field)
        val weights = waveWeights(waves, self, time)
        val queue =
            PriorityQueue<Node>(
                compareBy<Node> { if (it.reused) it.danger * REUSE_BIAS else it.danger }
                    .thenByDescending { it.depth },
            )
        val root =
            Node(
                null,
                0,
                orbitDirection,
                MovementSim.State(self.x, self.y, self.headingRadians, self.velocity),
                time,
                0,
                DoubleArray(waves.size) { Double.POSITIVE_INFINITY },
                DoubleArray(waves.size) { Double.NEGATIVE_INFINITY },
                BooleanArray(waves.size),
                0.0,
                false,
                false,
            )

        var best: Node? = null
        // Plan reuse: re-seed last tick's plan against the current waves.
        if (previous != null && previous.options.isNotEmpty()) {
            var node = root
            for (option in previous.options) {
                node = expand(sim, node, option, previous.line, waves, weights, dangerModel, shadows, enemy, true)
                if (best == null || node.danger < best!!.danger) queue.add(node)
                if (node.finished && (best == null || node.danger < best!!.danger)) best = node
            }
        }

        queue.add(root)
        var expansions = 0
        while (queue.isNotEmpty() && expansions < MAX_EXPANSIONS) {
            val node = queue.poll()
            if (best != null && node.danger >= best.danger) continue
            if (node.finished) {
                if (best == null || node.danger < best!!.danger) best = node
                continue
            }
            expansions++
            val lines = if (node.parent == null) ROOT_LINES else doubleArrayOf(node.line)
            for (line in lines) {
                val branchLine = if (node.parent == null) orbitDirection * line else line
                for (option in OPTIONS) {
                    val child =
                        expand(sim, node, option, branchLine, waves, weights, dangerModel, shadows, enemy, false)
                    if (best == null || child.danger < best.danger) {
                        queue.add(child)
                    }
                }
            }
        }

        val chosen = best ?: queue.peek() ?: root
        return reconstruct(chosen)
    }

    /** Advances one tick: wave tests against the pre-move square, then physics. */
    private fun expand(
        sim: MovementSim,
        parent: Node,
        option: Int,
        line: Double,
        waves: List<Wave>,
        weights: DoubleArray,
        dangerModel: EmpiricalDanger,
        shadows: Map<Wave, List<DoubleArray>>,
        enemy: BotState,
        reused: Boolean,
    ): Node {
        val simTime = parent.time + 1
        val coveredLo = parent.coveredLo.copyOf()
        val coveredHi = parent.coveredHi.copyOf()
        val done = parent.done.copyOf()
        val state = parent.state.copy()

        // Bullet phase of simTime: the ring segment is tested against the robot
        // square where it stood before this tick's move (engine order).
        var allDone = true
        for (i in waves.indices) {
            if (done[i]) continue
            val wave = waves[i]
            val r1 = wave.radius(simTime)
            val interval = wave.intersection(state.x, state.y, r1 - wave.speed, r1)
            if (interval != null) {
                val gf = wave.gfInterval(interval[0], interval[1])
                coveredLo[i] = min(coveredLo[i], gf[0])
                coveredHi[i] = max(coveredHi[i], gf[1])
            } else if (r1 > wave.distanceTo(state.x, state.y) + WAVE_PASS_MARGIN) {
                done[i] = true
            }
            if (!done[i]) allDone = false
        }

        // Robot phase: turn toward the tangent, accelerate to the signed target.
        val biasRadians = DistanceBand.dampedBiasRadians(state.distanceTo(enemy))
        val tangentRadians =
            Angles.absoluteBearingRadians(state.x, state.y, enemy.x, enemy.y) +
                line * (PI / 2.0 + biasRadians)
        sim.step(state, tangentRadians, option * Rules.MAX_VELOCITY, line)

        return Node(
            parent,
            option,
            line,
            state,
            simTime,
            parent.depth + 1,
            coveredLo,
            coveredHi,
            done,
            dangerOf(coveredLo, coveredHi, waves, weights, dangerModel, shadows) +
                if (abs(state.velocity) < LOW_SPEED_LIMIT) LOW_SPEED_PENALTY else 0.0,
            allDone,
            reused,
        )
    }

    private fun dangerOf(
        coveredLo: DoubleArray,
        coveredHi: DoubleArray,
        waves: List<Wave>,
        weights: DoubleArray,
        dangerModel: EmpiricalDanger,
        shadows: Map<Wave, List<DoubleArray>>,
    ): Double {
        var danger = 0.0
        for (i in waves.indices) {
            if (coveredLo[i] <= coveredHi[i]) {
                danger += weights[i] * dangerModel.danger(coveredLo[i], coveredHi[i], shadows[waves[i]])
            }
        }
        return danger
    }

    private fun waveWeights(
        waves: List<Wave>,
        self: BotState,
        time: Long,
    ): DoubleArray {
        val weights = DoubleArray(waves.size)
        for (i in waves.indices) {
            val wave = waves[i]
            weights[i] =
                (WAVE_WEIGHT_BASE + wave.power) /
                sqrt(4.0 + max(1.0, wave.ticksUntilArrival(wave.distanceTo(self.x, self.y), time)))
            if (i >= THIRD_WAVE_INDEX) weights[i] *= THIRD_WAVE_DISCOUNT
        }
        return weights
    }

    private fun reconstruct(node: Node): Plan {
        val options = ArrayList<Int>(node.depth)
        var current: Node? = node
        while (current != null && current.parent != null) {
            options.add(current.option)
            current = current.parent
        }
        options.reverse()
        return Plan(node.line, options.toIntArray(), node.danger)
    }

    companion object {
        // Full-speed in both directions only: with a crude danger model, the
        // stop option invites stop-and-go jitter that simple guns hit for free.
        val OPTIONS = intArrayOf(1, -1)

        // Root branches: keep the current orbit direction, try the other one.
        val ROOT_LINES = doubleArrayOf(1.0, -1.0)
        const val MAX_EXPANSIONS = 800
        const val WAVE_PASS_MARGIN = 26.0
        const val WAVE_WEIGHT_BASE = 0.2
        const val THIRD_WAVE_INDEX = 2
        const val THIRD_WAVE_DISCOUNT = 0.75
        const val REUSE_BIAS = 0.90

        // Per-tick penalty for crawling: near-zero velocity phases (mid-reversal)
        // are where simple guns actually connect, and the GF danger model does
        // not price them in.
        const val LOW_SPEED_LIMIT = 2.0
        const val LOW_SPEED_PENALTY = 0.05
    }
}
