package zen.mirage

/**
 * Derived enemy state for one scan: the quantities that need two consecutive
 * [EnemyState] snapshots (related to our line of sight) instead of a single scan.
 * These are what targeting and wave-surfing actually consume. All angles in
 * radians.
 *
 * It is a pure function of the current and previous snapshots, so it only exists
 * once there are two scans; the layer that keeps the previous snapshot (a tracker)
 * produces it. Rates are per tick — divided by the scan gap, normally 1.
 *
 * [lateralVelocity] and [advancingVelocity] split the enemy's own velocity about
 * our line of sight (lateral perpendicular, advancing positive toward us).
 * [bearingRateRadians] is the full angular sweep of the enemy and so also reflects
 * our own motion. [lateralDirection] is just the sign of [lateralVelocity] (0 when
 * the enemy moves straight at or away from us); keeping it sticky across those
 * straight-line moments is left to the consumer. [energyDelta] is the raw energy
 * change; deciding that the enemy fired (and excluding wall and bullet-hit causes)
 * belongs to the wave layer.
 */
data class EnemyDerived(
    val lateralVelocity: Double,
    val advancingVelocity: Double,
    val lateralDirection: Int,
    val bearingRateRadians: Double,
    val turnRateRadians: Double,
    val acceleration: Double,
    val distanceRate: Double,
    val energyDelta: Double,
) {
    companion object {
        /** Derive from the [current] scan relative to the [previous] one. */
        fun from(
            current: EnemyState,
            previous: EnemyState,
        ): EnemyDerived {
            val dt = (current.time - previous.time).coerceAtLeast(1L).toDouble()
            // Angle of the enemy's travel direction off our line of sight to it.
            val angleOffLineOfSight = current.headingRadians - current.absoluteBearingRadians
            val lateralVelocity = current.velocity * Math.sin(angleOffLineOfSight)
            return EnemyDerived(
                lateralVelocity = lateralVelocity,
                advancingVelocity = -current.velocity * Math.cos(angleOffLineOfSight),
                lateralDirection = Math.signum(lateralVelocity).toInt(),
                bearingRateRadians =
                    Angles.normalizeRelative(
                        current.absoluteBearingRadians - previous.absoluteBearingRadians,
                    ) / dt,
                turnRateRadians =
                    Angles.normalizeRelative(
                        current.headingRadians - previous.headingRadians,
                    ) / dt,
                acceleration = (current.velocity - previous.velocity) / dt,
                distanceRate = (current.distance - previous.distance) / dt,
                energyDelta = current.energy - previous.energy,
            )
        }
    }
}
