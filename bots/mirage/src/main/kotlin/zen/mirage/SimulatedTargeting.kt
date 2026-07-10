package zen.mirage

import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

/**
 * Simulated-targeting danger prior (BeepBoop's SimpleDangerModels, ported).
 *
 * Our surf-danger [DangerModel] is purely empirical: it learns where the enemy's
 * bullets arrive from observed visits and hits. That leaves it blind on wave 1
 * (and slow to characterize an unknown gun) — it assumes roughly uniform danger,
 * so it dodges the first wave poorly. A simple gun, though, fires at a
 * *predictable* GF: head-on aims at GF 0, linear aims at the constant-velocity
 * lead GF, circular at the turning lead GF. This object computes those geometric
 * GFs from the wave's fire-time state and lays danger there, giving the surfer an
 * intelligent prior before any learning. As the empirical ensemble accumulates
 * real data it dominates, but the prior is what keeps the first waves survivable
 * against a strong gun that fires accurately from the start.
 *
 * All angles in radians (0 = north, clockwise). Returns a normalized 47-bin
 * danger distribution over guess factors.
 */
object SimulatedTargeting {
    enum class Expert {
        HEAD_ON,
        LINEAR,
        CIRCULAR,
        WALL_LINEAR,
    }

    data class Predictions(
        val guessFactors: DoubleArray,
    ) {
        init {
            require(guessFactors.size == Expert.values().size)
        }

        operator fun get(expert: Expert): Double = guessFactors[expert.ordinal]
    }

    private const val BINS = GuessFactorDanger.BINS
    private const val MID = BINS / 2

    /** Exponential-kernel decay (per GF unit) for spreading a peak across bins. */
    private const val KERNEL_LAMBDA = 20.0

    /** Uniform danger floor so a single peak never reads as the only threat. */
    private const val FLOOR = 0.04

    /**
     * Normalized GF-bin danger from where simple targeting methods would fire at us
     * for a wave fired from ([sourceX], [sourceY]) at our fire-time state
     * ([usX], [usY], [usHeading], [usVelocity]). [directAngle] is the source→us bearing
     * (GF 0), [orbitDirection] signs the GF, [maxEscapeRadians] scales it, and
     * [bulletSpeed] sizes the linear/circular lead.
     */
    fun dangerGf(
        sourceX: Double,
        sourceY: Double,
        usX: Double,
        usY: Double,
        usHeading: Double,
        usVelocity: Double,
        directAngle: Double,
        orbitDirection: Int,
        maxEscapeRadians: Double,
        bulletSpeed: Double,
        fieldWidth: Double,
        fieldHeight: Double,
    ): DoubleArray {
        val out = DoubleArray(BINS)
        // Head-on: the enemy fires straight at GF 0.
        addPeak(out, 0.0, 1.0)
        // Linear: constant-velocity lead.
        val linearAngle =
            leadAngle(sourceX, sourceY, usX, usY, usHeading, usVelocity, bulletSpeed, stopAtWall = false, fieldWidth, fieldHeight)
        addPeak(out, gfOf(linearAngle, directAngle, orbitDirection, maxEscapeRadians), 1.0)
        // Linear with wall stop (many bots clamp at the wall).
        val linearWallAngle =
            leadAngle(sourceX, sourceY, usX, usY, usHeading, usVelocity, bulletSpeed, stopAtWall = true, fieldWidth, fieldHeight)
        addPeak(out, gfOf(linearWallAngle, directAngle, orbitDirection, maxEscapeRadians), 0.7)
        for (i in 0 until BINS) out[i] += FLOOR
        var sum = 0.0
        for (v in out) sum += v
        if (sum > 0.0) for (i in 0 until BINS) out[i] /= sum
        return out
    }

    /** Per-expert firing guess factors for ShotDodger-lite. Unlike [dangerGf],
     *  this keeps each model separate so its realized error can be learned per
     *  opponent instead of permanently blending all simple-gun priors. */
    @Suppress("kotlin:S107")
    fun predictions(
        sourceX: Double,
        sourceY: Double,
        usX: Double,
        usY: Double,
        usHeadingRadians: Double,
        usVelocity: Double,
        usTurnRateRadians: Double,
        directAngleRadians: Double,
        orbitDirection: Int,
        maxEscapeRadians: Double,
        bulletSpeed: Double,
        fieldWidth: Double,
        fieldHeight: Double,
    ): Predictions {
        val linearAngleRadians =
            leadAngle(
                sourceX,
                sourceY,
                usX,
                usY,
                usHeadingRadians,
                usVelocity,
                bulletSpeed,
                stopAtWall = false,
                fieldWidth,
                fieldHeight,
            )
        val circularAngleRadians =
            circularLeadAngle(
                sourceX,
                sourceY,
                usX,
                usY,
                usHeadingRadians,
                usVelocity,
                usTurnRateRadians,
                bulletSpeed,
                fieldWidth,
                fieldHeight,
            )
        val wallLinearAngleRadians =
            leadAngle(
                sourceX,
                sourceY,
                usX,
                usY,
                usHeadingRadians,
                usVelocity,
                bulletSpeed,
                stopAtWall = true,
                fieldWidth,
                fieldHeight,
            )
        return Predictions(
            doubleArrayOf(
                0.0,
                gfOf(linearAngleRadians, directAngleRadians, orbitDirection, maxEscapeRadians),
                gfOf(circularAngleRadians, directAngleRadians, orbitDirection, maxEscapeRadians),
                gfOf(wallLinearAngleRadians, directAngleRadians, orbitDirection, maxEscapeRadians),
            ),
        )
    }

    /** Add an exponential-kernel peak of [weight] centered at guess factor [gf]. */
    private fun addPeak(
        out: DoubleArray,
        gf: Double,
        weight: Double,
    ) {
        val clamped = gf.coerceIn(-1.0, 1.0)
        for (i in 0 until BINS) {
            val center = (i - MID).toDouble() / MID
            out[i] += weight * exp(-KERNEL_LAMBDA * kotlin.math.abs(center - clamped))
        }
    }

    /** Guess factor of an absolute aim [angle] in [-1,1], signed by [orbitDirection]. */
    private fun gfOf(
        angle: Double,
        directAngle: Double,
        orbitDirection: Int,
        maxEscapeRadians: Double,
    ): Double = (Angles.normalizeRelative(angle - directAngle) / maxEscapeRadians * orbitDirection).coerceIn(-1.0, 1.0)

    /** Iterative linear lead: project our motion at constant velocity until the
     *  bullet would catch us, return the firing bearing. [stopAtWall] clamps the
     *  projection to the field (a wall-clamping gun). */
    private fun leadAngle(
        sourceX: Double,
        sourceY: Double,
        usX: Double,
        usY: Double,
        usHeading: Double,
        usVelocity: Double,
        bulletSpeed: Double,
        stopAtWall: Boolean,
        fieldWidth: Double,
        fieldHeight: Double,
    ): Double {
        var px = usX
        var py = usY
        var ticks = 0
        val maxTicks = 200
        while (ticks < maxTicks) {
            val dist = kotlin.math.hypot(px - sourceX, py - sourceY)
            if (ticks * bulletSpeed >= dist) break
            px += sin(usHeading) * usVelocity
            py += cos(usHeading) * usVelocity
            if (stopAtWall) {
                px = px.coerceIn(Kinematics.HALF_BOT, fieldWidth - Kinematics.HALF_BOT)
                py = py.coerceIn(Kinematics.HALF_BOT, fieldHeight - Kinematics.HALF_BOT)
            }
            ticks++
        }
        return Angles.absoluteBearing(sourceX, sourceY, px, py)
    }

    /** Iterative constant-turn-rate lead. The projected target stops at field
     *  bounds rather than walking outside the legal battlefield. */
    @Suppress("kotlin:S107")
    private fun circularLeadAngle(
        sourceX: Double,
        sourceY: Double,
        usX: Double,
        usY: Double,
        usHeadingRadians: Double,
        usVelocity: Double,
        usTurnRateRadians: Double,
        bulletSpeed: Double,
        fieldWidth: Double,
        fieldHeight: Double,
    ): Double {
        var px = usX
        var py = usY
        var headingRadians = usHeadingRadians
        var ticks = 0
        while (ticks < MAX_LEAD_TICKS) {
            val distance = kotlin.math.hypot(px - sourceX, py - sourceY)
            if (ticks * bulletSpeed >= distance) break
            headingRadians = Angles.normalizeAbsolute(headingRadians + usTurnRateRadians)
            px =
                (px + sin(headingRadians) * usVelocity)
                    .coerceIn(Kinematics.HALF_BOT, fieldWidth - Kinematics.HALF_BOT)
            py =
                (py + cos(headingRadians) * usVelocity)
                    .coerceIn(Kinematics.HALF_BOT, fieldHeight - Kinematics.HALF_BOT)
            ticks++
        }
        return Angles.absoluteBearing(sourceX, sourceY, px, py)
    }

    private const val MAX_LEAD_TICKS = 200
}
