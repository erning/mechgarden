package zen.ronin

import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Dynamic-clustering gun: instead of fixed segmentation, it remembers every past
 * wave (as a fire-time feature vector plus the guess factor the enemy eventually
 * reached) and, at fire time, finds the [k] nearest past situations by feature
 * distance and fires at the peak of their guess-factor mass. Against an
 * *un-flattened* adaptive mover — whose visits form learnable, multi-dimensional
 * peaks — a KNN blend of similar past shots hits where a single hard segmentation
 * spreads its data too thin to resolve. (Fencer has no movement flattener, so its
 * visits cluster; this gun reads those clusters.)
 *
 * Instances live in a per-enemy static registry ([forEnemy]); statics survive the
 * per-round robot rebuild, so the observation set keeps growing across a battle.
 */
class DcGun(
    private val k: Int = 25,
    private val bins: Int = 31,
) {
    private data class Pending(
        val features: DoubleArray,
        val fireX: Double,
        val fireY: Double,
        val fireTime: Long,
        val bulletSpeed: Double,
        val directAngleDeg: Double,
        val orbitSign: Int,
        val maxEscapeDeg: Double,
        val halfBotWidthGf: Double,
        val profileGuessFactors: DoubleArray,
    )

    private data class Obs(
        val features: DoubleArray,
        val gf: Double,
    )

    private val pending = mutableListOf<Pending>()
    private val obs = ArrayList<Obs>(2048)
    private val mid = bins / 2
    private val profileScore = DoubleArray(PROFILES.size)
    private var profileAttempts = 0.0
    private var activeProfileIndex = DEFAULT_PROFILE_INDEX

    /** Reusable (dist², gf) holder for the KNN sort, plus a reusable histogram and
     * comparator. When the DC gun is the primary aim its KNN runs every scan;
     * reusing these avoids allocating thousands of Pair / boxed-Double objects per
     * scan (the previous hot allocation path). */
    private class Score(
        var d2: Double,
        var gf: Double,
    )

    private val scoreBuf = Array(CAP) { Score(0.0, 0.0) }
    private val histBuf = DoubleArray(bins)

    /** Fire-time feature vector (normalized) the KNN distance is measured over. */
    fun features(
        distance: Double,
        lateralAbs: Double,
        advancingAbs: Double,
        accel: Double,
        wallForwardRatio: Double,
        timeSinceDirectionChange: Long,
    ): DoubleArray =
        doubleArrayOf(
            distance / 1200.0,
            lateralAbs / Kinematics.MAX_VELOCITY,
            advancingAbs / Kinematics.MAX_VELOCITY,
            accel.coerceIn(-2.0, 2.0) / 2.0,
            wallForwardRatio.coerceIn(0.0, 2.0),
            (timeSinceDirectionChange.coerceIn(0L, 20L)) / 20.0,
        )

    fun onFire(
        features: DoubleArray,
        fireX: Double,
        fireY: Double,
        fireTime: Long,
        bulletSpeed: Double,
        directAngleDeg: Double,
        orbitSign: Int,
        maxEscapeDeg: Double,
    ) {
        val distance = (features[0] * 1200.0).coerceAtLeast(Kinematics.HALF_BOT)
        val halfBotWidthGf = Math.toDegrees(atan(Kinematics.HALF_BOT / distance)) / maxEscapeDeg
        val ownedFeatures = features.copyOf()
        val profileGuessFactors = predictAllProfileGfs(ownedFeatures, halfBotWidthGf)
        pending +=
            Pending(
                ownedFeatures,
                fireX,
                fireY,
                fireTime,
                bulletSpeed,
                directAngleDeg,
                orbitSign,
                maxEscapeDeg,
                halfBotWidthGf,
                profileGuessFactors,
            )
    }

    fun update(
        now: Long,
        enemyX: Double,
        enemyY: Double,
    ) {
        val iter = pending.iterator()
        while (iter.hasNext()) {
            val w = iter.next()
            val d = hypot(enemyX - w.fireX, enemyY - w.fireY)
            if (w.bulletSpeed * (now - w.fireTime) < d) continue
            val actual = Angles.absoluteBearing(w.fireX, w.fireY, enemyX, enemyY)
            val gf = (Angles.normalizeRelative(actual - w.directAngleDeg) / w.maxEscapeDeg * w.orbitSign).coerceIn(-1.0, 1.0)
            updateProfileScores(w.profileGuessFactors, gf, w.halfBotWidthGf)
            obs += Obs(w.features, gf)
            iter.remove()
        }
        // Bound memory: drop the oldest once we exceed the cap.
        if (obs.size > CAP) obs.subList(0, obs.size - CAP).clear()
    }

    /** Number of resolved observations available for the KNN query. */
    fun size(): Int = obs.size

    /** Candidate firing angle now: the K nearest past observations' guess-factor
     * mass, peaked over the bot-width window. Head-on if no data yet. */
    fun aim(
        features: DoubleArray,
        directAngleDeg: Double,
        orbitSign: Int,
        maxEscapeDeg: Double,
        halfBotWidthGf: Double,
    ): Double {
        val gf = predictGf(features, PROFILES[selectProfileIndex()].weights, halfBotWidthGf)
        return directAngleDeg + gf * maxEscapeDeg * orbitSign
    }

    private fun predictAllProfileGfs(
        features: DoubleArray,
        halfBotWidthGf: Double,
    ): DoubleArray {
        val out = DoubleArray(PROFILES.size)
        for (i in PROFILES.indices) {
            out[i] = predictGf(features, PROFILES[i].weights, halfBotWidthGf)
        }
        return out
    }

    private fun predictGf(
        features: DoubleArray,
        weights: DoubleArray,
        halfBotWidthGf: Double,
    ): Double {
        val n = minOf(obs.size, scoreBuf.size)
        if (n == 0) return 0.0
        for (i in 0 until n) {
            val o = obs[i]
            scoreBuf[i].d2 = distance2(o.features, features, weights)
            scoreBuf[i].gf = o.gf
        }
        val kk = minOf(k, n)
        // Partial-select the kk smallest distances into the first kk slots — the
        // same K nearest a full sort yields, except at exact distance ties (which
        // continuous features make measure-zero, so immaterial in practice; the
        // histogram sum is order-independent within the K). O(n) average vs the
        // full sort's O(n log n) — this is the KNN's real compute cost.
        if (kk < n) selectKth(0, n - 1, kk - 1)
        return peakGf(kk, halfBotWidthGf)
    }

    private fun updateProfileScores(
        predictedGfs: DoubleArray,
        actualGf: Double,
        halfBotWidthGf: Double,
    ) {
        if (predictedGfs.size != PROFILES.size) return
        val width = halfBotWidthGf.coerceAtLeast(PROFILE_WIDTH_FLOOR)
        for (i in predictedGfs.indices) {
            val normalizedMiss = abs(predictedGfs[i] - actualGf) / width
            val score = 1.0 / (1.0 + normalizedMiss * normalizedMiss)
            profileScore[i] = profileScore[i] * PROFILE_RETAIN + score
        }
        profileAttempts = profileAttempts * PROFILE_RETAIN + 1.0
    }

    private fun selectProfileIndex(): Int {
        if (obs.size < PROFILE_MIN_OBSERVATIONS || profileAttempts < PROFILE_MIN_ATTEMPTS) {
            activeProfileIndex = DEFAULT_PROFILE_INDEX
            return activeProfileIndex
        }

        var bestIndex = activeProfileIndex
        var bestRate = profileRate(activeProfileIndex)
        for (i in PROFILES.indices) {
            val rate = profileRate(i)
            if (rate > bestRate) {
                bestIndex = i
                bestRate = rate
            }
        }

        val activeRate = profileRate(activeProfileIndex)
        if (bestIndex != activeProfileIndex && bestRate > activeRate + PROFILE_SWITCH_MARGIN) {
            activeProfileIndex = bestIndex
        }
        return activeProfileIndex
    }

    private fun profileRate(index: Int): Double =
        (profileScore[index] + PROFILE_PRIOR_WEIGHT * PROFILE_PRIOR_SCORE) / (profileAttempts + PROFILE_PRIOR_WEIGHT)

    /** Quickselect with three-way (Dutch-national-flag) partitioning and a
     * median-of-three pivot value: rearrange `lo..hi` so slots `lo..k` hold the
     * smallest d2 values. Three-way partitioning isolates the equal-to-pivot cluster
     * in one pass and returns immediately once `k` lands inside it, so a cluster of
     * equal distances (or the all-equal worst case) stays O(n) — a single-pivot
     * Lomuto would degrade toward O(n^2) there. */
    private fun selectKth(
        lo: Int,
        hi: Int,
        k: Int,
    ) {
        var l = lo
        var h = hi
        while (l < h) {
            val m = (l + h) ushr 1
            val a = scoreBuf[l].d2
            val b = scoreBuf[m].d2
            val c = scoreBuf[h].d2
            // Median-of-three pivot VALUE (guards against sorted input).
            val pivot =
                if (a <= b) {
                    if (b <= c) {
                        b
                    } else if (a <= c) {
                        c
                    } else {
                        a
                    }
                } else {
                    if (a <= c) {
                        a
                    } else if (b <= c) {
                        c
                    } else {
                        b
                    }
                }
            var lt = l
            var gt = h
            var i = l
            while (i <= gt) {
                val d = scoreBuf[i].d2
                when {
                    d < pivot -> {
                        swap(lt, i)
                        lt++
                        i++
                    }
                    d > pivot -> {
                        swap(gt, i)
                        gt--
                    }
                    else -> i++
                }
            }
            when {
                k < lt -> h = lt - 1
                k > gt -> l = gt + 1
                else -> return
            }
        }
    }

    private fun swap(
        a: Int,
        b: Int,
    ) {
        val t = scoreBuf[a]
        scoreBuf[a] = scoreBuf[b]
        scoreBuf[b] = t
    }

    private fun distance2(
        a: DoubleArray,
        b: DoubleArray,
        weights: DoubleArray,
    ): Double {
        var s = 0.0
        for (i in a.indices) {
            val d = (a[i] - b[i]) * weights[i]
            s += d * d
        }
        return s
    }

    /** Build a kernel-smoothed histogram from the [kk] nearest (dist², gf) pairs
     * and return the guess factor whose bot-width window holds the most mass. */
    private fun peakGf(
        kk: Int,
        halfBotWidthGf: Double,
    ): Double {
        histBuf.fill(0.0)
        for (j in 0 until kk) {
            val s = scoreBuf[j]
            val w = 1.0 / (s.d2 + KERNEL_SMOOTH)
            val center = gfToBin(s.gf, mid)
            for (i in histBuf.indices) {
                val dd = (center - i).toDouble()
                histBuf[i] += w / (dd * dd + 1.0)
            }
        }
        val halfWindow = (halfBotWidthGf * mid).roundToInt().coerceIn(0, mid)
        var best = mid
        var bestMass = windowMass(histBuf, mid, halfWindow)
        for (i in histBuf.indices) {
            val mass = windowMass(histBuf, i, halfWindow)
            if (mass > bestMass) {
                bestMass = mass
                best = i
            }
        }
        return (best - mid).toDouble() / mid
    }

    private fun windowMass(
        hist: DoubleArray,
        center: Int,
        halfWindow: Int,
    ): Double {
        var sum = 0.0
        for (i in (center - halfWindow).coerceAtLeast(0)..(center + halfWindow).coerceAtMost(hist.size - 1)) {
            sum += hist[i]
        }
        return sum
    }

    companion object {
        const val CAP = 4000
        const val KERNEL_SMOOTH = 0.08
        private const val PROFILE_RETAIN = 0.985
        private const val PROFILE_PRIOR_SCORE = 0.25
        private const val PROFILE_PRIOR_WEIGHT = 25.0
        private const val PROFILE_MIN_ATTEMPTS = 12.0
        private const val PROFILE_MIN_OBSERVATIONS = 45
        private const val PROFILE_SWITCH_MARGIN = 0.015
        private const val PROFILE_WIDTH_FLOOR = 0.03
        private const val DEFAULT_PROFILE_INDEX = 0

        private class WeightProfile(
            val name: String,
            val weights: DoubleArray,
        )

        // Pretrained DC-gun distance weights kept here so Ronin remains a
        // self-contained robot jar.
        private val PROFILES =
            arrayOf(
                WeightProfile(
                    "classic-500-legacy",
                    doubleArrayOf(0.791408, 0.603753, 0.456976, 0.369378, 0.308844, 0.455456),
                ),
                WeightProfile(
                    "classic-500-first-pass",
                    doubleArrayOf(2.95425, 0.403151, 0.23571, 0.283015, 0.460098, 0.368233),
                ),
                WeightProfile(
                    "gigarumble-combined",
                    doubleArrayOf(4.0, 0.68044, 0.266567, 0.575477, 0.460098, 0.497114),
                ),
            )

        private val perEnemy = HashMap<String, DcGun>()

        fun forEnemy(name: String): DcGun = perEnemy.getOrPut(name) { DcGun() }
    }
}
