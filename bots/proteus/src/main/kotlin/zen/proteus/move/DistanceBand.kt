package zen.proteus.move

import kotlin.math.PI

/** Preferred engagement distance: how the orbit heading bends toward or away
 *  from the enemy when we drift out of the band. Shared by the orbit fallback
 *  and the surfer so transitions between them are seamless. */
internal object DistanceBand {
    const val PREFERRED_MIN = 200.0
    const val PREFERRED_MAX = 400.0
    val APPROACH_RADIANS = PI / 5.0
    val RETREAT_RADIANS = PI / 4.0
    const val SURF_BIAS_SCALE = 0.5

    /**
     * Heading bias added to the perpendicular. With headings measured relative
     * to the bearing toward the enemy, a negative bias pulls inward (toward the
     * enemy) and a positive bias pushes outward.
     */
    fun biasRadians(distance: Double): Double =
        when {
            distance > PREFERRED_MAX -> -APPROACH_RADIANS
            distance < PREFERRED_MIN -> RETREAT_RADIANS
            else -> 0.0
        }

    /** Damped bias used while surfing: holds range without twisting escape geometry. */
    fun dampedBiasRadians(distance: Double): Double = SURF_BIAS_SCALE * biasRadians(distance)
}
