package zen.mirage

import kotlin.math.atan
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * The situation **we** (the dodger) were in when an enemy wave was fired — the
 * feature vector the surf-danger segmenters slice. Sampled once per wave from the
 * fire-time snapshot; raw and unbucketed (each buffer buckets the axes it wants).
 */
class WaveFeatures(
    val distance: Double,
    val lateralAbs: Double,
    val accelSign: Int,
    val wallForwardRatio: Double,
) {
    companion object {
        @Suppress("kotlin:S107")
        fun at(
            us: RobotState,
            sourceX: Double,
            sourceY: Double,
            bulletSpeed: Double,
            prevSpeedAbs: Double,
            fieldWidth: Double,
            fieldHeight: Double,
        ): WaveFeatures {
            val sourceToUs = Angles.absoluteBearing(sourceX, sourceY, us.x, us.y)
            val lateral = us.velocity * sin(us.headingRadians - sourceToUs)
            val distance = hypot(us.x - sourceX, us.y - sourceY)
            val accelSign = Segments.accelSign(Math.abs(us.velocity) - prevSpeedAbs)
            val travelHeading =
                if (us.velocity >= 0.0) us.headingRadians else Angles.normalizeAbsolute(us.headingRadians + Angles.PI)
            val wallSpace = distanceToWall(us.x, us.y, travelHeading, fieldWidth, fieldHeight)
            val flightTicks = (distance / bulletSpeed).coerceAtLeast(1.0)
            val wallTicks = wallSpace / Kinematics.MAX_VELOCITY
            return WaveFeatures(distance, Math.abs(lateral), accelSign, wallTicks / flightTicks)
        }
    }
}

/**
 * An inferred enemy bullet wave: a circle expanding from the enemy's fire position
 * at the bullet's speed. Facts captured at fire time: source, time, power/speed,
 * the direct bearing source→us ([directAngleRadians], the GF zero), the orbit
 * sense ([orbitDirection], the GF sign), and the fire-time [features].
 * [dangerBins] is the fused per-bin danger baked once at fire time.
 *
 * All angles in radians; guess factors are unit-agnostic (offset/maxEscape).
 */
class EnemyWave(
    val sourceX: Double,
    val sourceY: Double,
    val fireTime: Long,
    val power: Double,
    val velocity: Double,
    val directAngleRadians: Double,
    val orbitDirection: Int,
    val features: WaveFeatures,
    val dangerBins: DoubleArray,
    val maxEscapeRadians: Double = StrictMath.asin(Kinematics.MAX_VELOCITY / velocity),
    /** Directional precise MEA: escape half-angle for the GF+ side and the GF- side
     *  separately. Walls make them asymmetric, and using each side's own bound
     *  keeps the GF scale honest (a symmetric max smears danger across bins the
     *  wall makes unreachable, and over-credits one direction). When unset both
     *  fall back to [maxEscapeRadians]. */
    val maxEscapePositive: Double = maxEscapeRadians,
    val maxEscapeNegative: Double = maxEscapeRadians,
    /** Enemy gun line predicted for active shielding. Many classic robots fire
     *  using their prior-tick scan, so this may differ from the fire-time GF
     *  zero in [directAngleRadians]. */
    val shieldHeadingRadians: Double = directAngleRadians,
    /** Separate simple-gun predictions captured at fire time for ShotDodger-lite.
     *  Null for waves that should not train the selector. */
    val simpleTargetPredictions: SimulatedTargeting.Predictions? = null,
) {
    /** GF interval our hull covered while the front crossed it (NaN until crossing). */
    var coveredLowGf: Double = Double.NaN
        private set
    var coveredHighGf: Double = Double.NaN
        private set

    /** Per-bin count of active bullet shadows (see [BulletShadows]); a bin covered
     *  by at least one live shadow reads as zero danger. */
    private val shadowCount = IntArray(dangerBins.size)

    fun addShadow(
        lowGf: Double,
        highGf: Double,
    ) {
        val lo = GuessFactorDanger.binIndex(min(lowGf, highGf))
        val hi = GuessFactorDanger.binIndex(max(lowGf, highGf))
        for (i in lo..hi) shadowCount[i]++
    }

    fun removeShadow(
        lowGf: Double,
        highGf: Double,
    ) {
        val lo = GuessFactorDanger.binIndex(min(lowGf, highGf))
        val hi = GuessFactorDanger.binIndex(max(lowGf, highGf))
        for (i in lo..hi) if (shadowCount[i] > 0) shadowCount[i]--
    }

    fun radius(now: Long): Double = velocity * (now - fireTime)

    fun hasPassed(
        now: Long,
        px: Double,
        py: Double,
    ): Boolean = radius(now) >= hypot(px - sourceX, py - sourceY)

    fun hullHalfGf(distance: Double): Double = atan(Kinematics.HALF_BOT / distance) / maxEscapeRadians

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

    /** Shadow-aware hull-window danger read: mean of [dangerBins] over the GF
     *  window, with actively shadowed bins contributing zero. */
    fun dangerWindow(
        lowGf: Double,
        highGf: Double,
    ): Double {
        val lo = GuessFactorDanger.binIndex(min(lowGf, highGf))
        val hi = GuessFactorDanger.binIndex(max(lowGf, highGf))
        var sum = 0.0
        for (i in lo..hi) if (shadowCount[i] == 0) sum += dangerBins[i]
        return sum / (hi - lo + 1)
    }

    /** Guess factor of ([px], [py]) in [-1,1], signed by [orbitDirection]. Uses the
     *  per-direction MEA so each side is scaled by what is actually reachable
     *  there (wall-aware), not a symmetric max. */
    fun guessFactor(
        px: Double,
        py: Double,
    ): Double {
        val bearing = Angles.absoluteBearing(sourceX, sourceY, px, py)
        val offset = Angles.normalizeRelative(bearing - directAngleRadians)
        val mea = if (offset * orbitDirection >= 0.0) maxEscapePositive else maxEscapeNegative
        return (offset / mea * orbitDirection).coerceIn(-1.0, 1.0)
    }
}

/**
 * Holds enemy waves in flight. [add] feeds new inferred shots; [active] is read by
 * the surfer; [sweep] tracks each wave's hull-crossing and hands back fully-passed
 * waves (to learn as visits); [matchBullet] pulls the wave a connecting bullet
 * belongs to.
 */
class EnemyWaveTracker {
    private val waves = mutableListOf<EnemyWave>()

    val active: List<EnemyWave> get() = waves

    fun add(wave: EnemyWave) {
        waves += wave
    }

    fun sweep(
        now: Long,
        px: Double,
        py: Double,
        onPassed: (EnemyWave) -> Unit,
    ) {
        val iter = waves.iterator()
        while (iter.hasNext()) {
            val w = iter.next()
            val d = hypot(px - w.sourceX, py - w.sourceY)
            val r = w.radius(now)
            if (r >= d - Kinematics.HALF_BOT) w.cover(px, py)
            if (r >= d + Kinematics.HALF_BOT) {
                onPassed(w)
                iter.remove()
            }
        }
    }

    fun matchBullet(
        now: Long,
        px: Double,
        py: Double,
        bulletVelocity: Double,
    ): EnemyWave? {
        var best: EnemyWave? = null
        var bestDiff = Double.MAX_VALUE
        for (w in waves) {
            if (Math.abs(w.velocity - bulletVelocity) >= SPEED_TOLERANCE) continue
            val diff = Math.abs(w.radius(now) - hypot(px - w.sourceX, py - w.sourceY))
            if (diff < bestDiff) {
                bestDiff = diff
                best = w
            }
        }
        if (best != null) waves.remove(best)
        return best
    }

    private companion object {
        const val SPEED_TOLERANCE = 0.5
    }
}
