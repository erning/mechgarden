package zen.ronin

import robocode.AdvancedRobot

/**
 * Radar layer — keep the single enemy locked so every observation downstream is
 * fresh. Each scan turns the radar to ~2× the remaining offset, so the beam
 * always sweeps past the target and re-captures it next tick. When there's no
 * fresh scan, [search] spins to re-acquire.
 */
class Radar(
    private val bot: AdvancedRobot,
) {
    fun lock(absBearingDeg: Double) {
        val offset = Angles.normalizeRelative(absBearingDeg - bot.radarHeading)
        bot.setTurnRadarRight(offset * LOCK_OVERSHOOT)
    }

    fun search() {
        bot.setTurnRadarRight(Double.POSITIVE_INFINITY)
    }

    private companion object {
        const val LOCK_OVERSHOOT = 2.0
    }
}
