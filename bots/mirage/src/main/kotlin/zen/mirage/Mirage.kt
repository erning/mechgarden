package zen.mirage

import robocode.AdvancedRobot
import robocode.Rules
import robocode.ScannedRobotEvent
import robocode.StatusEvent
import java.awt.Color

/**
 * Mirage — a defensive, movement-focused 1v1 robot. All angles in radians.
 *
 * The [Radar] owns finding, locking, and holding the enemy. Every tick `onStatus`
 * snapshots our own [RobotState]; each scan the [Tracker] pairs that with the
 * enemy observation into a frame (with the cross-tick derived state) and keeps the
 * history. The gun — and the movement and wave layers to come — read those frames
 * rather than the raw robot or event. The gun still fires head-on at minimum power
 * and the body does not translate yet; tracking, movement, and wave-surfing come
 * later.
 */
abstract class Mirage : AdvancedRobot() {
    private val radar = Radar(this)
    private val tracker = Tracker()

    /** Our own state, refreshed every tick from the status event (priority 99, so
     *  it lands before the scan event that reads it). */
    private var self: RobotState? = null

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

    override fun onStatus(e: StatusEvent) {
        self = RobotState.from(e.status)
    }

    override fun onScannedRobot(e: ScannedRobotEvent) {
        radar.onScan(e)
        // The radar steers the body and gun on its cold-start recovery tick; leave
        // the gun to it until the lock settles.
        if (radar.state == Radar.State.ACQUIRING) return
        val self = self ?: return
        val frame = tracker.onScan(e, self)
        setTurnGunRightRadians(Angles.normalizeRelative(frame.enemy.absoluteBearingRadians - gunHeadingRadians))
        if (gunHeat == 0.0) setFireBullet(Rules.MIN_BULLET_POWER)
    }
}
