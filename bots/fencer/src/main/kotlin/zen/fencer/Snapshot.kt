package zen.fencer

/**
 * Immutable world-state of one robot at a known tick — a **fact**, never a
 * prediction, never mutated after capture. Robocode's frame: +x east, +y north;
 * heading is degrees clockwise from north (see docs/robocode-physics.md).
 *
 * Both our own and the enemy's state are captured as [Snapshot]s at the *same*
 * tick, so any relative quantity (lateral velocity, bearing, …) is computed from
 * a single consistent time slice.
 */
data class Snapshot(
    val time: Long,
    val x: Double,
    val y: Double,
    val headingDeg: Double,
    val velocity: Double,
    val energy: Double,
)
