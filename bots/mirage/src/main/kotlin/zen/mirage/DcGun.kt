package zen.mirage

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
 * spreads its data too thin to resolve. This is Mirage's main tool for hitting
 * wave-surfers with stable visit distributions.
 *
 * Several pretrained feature-weight profiles are embedded; each resolved wave
 * scores every profile's prediction error, and the gun fires with the leading
 * profile once enough feedback is in (default profile until then). Instances live
 * in a per-enemy static registry ([forEnemy]) so the observation set keeps growing
 * across a battle's rounds. Angles in radians; guess factors unit-agnostic.
 */
class DcGun(
    private val k: Int = dcKOverride(),
    private val bins: Int = 31,
) {
    private data class Pending(
        val features: DoubleArray,
        val fireX: Double,
        val fireY: Double,
        val fireTime: Long,
        val bulletSpeed: Double,
        val directAngleRadians: Double,
        val orbitSign: Int,
        val maxEscapeRadians: Double,
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

    private class Score(
        var d2: Double,
        var gf: Double,
        var recency: Double = 1.0,
    )

    private val scoreBuf = Array(CAP) { Score(0.0, 0.0) }
    private val histBuf = DoubleArray(bins)

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
            timeSinceDirectionChange.coerceIn(0L, 20L) / 20.0,
        )

    fun onFire(
        features: DoubleArray,
        fireX: Double,
        fireY: Double,
        fireTime: Long,
        bulletSpeed: Double,
        directAngleRadians: Double,
        orbitSign: Int,
        maxEscapeRadians: Double,
    ) {
        val distance = (features[0] * 1200.0).coerceAtLeast(Kinematics.HALF_BOT)
        val halfBotWidthGf = atan(Kinematics.HALF_BOT / distance) / maxEscapeRadians
        val profileGuessFactors = predictAllProfileGfs(features, halfBotWidthGf)
        pending +=
            Pending(
                features,
                fireX,
                fireY,
                fireTime,
                bulletSpeed,
                directAngleRadians,
                orbitSign,
                maxEscapeRadians,
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
            val gf = (Angles.normalizeRelative(actual - w.directAngleRadians) / w.maxEscapeRadians * w.orbitSign).coerceIn(-1.0, 1.0)
            updateProfileScores(w.profileGuessFactors, gf, w.halfBotWidthGf)
            obs += Obs(w.features, gf)
            iter.remove()
        }
        if (obs.size > CAP) obs.subList(0, obs.size - CAP).clear()
    }

    fun size(): Int = obs.size

    /** Active profile's rolling prediction accuracy (0..1, mirage.debug): how
     *  closely recent resolved waves' predicted GF matched the actual GF. */
    fun activeProfileRate(): Double = profileRate(selectProfileIndex())

    fun aimRadians(
        features: DoubleArray,
        directAngleRadians: Double,
        orbitSign: Int,
        maxEscapeRadians: Double,
        halfBotWidthGf: Double,
    ): Double {
        val gf = predictGf(features, PROFILES[selectProfileIndex()].weights, halfBotWidthGf)
        return directAngleRadians + gf * maxEscapeRadians * orbitSign
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
        // Recency weight: newer observations (higher index) better describe a
        // learning opponent's *current* habits, so weight them up in the density
        // estimate. Half-life is in observations; <= 0 disables (uniform weight),
        // which is the A/B baseline. Carried on the Score so it survives the
        // quickselect reordering. (BeepBoop-style time-decay over a KNN buffer.)
        val halfLife = recencyHalfLife()
        val decayPerObs = if (halfLife > 0.0) StrictMath.pow(0.5, 1.0 / halfLife) else 1.0
        for (i in 0 until n) {
            val o = obs[i]
            scoreBuf[i].d2 = distance2(o.features, features, weights)
            scoreBuf[i].gf = o.gf
            scoreBuf[i].recency = if (decayPerObs >= 1.0) 1.0 else StrictMath.pow(decayPerObs, (n - 1 - i).toDouble())
        }
        val kk = minOf(k, n)
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
        val activeRate = profileRate(activeProfileIndex)
        var bestIndex = activeProfileIndex
        var bestRate = activeRate
        for (i in PROFILES.indices) {
            val rate = profileRate(i)
            if (rate > bestRate) {
                bestIndex = i
                bestRate = rate
            }
        }
        if (bestIndex != activeProfileIndex && bestRate > activeRate + PROFILE_SWITCH_MARGIN) {
            activeProfileIndex = bestIndex
        }
        return activeProfileIndex
    }

    private fun profileRate(index: Int): Double =
        (profileScore[index] + PROFILE_PRIOR_WEIGHT * PROFILE_PRIOR_SCORE) / (profileAttempts + PROFILE_PRIOR_WEIGHT)

    /** Quickselect with three-way (Dutch-national-flag) partitioning and a
     *  median-of-three pivot value: rearrange `lo..hi` so slots `lo..k` hold the
     *  smallest d2 values. Stays O(n) on clusters of equal distances. */
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

    private fun peakGf(
        kk: Int,
        halfBotWidthGf: Double,
    ): Double {
        val kernel = kernelSmooth()
        histBuf.fill(0.0)
        for (j in 0 until kk) {
            val s = scoreBuf[j]
            val w = s.recency / (s.d2 + kernel)
            val center = gfToBin(s.gf, mid)
            for (i in histBuf.indices) {
                val dd = (center - i).toDouble()
                histBuf[i] += w / (dd * dd + 1.0)
            }
        }
        val halfWindow = (halfBotWidthGf * mid).roundToInt().coerceIn(0, mid)
        val best = peakGfBin(histBuf, mid, halfWindow)
        return (best - mid).toDouble() / mid
    }

    companion object {
        const val CAP = 4000
        const val KERNEL_SMOOTH = 0.08

        /** Kernel smoothing (A/B override mirage.dckernel). Lower = sharper peak
         *  (trust the nearest neighbor more), higher = smoother blend. */
        private fun kernelSmooth(): Double = System.getProperty("mirage.dckernel")?.toDoubleOrNull()?.coerceIn(0.005, 1.0) ?: KERNEL_SMOOTH

        /** KNN neighbor count. The mirage.dck override (A/B tuning) probes whether
         *  a wider neighbor blend predicts adaptive movers more robustly; default
         *  keeps the tuned value. */
        private fun dcKOverride(): Int = System.getProperty("mirage.dck")?.toIntOrNull()?.coerceIn(1, 200) ?: 25

        /** KNN recency half-life in observations: how many shots back a neighbor's
         *  density weight halves. The mirage.dchalflife override (A/B tuning) probes
         *  whether time-decaying the buffer hits adaptive movers better; default
         *  keeps the tuned value. <= 0 disables recency weighting (uniform). */
        private fun recencyHalfLife(): Double = System.getProperty("mirage.dchalflife")?.toDoubleOrNull() ?: DEFAULT_HALF_LIFE

        /** Default recency half-life, in observations. Validated (12-sample A/B) to
         *  raise damage dealt and APS across Firestarter/Phoenix/Gilgalad (DEALT
         *  +1.8..3.8, survival up to +4.8) while staying neutral on SandboxDT.
         *  0 disables (the pre-recency uniform baseline). */
        private const val DEFAULT_HALF_LIFE = 400.0

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

        // Pretrained DC-gun distance weights kept here so Mirage keeps a
        // self-contained robot jar.
        private val PROFILES =
            arrayOf(
                WeightProfile("classic-500-legacy", doubleArrayOf(0.791408, 0.603753, 0.456976, 0.369378, 0.308844, 0.455456)),
                WeightProfile("classic-500-first-pass", doubleArrayOf(2.95425, 0.403151, 0.23571, 0.283015, 0.460098, 0.368233)),
                WeightProfile("gigarumble-combined", doubleArrayOf(4.0, 0.68044, 0.266567, 0.575477, 0.460098, 0.497114)),
            )

        private val perEnemy = HashMap<String, DcGun>()

        fun forEnemy(name: String): DcGun = perEnemy.getOrPut(name) { DcGun() }
    }
}
