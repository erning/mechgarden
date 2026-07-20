package zen.proteus.control

import robocode.AdvancedRobot
import robocode.Bullet

/**
 * One tick's command frame. Each subsystem writes only its own channel and
 * [apply] issues the queued `set*` calls once, in a fixed order; the main loop
 * then commits them with `execute()`. A null channel is left untouched, so a
 * previously issued turn keeps running — this makes the single-writer-per-channel
 * rule structural instead of conventional.
 */
internal class Controls(
    private val robot: AdvancedRobot,
) {
    var bodyTurnRadians: Double? = null
    var ahead: Double? = null
    var maxVelocity: Double? = null
    var gunTurnRadians: Double? = null
    var radarTurnRadians: Double? = null
    var firePower: Double? = null

    /** Issues the queued `set*` commands. Returns the fired bullet, if any. */
    fun apply(): Bullet? {
        bodyTurnRadians?.let { robot.setTurnRightRadians(it) }
        ahead?.let { robot.setAhead(it) }
        maxVelocity?.let { robot.setMaxVelocity(it) }
        gunTurnRadians?.let { robot.setTurnGunRightRadians(it) }
        radarTurnRadians?.let { robot.setTurnRadarRightRadians(it) }
        // setFireBullet (not setFire) so the caller gets the Bullet for wave and
        // shadow bookkeeping; firing is still within this tick's command frame.
        return firePower?.let { robot.setFireBullet(it) }
    }
}
