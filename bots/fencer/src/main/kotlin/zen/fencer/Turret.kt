package zen.fencer

import robocode.AdvancedRobot
import robocode.Bullet

/**
 * Turret execution layer. It points the gun and pulls the trigger; aim model,
 * firepower, and fire gating live in [Gun].
 */
class Turret(
    private val bot: AdvancedRobot,
) {
    /** Turn the gun toward [aimAngleDeg]; return the remaining turn (deg) so the
     * caller can judge alignment for the fire gate. */
    fun aimAt(aimAngleDeg: Double): Double {
        val turn = Angles.normalizeRelative(aimAngleDeg - bot.gunHeading)
        bot.setTurnGunRight(turn)
        return turn
    }

    /** Fire [power]; the returned [Bullet] is non-null only if a shot left the gun. */
    fun fire(power: Double): Bullet? = bot.setFireBullet(power)
}
