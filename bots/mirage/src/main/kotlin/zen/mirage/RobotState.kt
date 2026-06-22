package zen.mirage

import robocode.AdvancedRobot
import robocode.RobotStatus

/**
 * Immutable snapshot of our own robot's physical state for a single tick, in
 * radians (Robocode frame: +x east, +y north; headings 0 = north, clockwise).
 *
 * Built from either source of the same per-tick getters:
 *  - [from] an [AdvancedRobot]: snapshot on demand anywhere (an event handler, or
 *    the moment a bullet is fired to stamp a wave origin).
 *  - [from] a [RobotStatus]: the snapshot Robocode hands to `onStatus` every tick,
 *    the natural place to capture our state once per turn.
 */
data class RobotState(
    val time: Long,
    val x: Double,
    val y: Double,
    val energy: Double,
    val velocity: Double,
    val headingRadians: Double,
    val gunHeadingRadians: Double,
    val gunHeat: Double,
    val radarHeadingRadians: Double,
) {
    companion object {
        /** Snapshot the live robot (valid in `run` or any event handler). */
        fun from(robot: AdvancedRobot) =
            RobotState(
                time = robot.time,
                x = robot.x,
                y = robot.y,
                energy = robot.energy,
                velocity = robot.velocity,
                headingRadians = robot.headingRadians,
                gunHeadingRadians = robot.gunHeadingRadians,
                gunHeat = robot.gunHeat,
                radarHeadingRadians = robot.radarHeadingRadians,
            )

        /** Snapshot the per-tick status Robocode passes to `onStatus`. */
        fun from(status: RobotStatus) =
            RobotState(
                time = status.time,
                x = status.x,
                y = status.y,
                energy = status.energy,
                velocity = status.velocity,
                headingRadians = status.headingRadians,
                gunHeadingRadians = status.gunHeadingRadians,
                gunHeat = status.gunHeat,
                radarHeadingRadians = status.radarHeadingRadians,
            )
    }
}
