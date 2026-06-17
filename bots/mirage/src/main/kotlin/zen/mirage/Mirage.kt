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
 * likely to face the interior. The stacked sweep reaches the engine's max radar
 * sweep (~1.31 rad/tick). Over 1000 rounds vs sample.Crazy this finds the enemy
 * in ~2.2 ticks on average (max 5), down from ~4.3 (max 8) for a radar-only
 * fixed-direction sweep.
 *
 * First contact has to undo that same fast sweep: the beam can overshoot the
 * target by more than a decoupled radar's 45°/tick reach, so a plain decoupled
 * 2x lock would miss the next tick and go blind for several ticks. So when the
 * first-contact offset exceeds the radar's own turn rate, Mirage stays coupled
 * for one recovery tick and turns body, gun, and radar toward the target so
 * their rates stack (~75°/tick) and swing the beam back; otherwise it decouples
 * immediately. After the lock settles the gun and radar are decoupled for a
 * stable 2x-overshoot lock, re-searched alone if the lock is lost. The gun still
 * fires head-on at minimum power; the body still does not translate. Tracking,
 * movement, and wave-surfing come later.
 */
abstract class Mirage : AdvancedRobot() {
    private val radar = Radar(this)

    private var lastScanTime = 0L
    private var locked = false

    /** True for the one coupled recovery tick after a wide first-contact overshoot;
     *  decouple into the stable lock on the next scan. */
    private var coupledLockPending = false

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
        val absBearing = Angles.normalizeAbsolute(headingRadians + e.bearingRadians)

        if (!locked) {
            locked = true
            val offset = Angles.normalizeRelative(absBearing - radarHeadingRadians)
            if (Math.abs(offset) > Rules.RADAR_TURN_RATE_RADIANS) {
                // The opening sweep overshot the target by more than a decoupled
                // radar can swing back in one tick. Stay coupled this tick and turn
                // body, gun, and radar toward it so their rates stack (~75°/tick).
                coupledLockPending = true
                setTurnRightRadians(offset)
                setTurnGunRightRadians(offset)
                radar.lock(absBearing)
                return
            }
            // Within a decoupled radar's reach: stop the body spin and decouple now.
            setTurnRightRadians(0.0)
            isAdjustGunForRobotTurn = true
            isAdjustRadarForGunTurn = true
        } else if (coupledLockPending) {
            // Recovery tick done; settle into the decoupled stable lock.
            coupledLockPending = false
            setTurnRightRadians(0.0)
            isAdjustGunForRobotTurn = true
            isAdjustRadarForGunTurn = true
        }

        radar.lock(absBearing)
        setTurnGunRightRadians(Angles.normalizeRelative(absBearing - gunHeadingRadians))
        if (gunHeat == 0.0) setFireBullet(Rules.MIN_BULLET_POWER)
    }

    private companion object {
        const val REACQUIRE_TICKS = 1L
    }
}
