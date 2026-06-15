package zen.fencer

/**
 * The shot problem for one firing decision: the
 * fire-time situation that the aim models, the GuessFactor segmentation,
 * and firepower all read, so they share one consistent view instead of
 * each recomputing it.
 *
 * Built for the **current tick**: with our fire-when-cool + per-tick re-aim
 * policy, the gun continuously tracks the target and fires the instant it's cool
 * (`ticksUntilCool = 0`), so explicit "predict both robots to the fire tick"
 * latency compensation is inert here — the current-tick situation *is* the
 * fire-tick situation. (A commit-and-wait gun would need it; we don't.)
 */
class ShotContext(
    val sourceX: Double,
    val sourceY: Double,
    val enemy: Snapshot,
    val turnRateDeg: Double,
    val distance: Double,
    /** Bearing source→enemy, the guess-factor zero (degrees clockwise from north). */
    val directAngleDeg: Double,
    /** Enemy speed across our line of sight (signed). */
    val lateralSpeed: Double,
    /** Change in enemy *speed* per tick (+ speeding up, − braking) — a gun
     * feature axis complementary to lateral speed. */
    val accel: Double,
    /** Sign of [lateralSpeed] — the enemy's orbit sense around us. */
    val orbitSign: Int,
    val power: Double,
    val bulletSpeed: Double,
    /** Widest angle the enemy can be off the direct line, given max speed (deg). */
    val maxEscapeDeg: Double,
)
