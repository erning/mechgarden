package zen.mirage

import kotlin.math.atan
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Segmented guess-factor firing gun. Fired shots (and optional tick waves) learn
 * where the enemy reached inside the escape envelope for the caller-provided
 * segment; [aimRadians] points at the guess factor whose bot-width window covers
 * the most mass. All angles in radians; guess factors are unit-agnostic.
 */
class GfFireGun(
    private val segments: Int = 9,
    private val bins: Int = 31,
    private val retain: Double = 1.0,
) {
    private val table = Array(segments) { DoubleArray(bins) }
    private val mid = bins / 2
    private val learnWaves = mutableListOf<GfWave>()

    fun aimRadians(
        directAngleRadians: Double,
        orbitSign: Int,
        maxEscapeRadians: Double,
        segment: Int,
        halfBotWidthGf: Double = 0.0,
    ): Double = directAngleRadians + peak(segment, halfBotWidthGf) * maxEscapeRadians * orbitSign

    fun onFire(
        fireX: Double,
        fireY: Double,
        fireTime: Long,
        bulletSpeed: Double,
        directAngleRadians: Double,
        orbitSign: Int,
        maxEscapeRadians: Double,
        segment: Int,
        weight: Double = 1.0,
    ) {
        learnWaves += GfWave(fireX, fireY, fireTime, bulletSpeed, directAngleRadians, orbitSign, maxEscapeRadians, segment, weight)
    }

    fun update(
        now: Long,
        enemyX: Double,
        enemyY: Double,
    ) {
        val iter = learnWaves.iterator()
        while (iter.hasNext()) {
            val w = iter.next()
            val d = hypot(enemyX - w.fireX, enemyY - w.fireY)
            if (w.bulletSpeed * (now - w.fireTime) < d) continue
            val actual = Angles.absoluteBearing(w.fireX, w.fireY, enemyX, enemyY)
            val gf = (Angles.normalizeRelative(actual - w.directAngleRadians) / w.maxEscapeRadians * w.orbitSign).coerceIn(-1.0, 1.0)
            register(w.segment, gf, w.weight)
            iter.remove()
        }
    }

    private fun register(
        segment: Int,
        guessFactor: Double,
        weight: Double,
    ) {
        val histogram = table[segment]
        if (retain < 1.0) {
            for (i in histogram.indices) histogram[i] *= retain
        }
        val center = gfToBin(guessFactor, mid)
        for (i in histogram.indices) {
            val d = (center - i).toDouble()
            histogram[i] += weight / (d * d + 1.0)
        }
    }

    private fun peak(
        segment: Int,
        halfBotWidthGf: Double,
    ): Double {
        val histogram = table[segment]
        val halfWindow = (halfBotWidthGf * mid).roundToInt().coerceIn(0, mid)
        val best = peakGfBin(histogram, mid, halfWindow)
        return (best - mid).toDouble() / mid
    }

    private class GfWave(
        val fireX: Double,
        val fireY: Double,
        val fireTime: Long,
        val bulletSpeed: Double,
        val directAngleRadians: Double,
        val orbitSign: Int,
        val maxEscapeRadians: Double,
        val segment: Int,
        val weight: Double,
    )
}

/**
 * Virtual-gun selection. Several aim models each propose an angle every shot; we
 * fire one but record what each *would* have aimed. When a wave reaches the enemy
 * we score which models *would* have hit, and fire with the best — defaulting to
 * circular until another leads. Scores are recency-decayed.
 */
class VirtualGuns(
    private val antiSurferEvidence: AntiSurferEvidence? = null,
) {
    enum class Aim { HEAD_ON, LINEAR, CIRCULAR, GF_DISTLAT, GF_ROLL, GF_ACCEL, GF_WALL, GF_DC, GF_DC_AS }

    private val score = DoubleArray(AIM.size)
    private var attempts = 0.0
    private val ourWaves = mutableListOf<OurWave>()

    fun best(includeAntiSurfer: Boolean = true): Aim {
        var best = Aim.CIRCULAR
        var most = score[Aim.CIRCULAR.ordinal]
        for (aim in AIM) {
            if (!includeAntiSurfer && aim == Aim.GF_DC_AS) continue
            if (score[aim.ordinal] > most) {
                most = score[aim.ordinal]
                best = aim
            }
        }
        return best
    }

    fun hitRate(aim: Aim): Double = (score[aim.ordinal] + PRIOR_WEIGHT * PRIOR_RATE) / (attempts + PRIOR_WEIGHT)

    fun onFire(
        fireX: Double,
        fireY: Double,
        fireTime: Long,
        bulletSpeed: Double,
        anglesByAim: DoubleArray,
        asEvidenceEligible: Boolean = false,
    ) {
        ourWaves += OurWave(fireX, fireY, fireTime, bulletSpeed, anglesByAim, asEvidenceEligible)
    }

    fun update(
        now: Long,
        enemyX: Double,
        enemyY: Double,
    ) {
        val iter = ourWaves.iterator()
        while (iter.hasNext()) {
            val w = iter.next()
            val d = hypot(enemyX - w.fireX, enemyY - w.fireY)
            if (w.radius(now) < d) continue
            val actual = Angles.absoluteBearing(w.fireX, w.fireY, enemyX, enemyY)
            val tolerance = atan(Kinematics.HALF_BOT / d)
            var mainDcHit = false
            var antiSurferHit = false
            for (aim in AIM) {
                val hit = if (kotlin.math.abs(Angles.normalizeRelative(w.angles[aim.ordinal] - actual)) < tolerance) 1.0 else 0.0
                score[aim.ordinal] = score[aim.ordinal] * RETAIN + hit
                if (aim == Aim.GF_DC) mainDcHit = hit > 0.0
                if (aim == Aim.GF_DC_AS) antiSurferHit = hit > 0.0
            }
            attempts = attempts * RETAIN + 1.0
            if (w.asEvidenceEligible) antiSurferEvidence?.record(mainDcHit, antiSurferHit)
            iter.remove()
        }
    }

    private class OurWave(
        val fireX: Double,
        val fireY: Double,
        val fireTime: Long,
        val bulletSpeed: Double,
        val angles: DoubleArray,
        val asEvidenceEligible: Boolean,
    ) {
        fun radius(now: Long): Double = bulletSpeed * (now - fireTime)
    }

    private companion object {
        const val PRIOR_RATE = 0.4
        const val PRIOR_WEIGHT = 10.0
        const val RETAIN = 0.998

        private val AIM = Aim.values()
    }
}

/** Cross-round evidence used only to arbitrate the normal and hit-aware DC guns.
 *  Pending virtual waves remain round-local in [VirtualGuns]; this registry keeps
 *  only their resolved would-hit outcomes, so it cannot alter warmup aim or power. */
class AntiSurferEvidence {
    private var mainScore = 0.0
    private var antiSurferScore = 0.0
    private var attempts = 0.0
    private var antiSurferSelected = false

    fun record(
        mainHit: Boolean,
        antiSurferHit: Boolean,
    ) {
        mainScore = mainScore * RETAIN + if (mainHit) 1.0 else 0.0
        antiSurferScore = antiSurferScore * RETAIN + if (antiSurferHit) 1.0 else 0.0
        attempts = attempts * RETAIN + 1.0
    }

    fun mainHitRate(): Double = (mainScore + PRIOR_WEIGHT * PRIOR_RATE) / (attempts + PRIOR_WEIGHT)

    fun antiSurferHitRate(): Double = (antiSurferScore + PRIOR_WEIGHT * PRIOR_RATE) / (attempts + PRIOR_WEIGHT)

    fun resolvedAttempts(): Double = attempts

    /** Hysteretic, name-free selector. Enter only on a material lead with enough
     *  evidence; once selected, tolerate a small regression so adjacent virtual
     *  wave resolutions cannot make the turret alternate between DC models. */
    fun select(
        minAttempts: Double,
        maxMainHitRate: Double,
        maxMainExitHitRate: Double,
        enterLead: Double,
        exitLead: Double,
    ): Boolean {
        val mainRate = mainHitRate()
        val antiSurferRate = antiSurferHitRate()
        if (antiSurferSelected) {
            if (mainRate > maxMainExitHitRate || antiSurferRate < mainRate + exitLead) {
                antiSurferSelected = false
            }
        } else if (attempts >= minAttempts && mainRate <= maxMainHitRate && antiSurferRate >= mainRate + enterLead) {
            antiSurferSelected = true
        }
        return antiSurferSelected
    }

    fun selected(): Boolean = antiSurferSelected

    companion object {
        private const val PRIOR_RATE = 0.4
        private const val PRIOR_WEIGHT = 10.0
        private const val RETAIN = 0.998

        private val perEnemy = HashMap<String, AntiSurferEvidence>()

        fun forEnemy(name: String): AntiSurferEvidence = perEnemy.getOrPut(name) { AntiSurferEvidence() }
    }
}
