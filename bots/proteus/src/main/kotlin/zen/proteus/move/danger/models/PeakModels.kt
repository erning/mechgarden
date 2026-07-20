package zen.proteus.move.danger.models

import zen.proteus.core.Angles
import zen.proteus.core.normalMass
import zen.proteus.move.danger.DangerModel
import zen.proteus.move.danger.EnemyWave
import zen.proteus.state.BotState
import zen.proteus.wave.GuessFactorBins
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Simulated simple guns: each replicates one classic targeting method from the
 * enemy's perspective and paints a narrow Gaussian where it would aim. While
 * the enemy's hit rate stays below the gate these dominate the ensemble, and
 * they dodge simple guns almost perfectly.
 */
internal abstract class PeakModel : DangerModel {
    override val minHitRate = 0.0
    override val maxHitRate = 0.07

    private val cache = HashMap<EnemyWave, DoubleArray>()

    override fun binsFor(
        wave: EnemyWave,
        evalX: Double,
        evalY: Double,
    ): DoubleArray = cache.getOrPut(wave) { paint(centerGf(wave)) }

    override fun forget(wave: EnemyWave) {
        cache.remove(wave)
    }

    /** The guess factor this gun would aim at, computed once per wave. */
    protected abstract fun centerGf(wave: EnemyWave): Double

    private fun paint(centerGf: Double): DoubleArray {
        val bins = DoubleArray(GuessFactorBins.COUNT)
        for (i in bins.indices) {
            val gf = GuessFactorBins.guessFactorAt(i)
            bins[i] = normalMass(centerGf, PEAK_SIGMA, gf - BIN_HALF_WIDTH, gf + BIN_HALF_WIDTH)
        }
        return bins
    }

    companion object {
        const val PEAK_SIGMA = 0.08
        val BIN_HALF_WIDTH = 1.0 / (GuessFactorBins.COUNT - 1)

        /** Iterative lead from the wave origin against [state] moving at
         *  (heading, velocity), bullets at the wave's speed. */
        fun leadGf(
            wave: EnemyWave,
            state: BotState,
            turnRateRadiansPerTick: Double,
        ): Double {
            val w = wave.wave
            var targetX = state.x
            var targetY = state.y
            var heading = state.headingRadians
            val velocity = state.velocity
            repeat(2) {
                val flightTicks = hypot(targetX - w.originX, targetY - w.originY) / w.speed
                targetX = state.x + sin(heading) * velocity * flightTicks
                targetY = state.y + cos(heading) * velocity * flightTicks
                heading = state.headingRadians + turnRateRadiansPerTick * flightTicks
            }
            // Recompute with the final heading for the circular case.
            val flightTicks = hypot(targetX - w.originX, targetY - w.originY) / w.speed
            targetX = state.x + sin(heading) * velocity * flightTicks
            targetY = state.y + cos(heading) * velocity * flightTicks
            val angle = Angles.absoluteBearingRadians(w.originX, w.originY, targetX, targetY)
            return w.guessFactor(angle)
        }
    }
}

/** Head-on: aims where we stood at fire time — guess factor zero. */
internal class HotModel : PeakModel() {
    override fun centerGf(wave: EnemyWave): Double = 0.0
}

/** Straight-line lead from our fire-time motion. */
internal class LinearModel : PeakModel() {
    override fun centerGf(wave: EnemyWave): Double = leadGf(wave, wave.fireSelf, 0.0)
}

/** Circular lead: keep the turn rate we had at fire time. */
internal class CircularModel : PeakModel() {
    override fun centerGf(wave: EnemyWave): Double {
        val prev = wave.fireSelfPrev
        val turnRate =
            if (prev != null && prev.time == wave.fireSelf.time - 1 && wave.fireSelf.velocity != 0.0) {
                Angles.normalizeRelative(wave.fireSelf.headingRadians - prev.headingRadians)
            } else {
                0.0
            }
        return leadGf(wave, wave.fireSelf, turnRate)
    }
}

/** Linear lead using the average velocity over the last ticks before firing. */
internal class AvgLinearModel : PeakModel() {
    override val maxHitRate = 0.10

    override fun centerGf(wave: EnemyWave): Double {
        val state = wave.fireSelf
        val prev = wave.fireSelfPrev
        val avgState =
            if (prev != null && prev.time == state.time - 1) {
                val avgVelocity = (state.velocity + prev.velocity) / 2.0
                val avgHeading =
                    state.headingRadians +
                        Angles.normalizeRelative(prev.headingRadians - state.headingRadians) / 2.0
                state.copy(headingRadians = avgHeading, velocity = avgVelocity)
            } else {
                state
            }
        return leadGf(wave, avgState, 0.0)
    }
}

/** Current GF: aims where we are *now*, plus its mirror. Dynamic — no cache. */
internal class CurrentGfModel : DangerModel {
    override val minHitRate = 0.0
    override val maxHitRate = 1.0

    override fun binsFor(
        wave: EnemyWave,
        evalX: Double,
        evalY: Double,
    ): DoubleArray {
        val w = wave.wave
        val currentGf = w.guessFactor(Angles.absoluteBearingRadians(w.originX, w.originY, evalX, evalY))
        val bins = DoubleArray(GuessFactorBins.COUNT)
        for (i in bins.indices) {
            val gf = GuessFactorBins.guessFactorAt(i)
            val step = PeakModel.BIN_HALF_WIDTH
            bins[i] =
                normalMass(currentGf, SIGMA, gf - step, gf + step) +
                MIRROR_WEIGHT * normalMass(-currentGf, SIGMA, gf - step, gf + step)
        }
        return bins
    }

    private companion object {
        const val SIGMA = 0.08
        const val MIRROR_WEIGHT = 0.5
    }
}
