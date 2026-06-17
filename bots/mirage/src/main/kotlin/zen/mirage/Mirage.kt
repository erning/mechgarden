package zen.mirage

import robocode.AdvancedRobot
import robocode.Rules
import robocode.ScannedRobotEvent
import java.awt.Color

/**
 * Mirage — a defensive, movement-focused 1v1 robot. All angles in radians.
 *
 * The [Radar] owns finding, locking, and holding the enemy: a fast cold-start
 * sweep, an adaptive-overshoot lock, and reacquire. The robot just drives it —
 * start it each round, tick it in the loop, feed it scans. The lock keeps a fresh
 * scan coming every tick; the gun reads the bearing straight from that event,
 * fires head-on at minimum power, and the body does not translate yet. Tracking,
 * movement, and wave-surfing come later.
 */
abstract class Mirage : AdvancedRobot() {
    private val radar = Radar(this)

    override fun run() {
        setBodyColor(Color(0x2B, 0x33, 0x33))
        setGunColor(Color(0x6E, 0x7A, 0x80))
        setRadarColor(Color(0xB8, 0xC4, 0xCC))
        radar.beginRound()
        while (true) {
            radar.update()
            execute()
        }
    }

    override fun onScannedRobot(e: ScannedRobotEvent) {
        radar.onScan(e)
        // The radar steers the body and gun on its cold-start recovery tick; leave
        // the gun to it until the lock settles.
        if (radar.state == Radar.State.ACQUIRING) return
        val absBearing = Angles.normalizeAbsolute(headingRadians + e.bearingRadians)
        setTurnGunRightRadians(Angles.normalizeRelative(absBearing - gunHeadingRadians))
        if (gunHeat == 0.0) setFireBullet(Rules.MIN_BULLET_POWER)
    }
}
