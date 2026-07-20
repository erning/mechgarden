package zen.proteus.move

import robocode.Rules
import zen.proteus.core.Angles
import zen.proteus.core.Battlefield
import zen.proteus.move.danger.EmpiricalDanger
import zen.proteus.state.BotState
import zen.proteus.wave.Wave
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * True surfing: simulates FORWARD / BACKWARD / STOP tick by tick against the
 * nearest waves and picks the option whose covered guess-factor interval has
 * the lowest danger. Waves are weighted by power and time-to-arrival so closer,
 * heavier waves dominate the choice.
 */
internal class Surfer(
    private val field: Battlefield,
) {
    enum class Option { FORWARD, BACKWARD, STOP }

    data class Choice(
        val option: Option,
        val orbitDirection: Double,
    )

    fun choose(
        self: BotState,
        enemy: BotState,
        waves: List<Wave>,
        danger: EmpiricalDanger,
        shadows: Map<Wave, List<DoubleArray>>,
        orbitDirection: Double,
        time: Long,
    ): Choice {
        var best = Choice(Option.FORWARD, orbitDirection)
        var bestScore = Double.POSITIVE_INFINITY
        for (option in Option.entries) {
            val direction = if (option == Option.BACKWARD) -orbitDirection else orbitDirection
            val score = simulate(self, enemy, waves, danger, shadows, option, direction, time)
            // Prefer keeping the current orbit direction on near-ties (stability).
            val biased = score + if (option == Option.FORWARD) 0.0 else TIE_EPSILON
            if (biased < bestScore) {
                bestScore = biased
                best = Choice(option, direction)
            }
        }
        return best
    }

    private fun simulate(
        self: BotState,
        enemy: BotState,
        waves: List<Wave>,
        danger: EmpiricalDanger,
        shadows: Map<Wave, List<DoubleArray>>,
        option: Option,
        orbitDirection: Double,
        time: Long,
    ): Double {
        val sim = MovementSim(field)
        val state = MovementSim.State(self.x, self.y, self.headingRadians, self.velocity)
        val targetVelocity = if (option == Option.STOP) 0.0 else Rules.MAX_VELOCITY
        val coveredLo = DoubleArray(waves.size) { Double.POSITIVE_INFINITY }
        val coveredHi = DoubleArray(waves.size) { Double.NEGATIVE_INFINITY }
        val done = BooleanArray(waves.size)
        var remaining = waves.size
        var simTime = time
        var ticks = 0
        while (remaining > 0 && ticks < MAX_SIM_TICKS) {
            simTime++
            // Bullets move before robots: test the ring segment at the position
            // held before this tick's movement, then move.
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
                    remaining--
                }
            }
            if (remaining == 0) break
            // Orbit the live enemy position (not the stale wave origin) with a
            // damped distance bias, so surfing holds range without twisting the
            // escape geometry.
            val biasRadians = DistanceBand.dampedBiasRadians(state.distanceTo(enemy))
            val tangentRadians =
                Angles.absoluteBearingRadians(state.x, state.y, enemy.x, enemy.y) +
                    orbitDirection * (PI / 2.0 + biasRadians)
            sim.step(state, tangentRadians, targetVelocity, orbitDirection)
            ticks++
        }
        var score = 0.0
        for (i in waves.indices) {
            if (coveredLo[i] <= coveredHi[i]) {
                val wave = waves[i]
                val weight =
                    (WAVE_WEIGHT_BASE + wave.power) /
                        sqrt(4.0 + max(1.0, wave.ticksUntilArrival(wave.distanceTo(self.x, self.y), time)))
                score += weight * danger.danger(coveredLo[i], coveredHi[i], shadows[wave])
            }
        }
        return score
    }

    companion object {
        const val MAX_SIM_TICKS = 120

        // > 18 * sqrt(2): the ring is past every point of the robot square.
        const val WAVE_PASS_MARGIN = 26.0
        const val WAVE_WEIGHT_BASE = 0.2
        const val TIE_EPSILON = 1e-9
    }
}
