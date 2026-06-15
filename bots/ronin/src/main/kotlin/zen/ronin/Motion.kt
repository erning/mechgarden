package zen.ronin

import robocode.AdvancedRobot
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Kinematic movement execution layer. Turns a travel intent (a desired heading, or
 * an orbit around a point) into per-tick body-turn + drive commands under
 * Robocode's physics: it reverses when that faces the goal with less turning, and
 * wall-smooths the heading. No enemy stats, no strategy — strategy decides *where*
 * to go; this is purely *how* to get there.
 */
class MotionController(
    private val bot: AdvancedRobot,
) {
    /** Drive toward absolute travel heading [goHeadingDeg], reversing if that faces
     * it with less turning. A large [distance] every tick means we cruise along the
     * heading rather than braking to a point. */
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
        bot.setMaxVelocity(maxSpeed)
        bot.setTurnRight(turn)
        bot.setAhead(ahead)
    }

    /** Orbit ([centerX], [centerY]) in [dir] (+1 clockwise seen from the center),
     * wall-smoothed: travel perpendicular to the center→us line, nudged by the
     * distancing tilt. */
    fun orbit(
        centerX: Double,
        centerY: Double,
        dir: Int,
        maxSpeed: Double = Kinematics.MAX_VELOCITY,
        distance: Double = DRIVE_DISTANCE,
    ) {
        val centerToUs = Angles.absoluteBearing(centerX, centerY, bot.x, bot.y)
        val range = hypot(bot.x - centerX, bot.y - centerY)
        val desired = centerToUs + dir * (90.0 + Distancing.tilt(range))
        val go = WallSmoothing.smoothedHeading(bot.x, bot.y, desired, dir > 0, bot.battleFieldWidth, bot.battleFieldHeight)
        driveAlong(go, maxSpeed, distance)
    }

    private companion object {
        const val DRIVE_DISTANCE = 100.0
    }
}

/** Turret execution: points the gun and pulls the trigger. */
class Turret(
    private val bot: AdvancedRobot,
) {
    /** Turn the gun toward [aimAngleDeg]; return the remaining turn (deg). */
    fun aimAt(aimAngleDeg: Double): Double {
        val turn = Angles.normalizeRelative(aimAngleDeg - bot.gunHeading)
        bot.setTurnGunRight(turn)
        return turn
    }

    /** Fire [power]; non-null only if a shot left the gun. */
    fun fire(power: Double): robocode.Bullet? = bot.setFireBullet(power)
}
