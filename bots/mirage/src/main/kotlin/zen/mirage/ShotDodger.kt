package zen.mirage

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot

/**
 * Per-opponent simple-gun expert selector for ShotDodger-lite.
 *
 * Each inferred enemy wave carries separate Head-on, Linear, Circular and
 * Wall-linear predictions. Bullet hits provide a realized GF error for every
 * expert; a passed wave only contradicts experts whose predicted line crossed
 * the hull interval we actually occupied. Once one expert has enough evidence,
 * a useful accuracy floor and a clear lead over distinct predictions, its line
 * is added as a strong but bounded danger peak. Three consecutive failures drop
 * the expert immediately back to the ordinary empirical surfer.
 */
class ShotDodger {
    data class Decision(
        val expert: SimulatedTargeting.Expert,
        val guessFactor: Double,
        val accuracy: Double,
        val observations: Long,
        val lead: Double,
        val consecutiveMisses: Int,
    )

    private class ExpertStats {
        var weightedAccuracy = 0.0
        var weight = 0.0
        var observations = 0L
        var consecutiveMisses = 0

        val accuracy: Double
            get() = if (weight <= 0.0) 0.0 else weightedAccuracy / weight

        fun record(sampleAccuracy: Double) {
            weightedAccuracy = weightedAccuracy * SCORE_RETAIN + sampleAccuracy
            weight = weight * SCORE_RETAIN + 1.0
            observations++
            consecutiveMisses =
                if (sampleAccuracy < MISS_ACCURACY) {
                    consecutiveMisses + 1
                } else {
                    0
                }
        }
    }

    private class Model {
        val stats = Array(SimulatedTargeting.Expert.values().size) { ExpertStats() }

        fun recordHit(
            predictions: SimulatedTargeting.Predictions,
            actualGuessFactor: Double,
            hullHalfGuessFactor: Double,
        ) {
            val tolerance = maxOf(hullHalfGuessFactor, MIN_ERROR_TOLERANCE)
            for (expert in SimulatedTargeting.Expert.values()) {
                val outsideHullError = (abs(predictions[expert] - actualGuessFactor) - hullHalfGuessFactor).coerceAtLeast(0.0)
                val normalizedError = outsideHullError / tolerance
                stats[expert.ordinal].record(1.0 / (1.0 + normalizedError * normalizedError))
            }
        }

        fun recordPass(
            predictions: SimulatedTargeting.Predictions,
            coveredLowGuessFactor: Double,
            coveredHighGuessFactor: Double,
        ) {
            val low = minOf(coveredLowGuessFactor, coveredHighGuessFactor)
            val high = maxOf(coveredLowGuessFactor, coveredHighGuessFactor)
            for (expert in SimulatedTargeting.Expert.values()) {
                if (predictions[expert] in low..high) stats[expert.ordinal].record(0.0)
            }
        }

        fun decision(
            predictions: SimulatedTargeting.Predictions,
            force: Boolean,
        ): Decision? {
            var bestExpert: SimulatedTargeting.Expert? = null
            var bestAccuracy = Double.NEGATIVE_INFINITY
            for (expert in SimulatedTargeting.Expert.values()) {
                val candidate = stats[expert.ordinal]
                if (!force && candidate.observations < MIN_OBSERVATIONS) continue
                val accuracy = if (candidate.observations == 0L) FORCED_PRIOR_ACCURACY else candidate.accuracy
                if (accuracy > bestAccuracy) {
                    bestAccuracy = accuracy
                    bestExpert = expert
                }
            }
            val selected = bestExpert ?: return null
            val selectedStats = stats[selected.ordinal]
            if (!force) {
                if (bestAccuracy < MIN_ACCURACY) return null
                if (selectedStats.consecutiveMisses >= MAX_CONSECUTIVE_MISSES) return null
            }

            var runnerUpAccuracy = Double.NEGATIVE_INFINITY
            for (expert in SimulatedTargeting.Expert.values()) {
                if (expert == selected) continue
                val candidate = stats[expert.ordinal]
                if (!force && candidate.observations < MIN_OBSERVATIONS) continue
                if (abs(predictions[expert] - predictions[selected]) <= EQUIVALENT_GUESS_FACTOR) continue
                val accuracy = if (candidate.observations == 0L) FORCED_PRIOR_ACCURACY else candidate.accuracy
                runnerUpAccuracy = maxOf(runnerUpAccuracy, accuracy)
            }
            val lead =
                if (runnerUpAccuracy.isFinite()) {
                    bestAccuracy - runnerUpAccuracy
                } else {
                    bestAccuracy
                }
            if (!force && lead < MIN_LEAD) return null
            return Decision(
                expert = selected,
                guessFactor = predictions[selected],
                accuracy = bestAccuracy,
                observations = selectedStats.observations,
                lead = lead,
                consecutiveMisses = selectedStats.consecutiveMisses,
            )
        }

        fun bestSummary(): String {
            var best = SimulatedTargeting.Expert.HEAD_ON
            for (expert in SimulatedTargeting.Expert.values()) {
                val candidate = stats[expert.ordinal]
                val current = stats[best.ordinal]
                if (candidate.observations > 0L && candidate.accuracy > current.accuracy) best = expert
            }
            val value = stats[best.ordinal]
            return "${label(best)}/${"%.2f".format(value.accuracy)}@${value.observations}/${value.consecutiveMisses}"
        }
    }

    private var model = Model()
    private var lastDecision: Decision? = null
    private var appliedWaves = 0L

    fun adoptEnemy(name: String) {
        model = perEnemy.getOrPut(name) { Model() }
    }

    fun augmentDanger(
        baseDanger: DoubleArray,
        predictions: SimulatedTargeting.Predictions,
    ): DoubleArray {
        val mode = System.getProperty("mirage.shotdodger")?.trim()?.lowercase()
        if (mode in OFF_VALUES) {
            lastDecision = null
            return baseDanger
        }
        val decision = model.decision(predictions, force = mode == "force")
        lastDecision = decision
        if (decision == null) return baseDanger

        val weight = System.getProperty("mirage.shotweight")?.toDoubleOrNull() ?: DEFAULT_DANGER_WEIGHT
        if (weight <= 0.0) return baseDanger
        val peak = DoubleArray(baseDanger.size)
        val mid = baseDanger.size / 2
        var peakTotal = 0.0
        for (i in peak.indices) {
            val centerGuessFactor = (i - mid).toDouble() / mid.toDouble()
            peak[i] = exp(-DANGER_KERNEL_LAMBDA * abs(centerGuessFactor - decision.guessFactor))
            peakTotal += peak[i]
        }
        val out = baseDanger.copyOf()
        if (peakTotal > 0.0) {
            for (i in out.indices) out[i] += weight * peak[i] / peakTotal
        }
        appliedWaves++
        return out
    }

    fun recordHit(
        wave: EnemyWave,
        bulletX: Double,
        bulletY: Double,
    ) {
        val predictions = wave.simpleTargetPredictions ?: return
        val actualGuessFactor = wave.guessFactor(bulletX, bulletY)
        val hullHalfGuessFactor = wave.hullHalfGf(hypot(bulletX - wave.sourceX, bulletY - wave.sourceY))
        model.recordHit(predictions, actualGuessFactor, hullHalfGuessFactor)
    }

    fun recordPass(wave: EnemyWave) {
        val predictions = wave.simpleTargetPredictions ?: return
        if (wave.coveredLowGf.isNaN() || wave.coveredHighGf.isNaN()) return
        model.recordPass(predictions, wave.coveredLowGf, wave.coveredHighGf)
    }

    internal fun recordHit(
        predictions: SimulatedTargeting.Predictions,
        actualGuessFactor: Double,
        hullHalfGuessFactor: Double,
    ) {
        model.recordHit(predictions, actualGuessFactor, hullHalfGuessFactor)
    }

    internal fun recordPass(
        predictions: SimulatedTargeting.Predictions,
        coveredLowGuessFactor: Double,
        coveredHighGuessFactor: Double,
    ) {
        model.recordPass(predictions, coveredLowGuessFactor, coveredHighGuessFactor)
    }

    internal fun decision(predictions: SimulatedTargeting.Predictions): Decision? = model.decision(predictions, force = false)

    fun debugSummary(): String {
        val active = lastDecision
        return if (active == null) {
            "shotDodger=idle/$appliedWaves best=${model.bestSummary()}"
        } else {
            "shotDodger=${label(active.expert)}/${"%.2f".format(active.accuracy)}@${active.observations}" +
                "/${"%.2f".format(active.lead)}/${active.consecutiveMisses}/$appliedWaves"
        }
    }

    private companion object {
        const val SCORE_RETAIN = 0.97
        const val MIN_ERROR_TOLERANCE = 0.035
        const val MISS_ACCURACY = 0.35
        const val MIN_OBSERVATIONS = 8L
        const val MIN_ACCURACY = 0.62
        const val MIN_LEAD = 0.12
        const val MAX_CONSECUTIVE_MISSES = 3
        const val EQUIVALENT_GUESS_FACTOR = 0.06
        const val FORCED_PRIOR_ACCURACY = 0.5
        const val DEFAULT_DANGER_WEIGHT = 0.55
        const val DANGER_KERNEL_LAMBDA = 32.0
        val OFF_VALUES = setOf("off", "false", "no")
        val perEnemy = HashMap<String, Model>()

        fun label(expert: SimulatedTargeting.Expert): String =
            when (expert) {
                SimulatedTargeting.Expert.HEAD_ON -> "HO"
                SimulatedTargeting.Expert.LINEAR -> "LIN"
                SimulatedTargeting.Expert.CIRCULAR -> "CIR"
                SimulatedTargeting.Expert.WALL_LINEAR -> "WLIN"
            }
    }
}
