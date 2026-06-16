package zen.mirage

import robocode.AdvancedRobot
import robocode.Rules
import robocode.ScannedRobotEvent
import java.awt.Color

/**
 * Mirage — a defensive, movement-focused 1v1 robot. All angles in radians.
 *
 * Radar layer: at round start the body, gun, and radar spin together (adjust
 * flags left at their default false so they stack) and the sweep heads toward
 * the field center — enemy spawns are uniform, so a near-edge start is more
 * likely to face the interior. On first contact the flags are set true to
 * decouple the gun and radar for a stable 2x-overshoot lock, re-searched alone
 * if the lock is lost. The stacked sweep reaches the engine's max radar sweep
 * (~1.31 rad/tick). Over 1000 rounds vs sample.Crazy this finds the enemy in
 * ~2.2 ticks on average (max 5), down from ~4.3 (max 8) for a radar-only
 * fixed-direction sweep. The gun still fires head-on at minimum power; the body
 * still does not translate. Tracking, movement, and wave-surfing come later.
 */
abstract class Mirage : AdvancedRobot() {
    private val radar = Radar(this)

    private var lastScanTime = 0L
    private var locked = false

    override fun run() {
        setBodyColor(Color(0x2B, 0x33, 0x33))
        setGunColor(Color(0x6E, 0x7A, 0x80))
        setRadarColor(Color(0xB8, 0xC4, 0xCC))
        // Adjust flags stay false (default) during the opening sweep so the body,
        // gun, and radar stack; they are decoupled in onScannedRobot once locked.
        acquireSweep()
        while (true) {
            if (locked && time - lastScanTime > REACQUIRE_TICKS) radar.search()
            execute()
        }
    }

    /** Opening search: spin the body, gun, and radar together toward the field
     *  center at the engine's max radar sweep (~1.31 rad/tick) instead of
     *  radar-only. Called once; the infinite turns sustain until onScannedRobot
     *  overrides them on first lock. */
    private fun acquireSweep() {
        val centerBearing = Angles.absoluteBearing(x, y, battleFieldWidth / 2.0, battleFieldHeight / 2.0)
        val dir =
            if (Angles.normalizeRelative(centerBearing - radarHeadingRadians) >= 0.0) {
                Double.POSITIVE_INFINITY
            } else {
                Double.NEGATIVE_INFINITY
            }
        setTurnRightRadians(dir)
        setTurnGunRightRadians(dir)
        setTurnRadarRightRadians(dir)
    }

    override fun onScannedRobot(e: ScannedRobotEvent) {
        lastScanTime = time
        if (!locked) {
            locked = true
            // Stop the opening body spin, then decouple gun/radar for a stable lock.
            setTurnRightRadians(0.0)
            isAdjustGunForRobotTurn = true
            isAdjustRadarForGunTurn = true
        }

        val absBearing = Angles.normalizeAbsolute(headingRadians + e.bearingRadians)
        radar.lock(absBearing)

        setTurnGunRightRadians(Angles.normalizeRelative(absBearing - gunHeadingRadians))
        if (gunHeat == 0.0) setFireBullet(Rules.MIN_BULLET_POWER)
    }

    private companion object {
        const val REACQUIRE_TICKS = 1L
    }
}
