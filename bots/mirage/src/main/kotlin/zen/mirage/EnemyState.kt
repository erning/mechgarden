package zen.mirage

import robocode.ScannedRobotEvent

/**
 * Immutable snapshot of the enemy for a single scan, in radians (Robocode frame:
 * +x east, +y north; headings 0 = north, clockwise).
 *
 * A [ScannedRobotEvent] reports the enemy relative to us (bearing off our heading,
 * distance), so deriving the enemy's absolute position and bearing needs our own
 * state at scan time. [from] takes that as a [RobotState]; pass the snapshot for
 * the same tick (e.g. captured in `onStatus`, or `RobotState.from(this)` inside
 * `onScannedRobot`) or the absolute position will be wrong.
 *
 * [headingRadians] is the enemy's own heading; [absoluteBearingRadians] is the
 * bearing from our position to the enemy. Other layers read this snapshot rather
 * than re-deriving from the raw event.
 */
data class EnemyState(
    val time: Long,
    val x: Double,
    val y: Double,
    val energy: Double,
    val velocity: Double,
    val headingRadians: Double,
    val absoluteBearingRadians: Double,
    val distance: Double,
) {
    companion object {
        /** Build from a scan, placing the enemy relative to [self] at scan time. */
        fun from(
            e: ScannedRobotEvent,
            self: RobotState,
        ): EnemyState {
            val absoluteBearing = Angles.normalizeAbsolute(self.headingRadians + e.bearingRadians)
            return EnemyState(
                time = e.time,
                x = self.x + Math.sin(absoluteBearing) * e.distance,
                y = self.y + Math.cos(absoluteBearing) * e.distance,
                energy = e.energy,
                velocity = e.velocity,
                headingRadians = e.headingRadians,
                absoluteBearingRadians = absoluteBearing,
                distance = e.distance,
            )
        }
    }
}
