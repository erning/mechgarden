package zen.fencer

/**
 * Multi-buffer surf-danger model (DrussGT-lite): many [GuessFactorDanger]
 * histograms over **different segmentations** of the same [WaveFeatures] learn
 * every wave in parallel, and the bake fuses them by maturity. The structure
 * deliberately keeps the proven two-profile shape as its backbone:
 *
 * - the **rolling 1×1 coarse** profile is the always-on base term (it tracks
 *   the enemy gun's *current* aim, anti-surfer), exactly as before;
 * - everything else forms a **confidence-weighted ensemble** whose fused share
 *   is a weighted *average* (denominator floored at 1), so with only the old
 *   dist×lat buffer present the bake reduces exactly to the previous
 *   `coarse + conf×fine` — and added buffers refine the mix instead of
 *   inflating the danger scale the wall/stop-bias terms were tuned against.
 *
 * A sparse segment speaks in proportion to what it has seen (per-segment
 * confidence `w/(w+25)`), so high-dimensional buffers fade in only where their
 * data is real — the guard against the recorded negative where widening the
 * *single* fine profile to 27 segments thinned its data and collapsed battles.
 *
 * All buffers learn both precise-interval visits and sharp hits (no visit-only
 * flattener buffers — a recorded negative). Instances live in a per-enemy static
 * registry ([forEnemy]); statics survive Robocode's per-round robot rebuild, so
 * the model keeps learning across a battle's rounds without disk persistence.
 */
class DangerBuffers {
    private class Buffer(
        val segments: Int,
        /** 1.0 = all-time; below it the touched profile fades before learning. */
        val retain: Double,
        val weight: Double,
        val segmenter: (WaveFeatures) -> Int,
    ) {
        val profiles = Array(segments) { GuessFactorDanger() }

        fun profile(f: WaveFeatures): GuessFactorDanger = profiles[segmenter(f).coerceIn(0, segments - 1)]
    }

    /** The always-on rolling dense profile — the base term of every bake. */
    private val coarse = GuessFactorDanger()

    /** The legacy fine view (distance × lateral speed, all-time). */
    private val fine = Buffer(9, 1.0, 1.0, { f -> dist3(f.distance) * 3 + lat3(f.lateralAbs) })

    private val ensemble =
        arrayOf(
            fine,
            Buffer(5, 1.0, 1.0, { f -> lat5(f.lateralAbs) }),
            Buffer(5, 1.0, 1.0, { f -> dist5(f.distance) }),
            Buffer(9, ROLL_RETAIN, 1.0, { f -> lat3(f.lateralAbs) * 3 + accel3(f.accelSign) }),
            Buffer(9, 1.0, 1.0, { f -> lat3(f.lateralAbs) * 3 + wall3(f.wallForwardRatio) }),
            // The high-dimensional views weigh 1.5: when a fine segment has
            // matured (confidence already gates immaturity) its more specific
            // read carries more information than the broad views'.
            Buffer(27, 1.0, 1.5, { f -> (dist3(f.distance) * 3 + lat3(f.lateralAbs)) * 3 + wall3(f.wallForwardRatio) }),
            Buffer(12, 1.0, 1.5, { f -> lat3(f.lateralAbs) * 4 + decel4(f.ticksSinceDecel) }),
            Buffer(27, 1.0, 1.5, { f -> (dist3(f.distance) * 3 + lat3(f.lateralAbs)) * 3 + accel3(f.accelSign) }),
            Buffer(1, 1.0, 1.0, { _ -> 0 }),
        )

    /** Learn a wave that swept our hull across [lowGf, highGf] without hitting. */
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

    /** Learn a real hit at the bullet's exact [guessFactor], sharply. */
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

    /** Fuse the per-bin danger shares for a wave fired in [features]'s
     * situation: rolling coarse base + the ensemble's confidence-weighted
     * average share (denominator floored at 1 so an immature ensemble
     * contributes in proportion to its confidence rather than being inflated
     * to a full vote). */
    fun bake(features: WaveFeatures): DoubleArray {
        val out = coarse.shares()
        val num = DoubleArray(out.size)
        var denom = 0.0
        for (b in ensemble) {
            val p = b.profile(features)
            val conf = p.totalWeight().let { it / (it + CONF_SCALE) }
            if (conf <= 0.0) continue
            val w = b.weight * conf
            val shares = p.shares()
            for (i in num.indices) num[i] += w * shares[i]
            denom += w
        }
        val scale = denom.coerceAtLeast(1.0)
        for (i in out.indices) out[i] += num[i] / scale
        return out
    }

    /** Legacy-layout snapshot (fine 9×bins + coarse bins). */
    fun snapshot(): DoubleArray {
        val per = coarse.snapshot().size
        val out = DoubleArray((fine.segments + 1) * per)
        for (s in 0 until fine.segments) System.arraycopy(fine.profiles[s].snapshot(), 0, out, s * per, per)
        System.arraycopy(coarse.snapshot(), 0, out, fine.segments * per, per)
        return out
    }

    fun restore(data: DoubleArray) {
        val per = coarse.snapshot().size
        if (data.size != (fine.segments + 1) * per) return
        for (s in 0 until fine.segments) fine.profiles[s].restore(data.copyOfRange(s * per, (s + 1) * per))
        coarse.restore(data.copyOfRange(fine.segments * per, (fine.segments + 1) * per))
    }

    /** Has this model already learned anything this battle? */
    fun isWarm(): Boolean = coarse.totalWeight() > 0.0

    companion object {
        const val COARSE_RETAIN = 0.98
        const val ROLL_RETAIN = 0.98

        /** Per-segment weight at which a profile's confidence reaches ½. */
        const val CONF_SCALE = 25.0

        private fun dist3(d: Double): Int = (d / 200.0).toInt().coerceIn(0, 2)

        private fun dist5(d: Double): Int = (d / 160.0).toInt().coerceIn(0, 4)

        private fun lat3(lat: Double): Int = (lat / Kinematics.MAX_VELOCITY * 3).toInt().coerceIn(0, 2)

        private fun lat5(lat: Double): Int = (lat / Kinematics.MAX_VELOCITY * 5).toInt().coerceIn(0, 4)

        private fun accel3(sign: Int): Int = sign + 1

        private fun decel4(ticks: Long): Int =
            when {
                ticks <= 1L -> 0
                ticks <= 4L -> 1
                ticks <= 9L -> 2
                else -> 3
            }

        private fun wall3(ratio: Double): Int =
            when {
                ratio < 0.5 -> 0
                ratio < 1.0 -> 1
                else -> 2
            }

        /** Per-enemy registry: statics survive the per-round robot rebuild, so
         * the full ensemble keeps learning across a battle's rounds. */
        private val perEnemy = HashMap<String, DangerBuffers>()

        fun forEnemy(name: String): DangerBuffers = perEnemy.getOrPut(name) { DangerBuffers() }
    }
}
