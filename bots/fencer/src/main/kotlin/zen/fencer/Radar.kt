package zen.fencer

import robocode.AdvancedRobot

/**
 * Fencer **radar layer** — the most foundational 1v1 capability: keep the
 * enemy locked so every observation downstream is fresh and reliable.
 *
 * The lock turns the radar to **twice** the remaining offset to the enemy each
 * scan, so the beam always sweeps a little *past* the target and re-captures it
 * next tick — in 1v1 the enemy can't change bearing fast enough to slip a 2×
 * sweep, so this effectively never loses lock. Predictive locking isn't needed
 * with only one opponent. When there's no fresh scan (battle start, or a rare
 * slip), [search] spins the radar to re-acquire.
 *
 * This layer only points the radar; turning scans into records is the
 * observation pipeline ([EnemyTracker.onScan]).
 */
class Radar(
    private val bot: AdvancedRobot,
) {
    /** Maintain the lock given the enemy's absolute bearing this scan. */
    fun lock(absBearingDeg: Double) {
        val offset = Angles.normalizeRelative(absBearingDeg - bot.radarHeading)
        bot.setTurnRadarRight(offset * LOCK_OVERSHOOT)
    }

    /** No fresh scan — sweep a full turn to (re)acquire the enemy. */
    fun search() {
        bot.setTurnRadarRight(Double.POSITIVE_INFINITY)
    }

    private companion object {
        const val LOCK_OVERSHOOT = 2.0
    }
}
