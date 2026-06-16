package zen.mirage

import robocode.AdvancedRobot

/**
 * Radar layer — scaffold state: the beam spins continuously so the enemy is rescanned
 * every tick. Radar locking (turn-to-and-hold the target) is added when the scan→track
 * pipeline is implemented.
 */
class Radar(
    private val bot: AdvancedRobot,
) {
    /** Spin the radar beam indefinitely. */
    fun search() {
        bot.setTurnRadarRight(Double.POSITIVE_INFINITY)
    }
}
