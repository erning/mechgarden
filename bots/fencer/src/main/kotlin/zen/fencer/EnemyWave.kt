package zen.fencer

import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * An **inferred** enemy bullet wave: we
 * never see enemy bullets directly, so when [FireDetector] infers a shot we model
 * it as a circle expanding from the enemy's fire position at the bullet's speed.
 *
 * Facts captured at fire time: where it came from, when, the inferred power /
 * speed, the direct bearing source→us ([directAngleDeg], the GF zero), and the
 * sense we were orbiting ([orbitDirection], the GF sign). [guessFactor] maps a
 * point to where it sits in the wave's escape envelope — the basis for surfing.
 */
class EnemyWave(
    val sourceX: Double,
    val sourceY: Double,
    val fireTime: Long,
    val power: Double,
    val velocity: Double,
    val directAngleDeg: Double,
    val orbitDirection: Int,
    /** Our fire-time situation (the [DangerBuffers] segmentation axes). */
    val features: WaveFeatures,
    /** Fused per-bin danger shares baked at fire time ([DangerBuffers.bake]):
     * reads are window means over this array, so they don't touch the live
     * profiles (waves fly 10–40 ticks; the fire-time snapshot stays fresh and
     * the read cost stays flat however many buffers feed the bake). */
    val dangerBins: DoubleArray,
) {
    /** Ideal max escape angle for this wave's bullet speed, degrees. */
    val maxEscapeDeg: Double = Math.toDegrees(asin(Kinematics.MAX_VELOCITY / velocity))

    /** GF interval our hull covered while the front crossed it (NaN until the
     * crossing starts) — the precise-passage visit observation. */
    var coveredLowGf: Double = Double.NaN
        private set
    var coveredHighGf: Double = Double.NaN
        private set

    /** Per-bin count of active bullet shadows (see [BulletShadows]); a bin
     * covered by at least one live shadow reads as zero danger. Counted, not
     * flagged, so overlapping shadows from different bullets retract cleanly. */
    private val shadowCount = IntArray(dangerBins.size)

    fun addShadow(
        lowGf: Double,
        highGf: Double,
    ) {
        val lo = GuessFactorDanger.binIndex(minOf(lowGf, highGf))
        val hi = GuessFactorDanger.binIndex(maxOf(lowGf, highGf))
        for (i in lo..hi) shadowCount[i]++
    }

    fun removeShadow(
        lowGf: Double,
        highGf: Double,
    ) {
        val lo = GuessFactorDanger.binIndex(minOf(lowGf, highGf))
        val hi = GuessFactorDanger.binIndex(maxOf(lowGf, highGf))
        for (i in lo..hi) if (shadowCount[i] > 0) shadowCount[i]--
    }

    /** Shadow-aware hull-window danger read: the mean of [dangerBins] over the
     * GF window, with actively shadowed bins contributing zero (an enemy bullet
     * there would be destroyed by one of ours before reaching us). */
    fun dangerWindow(
        lowGf: Double,
        highGf: Double,
    ): Double {
        val lo = GuessFactorDanger.binIndex(minOf(lowGf, highGf))
        val hi = GuessFactorDanger.binIndex(maxOf(lowGf, highGf))
        var sum = 0.0
        for (i in lo..hi) if (shadowCount[i] == 0) sum += dangerBins[i]
        return sum / (hi - lo + 1)
    }

    /** Radius of the wave front at [now]. */
    fun radius(now: Long): Double = velocity * (now - fireTime)

    /** Has the front already reached/passed the point ([px], [py])? */
    fun hasPassed(
        now: Long,
        px: Double,
        py: Double,
    ): Boolean = radius(now) >= hypot(px - sourceX, py - sourceY)

    /** Half the GF width our hull subtends at [distance] from the source. */
    fun hullHalfGf(distance: Double): Double = Math.toDegrees(atan(HALF_BOT / distance)) / maxEscapeDeg

    /** Widen the covered interval with our hull's GF footprint at ([px], [py])
     * this tick — called while the front is crossing the hull. */
    fun cover(
        px: Double,
        py: Double,
    ) {
        val gf = guessFactor(px, py)
        val half = hullHalfGf(hypot(px - sourceX, py - sourceY))
        val lo = (gf - half).coerceIn(-1.0, 1.0)
        val hi = (gf + half).coerceIn(-1.0, 1.0)
        coveredLowGf = if (coveredLowGf.isNaN()) lo else min(coveredLowGf, lo)
        coveredHighGf = if (coveredHighGf.isNaN()) hi else max(coveredHighGf, hi)
    }

    /**
     * Guess factor of point ([px], [py]) on this wave, in [-1, 1]: the bearing
     * offset from [directAngleDeg], normalized by the max escape angle and signed
     * by [orbitDirection] (so +1 = full escape in the direction we were moving).
     */
    fun guessFactor(
        px: Double,
        py: Double,
    ): Double {
        val bearing = Angles.absoluteBearing(sourceX, sourceY, px, py)
        val offset = Angles.normalizeRelative(bearing - directAngleDeg)
        return (offset / maxEscapeDeg * orbitDirection).coerceIn(-1.0, 1.0)
    }

    private companion object {
        /** Half a robot's bounding box, px. */
        const val HALF_BOT = 18.0
    }
}
