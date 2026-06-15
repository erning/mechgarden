package zen.fencer

/**
 * Distancing — the attack-angle bias on the orbit heading. A plain orbit drives
 * strictly perpendicular to the center, so the engagement range drifts wherever
 * the enemy pushes it. The fix is to tilt the orbit heading a few degrees
 * inward/outward so we hold a preferred range while still surfing:
 * too close ⇒ negative tilt (spiral out), too far ⇒ positive tilt (spiral in).
 *
 * Used identically by the live orbit driver and the surf forward-prediction, so
 * predicted arrival positions stay truthful to how we actually drive.
 */
object Distancing {
    /** Preferred engagement range, px. */
    const val TARGET = 400.0

    /** Degrees of tilt per unit of relative range error. */
    private const val GAIN = 60.0

    /** Tilt cap, degrees — keeps the orbit dominantly tangential. */
    private const val MAX_TILT = 25.0

    /** Signed tilt to add to the perpendicular orbit heading (in the orbit
     * direction's sense): positive closes distance, negative opens it. */
    fun tilt(distance: Double): Double = ((distance / TARGET - 1.0) * GAIN).coerceIn(-MAX_TILT, MAX_TILT)
}
