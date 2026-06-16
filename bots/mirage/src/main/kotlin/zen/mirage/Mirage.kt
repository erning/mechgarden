package zen.mirage

import robocode.AdvancedRobot
import robocode.Rules
import robocode.ScannedRobotEvent
import java.awt.Color

/**
 * Mirage — a defensive, movement-focused 1v1 robot (scaffold).
 *
 * Scaffold behaviour only, no strategy yet: the radar spins continuously; when an
 * enemy is scanned the gun points head-on at it and fires at minimum power; the body
 * does not move. Tracking, movement, wave-surfing, and gun learning are implemented
 * layer-by-layer on top of this foundation.
 */
abstract class Mirage : AdvancedRobot() {
    private val radar = Radar(this)

    override fun run() {
        setBodyColor(Color(0x2B, 0x33, 0x3A))
        setGunColor(Color(0x6E, 0x7A, 0x80))
        setRadarColor(Color(0xB8, 0xC4, 0xCC))
        isAdjustGunForRobotTurn = true
        isAdjustRadarForGunTurn = true

        radar.search()
        while (true) {
            execute()
        }
    }

    override fun onScannedRobot(e: ScannedRobotEvent) {
        val absBearing = Angles.normalizeAbsolute(heading + e.bearing)
        setTurnGunRight(Angles.normalizeRelative(absBearing - gunHeading))
        if (gunHeat == 0.0) setFireBullet(Rules.MIN_BULLET_POWER)
    }
}
