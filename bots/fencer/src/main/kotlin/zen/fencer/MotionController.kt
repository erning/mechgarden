package zen.fencer

import robocode.AdvancedRobot
import kotlin.math.abs

/**
 * Kinematic movement execution layer.
 *
 * Turns a travel intent (a desired heading, or an orbit around a point) into
 * the per-tick body-turn + drive commands under Robocode's physics: it reverses
 * (drives backward) when that faces the goal with less turning, and smooths the
 * heading off the walls so we skim rather than crash. It holds **no enemy stats
 * and no strategy** — strategy decides *where* to go; this is purely *how* to get
 * there. Forward prediction lives in [zen.fencer.Kinematics].
 */
class MotionController(
    private val bot: AdvancedRobot,
) {
    /**
     * Drive toward absolute travel heading [goHeadingDeg], reversing if that
     * faces it with less turning. We command a large move every tick (re-issued
     * each scan), so the bot runs at full speed along the heading rather than
     * braking to a point — "approach", not "stop exactly here".
     */
    fun driveAlong(
        goHeadingDeg: Double,
        maxSpeed: Double = Kinematics.MAX_VELOCITY,
        distance: Double = DRIVE_DISTANCE,
    ) {
        var turn = Angles.normalizeRelative(goHeadingDeg - bot.heading)
        var ahead = distance
        if (abs(turn) > 90.0) {
            turn = Angles.normalizeRelative(turn + 180.0)
            ahead = -distance
        }
        // Cap the speed for this tick (re-set every tick; 8 = full). A low cap
        // makes the bot decelerate / hold — the variable-speed surf candidates.
        bot.setMaxVelocity(maxSpeed)
        bot.setTurnRight(turn)
        bot.setAhead(ahead)
    }

    /**
     * Orbit the point ([centerX], [centerY]) in [dir] (+1 = clockwise as seen
     * from the center), wall-smoothed: travel perpendicular to the center→us
     * line, nudged toward open space near walls.
     */
    fun orbit(
        centerX: Double,
        centerY: Double,
        dir: Int,
        maxSpeed: Double = Kinematics.MAX_VELOCITY,
        distance: Double = DRIVE_DISTANCE,
    ) {
        val centerToUs = Angles.absoluteBearing(centerX, centerY, bot.x, bot.y)
        val range = kotlin.math.hypot(bot.x - centerX, bot.y - centerY)
        val desired = centerToUs + dir * (90.0 + Distancing.tilt(range))
        val go =
            WallSmoothing.smoothedHeading(
                bot.x,
                bot.y,
                desired,
                dir > 0,
                bot.battleFieldWidth,
                bot.battleFieldHeight,
            )
        driveAlong(go, maxSpeed, distance)
    }

    private companion object {
        const val DRIVE_DISTANCE = 100.0
    }
}
