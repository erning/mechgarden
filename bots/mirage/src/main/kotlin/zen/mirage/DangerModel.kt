package zen.mirage

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

    fun totalWeight(): Double {
        var total = 0.0
        var i = 0
        while (i < weight.size) {
            total += weight[i]
            i++
        }
        return total
    }

    /** Per-bin normalized shares (each bin's fraction of the total observed weight;
     *  zeros when empty) — the bake-time export. */
    fun shares(): DoubleArray {
        val out = DoubleArray(weight.size)
        writeShares(out)
        return out
    }

    fun writeShares(out: DoubleArray) {
        val total = totalWeight()
        var i = 0
        if (total <= 0.0) return
        while (i < weight.size) {
            out[i] = weight[i] / total
            i++
        }
    }

    fun addSharesTo(
        out: DoubleArray,
        scale: Double,
        total: Double,
    ) {
        if (total <= 0.0) return
        val factor = scale / total
        var i = 0
        while (i < weight.size) {
            out[i] += weight[i] * factor
            i++
        }
    }

    fun windowShare(
        lowGf: Double,
        highGf: Double,
    ): Double {
        val total = totalWeight()
        if (total <= 0.0) return 0.0
        val lo = index(min(lowGf, highGf))
        val hi = index(max(lowGf, highGf))
        var sum = 0.0
        var i = lo
        while (i <= hi) {
            sum += weight[i]
            i++
        }
        return sum / total / (hi - lo + 1)
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
 * Robocode's per-round robot rebuild, so the ensemble keeps learning across a
 * battle's rounds.
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

    private val coarse = GuessFactorDanger()
    private val numScratch = DoubleArray(GuessFactorDanger.BINS)

    private val ensemble =
        arrayOf(
            Buffer(9, 1.0, 1.0) { f -> Segments.dist3(f.distance) * 3 + Segments.lat3(f.lateralAbs) },
            Buffer(5, 1.0, 1.0) { f -> Segments.lat5(f.lateralAbs) },
            Buffer(5, 1.0, 1.0) { f -> Segments.dist5(f.distance) },
            Buffer(9, ROLL_RETAIN, 1.0) { f -> Segments.lat3(f.lateralAbs) * 3 + Segments.accel3(f.accelSign) },
            Buffer(9, 1.0, 1.0) { f -> Segments.lat3(f.lateralAbs) * 3 + Segments.wall3(f.wallForwardRatio) },
            Buffer(27, 1.0, 1.5) { f ->
                (Segments.dist3(f.distance) * 3 + Segments.lat3(f.lateralAbs)) * 3 +
                    Segments.wall3(f.wallForwardRatio)
            },
            Buffer(27, 1.0, 1.5) { f ->
                (Segments.dist3(f.distance) * 3 + Segments.lat3(f.lateralAbs)) * 3 + Segments.accel3(f.accelSign)
            },
            Buffer(9, ROLL_RETAIN, 1.0) { f -> Segments.dist3(f.distance) * 3 + Segments.lat3(f.lateralAbs) },
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

    fun bake(features: WaveFeatures): DoubleArray {
        val out = DoubleArray(GuessFactorDanger.BINS)
        coarse.writeShares(out)
        java.util.Arrays.fill(numScratch, 0.0)
        var denom = 0.0
        for (b in ensemble) {
            val p = b.profile(features)
            val tw = p.totalWeight()
            val conf = tw / (tw + CONF_SCALE)
            if (conf <= 0.0) continue
            val w = b.weight * conf
            p.addSharesTo(numScratch, w, tw)
            denom += w
        }
        val scale = denom.coerceAtLeast(1.0)
        var i = 0
        while (i < out.size) {
            out[i] += numScratch[i] / scale
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
