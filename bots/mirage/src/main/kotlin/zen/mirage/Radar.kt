package zen.mirage

import robocode.AdvancedRobot

/**
 * Radar layer — at round start the beam sweeps to find the enemy; once scanned it
 * locks on with a 2x-overshoot turn so the arc always passes the target and
 * recaptures it next tick. [search] re-spins the beam if the lock is ever lost.
 * All angles in radians.
 */
class Radar(
    private val bot: AdvancedRobot,
) {
    /** Lock the beam onto [absBearingRad] with a 2x overshoot past the target. */
    fun lock(absBearingRad: Double) {
        val offset = Angles.normalizeRelative(absBearingRad - bot.radarHeadingRadians)
        bot.setTurnRadarRightRadians(offset * LOCK_OVERSHOOT)
    }

    /** Spin the radar beam indefinitely to (re)acquire the enemy. */
    fun search() {
        bot.setTurnRadarRightRadians(Double.POSITIVE_INFINITY)
    }

    private companion object {
        const val LOCK_OVERSHOOT = 2.0
    }
}
