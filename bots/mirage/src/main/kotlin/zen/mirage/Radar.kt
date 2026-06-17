package zen.mirage

import robocode.AdvancedRobot
import robocode.Rules

/**
 * Radar layer — at round start the beam sweeps to find the enemy; once scanned it
 * locks on with an overshoot turn so the arc always passes the target and
 * recaptures it next tick. [search] re-spins the beam if the lock is ever lost.
 * All angles in radians.
 *
 * The lock leads the target adaptively. It points at the enemy and overshoots past
 * it on the side it is approaching from (the sign of the offset, which self-corrects
 * so the beam wobbles across the target instead of running away). The overshoot
 * magnitude scales with the measured per-tick bearing rate plus a safety margin, so
 * faster angular motion (close passes, ramming) gets a wider lead and the swept arc
 * still covers the enemy next tick where a fixed 2x overshoot would drop a frame.
 * With no usable history (first contact, or just-reacquired after [search]) the lead
 * falls back to |offset|, reproducing the plain 2x overshoot.
 */
class Radar(
    private val bot: AdvancedRobot,
) {
    private var lastBearingRadians = Double.NaN
    private var lastBearingTime = -1L

    /** Lock the beam onto [absBearingRadians], leading by the bearing rate. */
    fun lock(absBearingRadians: Double) {
        val offset = Angles.normalizeRelative(absBearingRadians - bot.radarHeadingRadians)
        val time = bot.time
        val lead: Double =
            if (lastBearingRadians.isNaN() || time <= lastBearingTime) {
                // No usable history: lead by |offset|, i.e. the plain 2x overshoot.
                Math.abs(offset)
            } else {
                val dt = (time - lastBearingTime).toDouble()
                val bearingRate = Angles.normalizeRelative(absBearingRadians - lastBearingRadians) / dt
                Math.min(Math.abs(bearingRate) + SAFETY_MARGIN, MAX_LEAD)
            }
        // Overshoot on the side the beam is approaching from so it wobbles across
        // the target and self-corrects, never running away with the drift.
        val direction = if (offset >= 0.0) 1.0 else -1.0
        bot.setTurnRadarRightRadians(offset + direction * lead)
        lastBearingRadians = absBearingRadians
        lastBearingTime = time
    }

    /** Spin the radar beam indefinitely to (re)acquire the enemy, dropping the
     *  stale bearing history so the next lock re-seeds its rate from fresh scans. */
    fun search() {
        bot.setTurnRadarRightRadians(Double.POSITIVE_INFINITY)
        lastBearingRadians = Double.NaN
        lastBearingTime = -1L
    }

    private companion object {
        /** Extra lead beyond the measured per-tick drift, absorbing acceleration. */
        val SAFETY_MARGIN = Math.toRadians(15.0)

        /** Cap on the lead so it never demands more than the radar can deliver. */
        val MAX_LEAD = Rules.RADAR_TURN_RATE_RADIANS
    }
}
