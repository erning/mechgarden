package zen.ronin

import kotlin.math.max
import kotlin.math.min

/**
 * Surf-danger histogram over guess factors. Visits register the hull interval a
 * passing wave swept; hits register a sharp point. Rolling profiles [fade] before
 * registering so they track the enemy gun's *recent* aim.
 */
class GuessFactorDanger(
    private val bins: Int = BINS,
) {
    init {
        require(bins % 2 == 1) { "bin count must be odd so GF 0 is centered, was $bins" }
    }

    private val weight = DoubleArray(bins)
    private val mid = bins / 2

    private fun index(guessFactor: Double): Int = gfToBin(guessFactor, mid)

    fun registerInterval(
        lowGf: Double,
        highGf: Double,
        weight: Double,
    ) {
        val lo = index(min(lowGf, highGf))
        val hi = index(max(lowGf, highGf))
        val per = weight / (hi - lo + 1)
        var i = lo
        while (i <= hi) {
            this.weight[i] += per
            i++
        }
    }

    fun registerSharp(
        guessFactor: Double,
        weight: Double,
    ) {
        val center = index(guessFactor)
        var i = 0
        while (i < this.weight.size) {
            val d = (center - i).toDouble()
            // Quartic kernel — sharper than the visit spread, informs its vicinity.
            this.weight[i] += weight / (d * d * d * d + 1.0)
            i++
        }
    }

    fun fade(retain: Double) {
        var i = 0
        while (i < weight.size) {
            weight[i] *= retain
            i++
        }
    }

    fun totalWeight(): Double = weight.sum()

    /** Per-bin normalized shares (each bin's fraction of the total observed weight;
     * zeros when empty) — the bake-time export. */
    fun shares(): DoubleArray {
        val total = weight.sum()
        val out = DoubleArray(weight.size)
        if (total <= 0.0) return out
        var i = 0
        while (i < weight.size) {
            out[i] = weight[i] / total
            i++
        }
        return out
    }

    companion object {
        const val BINS = 47

        fun binIndex(guessFactor: Double): Int = gfToBin(guessFactor, BINS / 2)
    }
}

/**
 * Multi-buffer surf-danger model (DrussGT-lite). Many [GuessFactorDanger]
 * histograms over different segmentations of the same [WaveFeatures] learn every
 * wave in parallel; [bake] fuses them by maturity. A sparse segment speaks in
 * proportion to what it has seen (per-segment confidence w/(w+CONF_SCALE)), so
 * high-dimensional buffers fade in only where their data is real.
 *
 * Instances live in a per-enemy static registry ([forEnemy]); statics survive
 * Robocode's per-round robot rebuild, so the full ensemble keeps learning across
 * a battle's rounds.
 */
class DangerModel {
    private class Buffer(
        val segments: Int,
        val retain: Double,
        val weight: Double,
        val segmenter: (WaveFeatures) -> Int,
    ) {
        val profiles = Array(segments) { GuessFactorDanger() }

        fun profile(f: WaveFeatures): GuessFactorDanger = profiles[segmenter(f).coerceIn(0, segments - 1)]
    }

    /** Always-on rolling dense profile — the base term of every bake. */
    private val coarse = GuessFactorDanger()

    private val ensemble =
        arrayOf(
            // All-time distance × lateral speed (the legacy fine view).
            Buffer(9, 1.0, 1.0) { f -> Segments.dist3(f.distance) * 3 + Segments.lat3(f.lateralAbs) },
            // Lateral speed only, finer resolution.
            Buffer(5, 1.0, 1.0) { f -> Segments.lat5(f.lateralAbs) },
            // Distance only, finer resolution.
            Buffer(5, 1.0, 1.0) { f -> Segments.dist5(f.distance) },
            // Rolling lateral speed × acceleration — the gun's recent accel-correlated aim.
            Buffer(9, ROLL_RETAIN, 1.0) { f -> Segments.lat3(f.lateralAbs) * 3 + Segments.accel3(f.accelSign) },
            // All-time lateral × forward-wall room.
            Buffer(9, 1.0, 1.0) { f -> Segments.lat3(f.lateralAbs) * 3 + Segments.wall3(f.wallForwardRatio) },
            // High-dim all-time distance × lateral × wall room (weight 1.5).
            Buffer(27, 1.0, 1.5) { f ->
                (Segments.dist3(f.distance) * 3 + Segments.lat3(f.lateralAbs)) * 3 +
                    Segments.wall3(f.wallForwardRatio)
            },
            // High-dim all-time distance × lateral × acceleration (weight 1.5).
            Buffer(27, 1.0, 1.5) { f -> (Segments.dist3(f.distance) * 3 + Segments.lat3(f.lateralAbs)) * 3 + Segments.accel3(f.accelSign) },
            // Rolling distance × lateral — recent range-based aim.
            Buffer(9, ROLL_RETAIN, 1.0) { f -> Segments.dist3(f.distance) * 3 + Segments.lat3(f.lateralAbs) },
            // Single global profile — the always-relevant fallback view.
            Buffer(1, 1.0, 1.0) { _ -> 0 },
        )

    fun registerVisit(
        features: WaveFeatures,
        lowGf: Double,
        highGf: Double,
        weight: Double,
    ) {
        coarse.fade(COARSE_RETAIN)
        coarse.registerInterval(lowGf, highGf, weight)
        for (b in ensemble) {
            val p = b.profile(features)
            if (b.retain < 1.0) p.fade(b.retain)
            p.registerInterval(lowGf, highGf, weight)
        }
    }

    fun registerHit(
        features: WaveFeatures,
        guessFactor: Double,
        weight: Double,
    ) {
        coarse.fade(COARSE_RETAIN)
        coarse.registerSharp(guessFactor, weight)
        for (b in ensemble) {
            val p = b.profile(features)
            if (b.retain < 1.0) p.fade(b.retain)
            p.registerSharp(guessFactor, weight)
        }
    }

    /** Fuse the per-bin danger shares for a wave fired in [features]'s situation:
     * rolling coarse base + the ensemble's confidence-weighted average share
     * (denominator floored at 1). */
    fun bake(features: WaveFeatures): DoubleArray {
        val out = coarse.shares()
        val num = DoubleArray(out.size)
        var denom = 0.0
        for (b in ensemble) {
            val p = b.profile(features)
            val tw = p.totalWeight()
            val conf = tw / (tw + CONF_SCALE)
            if (conf <= 0.0) continue
            val w = b.weight * conf
            val shares = p.shares()
            var i = 0
            while (i < num.size) {
                num[i] += w * shares[i]
                i++
            }
            denom += w
        }
        val scale = denom.coerceAtLeast(1.0)
        var i = 0
        while (i < out.size) {
            out[i] += num[i] / scale
            i++
        }
        return out
    }

    companion object {
        const val COARSE_RETAIN = 0.97
        const val ROLL_RETAIN = 0.96
        const val CONF_SCALE = 25.0

        private val perEnemy = HashMap<String, DangerModel>()

        fun forEnemy(name: String): DangerModel = perEnemy.getOrPut(name) { DangerModel() }
    }
}
