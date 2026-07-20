package zen.proteus.shield

import zen.proteus.core.Angles
import zen.proteus.state.BotState
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Online enemy-aim classifier for bullet shielding. Each candidate predictor
 * maps (wave origin, our state at fire time) to the enemy bullet's predicted
 * absolute angle; when a real bullet resolves we score the predictors by their
 * signed error. A predictor with enough samples, a tight spread, and a small
 * mean error means their aim is fully predictable — the shield may engage.
 *
 * All errors are in radians, normalized to (-PI, PI].
 */
internal class AimPredictors {
    /** Predictor index. */
    enum class Id { HOT, LINEAR, CIRCULAR, STALE, EXTRAPOLATED }

    private val errors = HashMap<Id, ArrayDeque<Double>>()

    /** Predicted absolute angles of a bullet fired from (originX, originY) at
     *  [speed], given our state at fire time ([selfAtFire], [selfAtFirePrev]). */
    fun predict(
        originX: Double,
        originY: Double,
        speed: Double,
        selfAtFire: BotState,
        selfAtFirePrev: BotState?,
    ): Map<Id, Double> {
        val result = HashMap<Id, Double>()
        result[Id.HOT] = Angles.absoluteBearingRadians(originX, originY, selfAtFire.x, selfAtFire.y)
        result[Id.LINEAR] = lead(originX, originY, speed, selfAtFire, 0.0)
        val turnRate =
            if (selfAtFirePrev != null && selfAtFirePrev.time == selfAtFire.time - 1 && selfAtFire.velocity != 0.0) {
                Angles.normalizeRelative(selfAtFire.headingRadians - selfAtFirePrev.headingRadians)
            } else {
                0.0
            }
        result[Id.CIRCULAR] = lead(originX, originY, speed, selfAtFire, turnRate)
        result[Id.STALE] =
            if (selfAtFirePrev != null) {
                Angles.absoluteBearingRadians(originX, originY, selfAtFirePrev.x, selfAtFirePrev.y)
            } else {
                result.getValue(Id.HOT)
            }
        val extrapolatedX = selfAtFire.x + sin(selfAtFire.headingRadians) * selfAtFire.velocity
        val extrapolatedY = selfAtFire.y + cos(selfAtFire.headingRadians) * selfAtFire.velocity
        result[Id.EXTRAPOLATED] = Angles.absoluteBearingRadians(originX, originY, extrapolatedX, extrapolatedY)
        return result
    }

    /** A real bullet resolved at [actualAngleRadians]; score every predictor. */
    fun noteResolved(
        predictions: Map<Id, Double>,
        actualAngleRadians: Double,
    ) {
        for ((id, predicted) in predictions) {
            val error = Angles.normalizeRelative(actualAngleRadians - predicted)
            val history = errors.getOrPut(id) { ArrayDeque() }
            history.addLast(error)
            while (history.size > HISTORY_LIMIT) history.removeFirst()
        }
    }

    /** The confident predictor, if one classifies their aim tightly enough. */
    fun best(): Pair<Id, Double>? {
        var best: Pair<Id, Double>? = null
        var bestStd = Double.POSITIVE_INFINITY
        for ((id, history) in errors) {
            if (history.size < MIN_SAMPLES) continue
            val mean = history.average()
            val std = sqrt(history.map { (it - mean) * (it - mean) }.average())
            if (abs(mean) < MAX_MEAN_ERROR && std < MAX_STD_ERROR && std < bestStd) {
                bestStd = std
                best = id to mean
            }
        }
        return best
    }

    fun reset() {
        errors.clear()
    }

    private fun lead(
        originX: Double,
        originY: Double,
        speed: Double,
        state: BotState,
        turnRate: Double,
    ): Double {
        var targetX = state.x
        var targetY = state.y
        var heading = state.headingRadians
        repeat(2) {
            val flightTicks = hypot(targetX - originX, targetY - originY) / speed
            targetX = state.x + sin(heading) * state.velocity * flightTicks
            targetY = state.y + cos(heading) * state.velocity * flightTicks
            heading = state.headingRadians + turnRate * flightTicks
        }
        return Angles.absoluteBearingRadians(originX, originY, targetX, targetY)
    }

    private companion object {
        const val HISTORY_LIMIT = 30
        const val MIN_SAMPLES = 10
        const val MAX_MEAN_ERROR = 0.03
        const val MAX_STD_ERROR = 0.02
    }
}
