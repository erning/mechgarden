package zen.proteus

import robocode.AdvancedRobot
import robocode.BulletHitEvent
import robocode.BulletMissedEvent
import robocode.HitByBulletEvent
import robocode.ScannedRobotEvent
import zen.proteus.aim.Aimer
import zen.proteus.control.Controls
import zen.proteus.core.Angles
import zen.proteus.core.Battlefield
import zen.proteus.move.Mover
import zen.proteus.radar.Radar
import zen.proteus.state.GameState
import java.awt.Color

/**
 * Proteus — MechGarden's adaptive 1v1 robot, built to approach and eventually
 * surpass the BeepBoop generation with original code. Architecture and milestone
 * plan live in bots/proteus/docs/.
 *
 * Per-tick pipeline, ordered by data dependency; each subsystem writes only its
 * own channel of [Controls], and the frame is committed once by `execute()`:
 *   1. [GameState] — self snapshot, enemy reconstruction, enemy fire detection
 *   2. [Radar] — infinity lock, or reacquire sweep on ticks without a scan
 *   3. [Mover] — body channel
 *   4. [Aimer] — gun and fire channels
 *
 * Movement runs before aiming so later milestones can feed the gun the simulated
 * future position the bullet will actually leave from.
 */
abstract class Proteus : AdvancedRobot() {
    private val gameState = GameState()
    private val radar = Radar(this)
    private val mover = Mover()
    private val aimer = Aimer(this)
    private lateinit var field: Battlefield

    private var pendingScan: ScannedRobotEvent? = null

    override fun run() {
        setColors(BODY_COLOR, GUN_COLOR, RADAR_COLOR)
        setAdjustGunForRobotTurn(true)
        setAdjustRadarForGunTurn(true)
        field = Battlefield(battleFieldWidth, battleFieldHeight)
        gameState.onRoundStart()
        radar.onRoundStart()
        mover.onRoundStart()
        while (true) {
            tick()
            execute()
        }
    }

    private fun tick() {
        gameState.onStatus(time, x, y, headingRadians, velocity, energy, gunHeat)
        val scan = pendingScan
        pendingScan = null
        val controls = Controls(this)
        if (scan != null && scan.time == time) {
            gameState.onScan(scan, gunCoolingRate)
            val self = gameState.self!!
            val enemy = gameState.enemy!!
            radar.onScan(Angles.normalizeAbsolute(headingRadians + scan.bearingRadians), controls)
            for (shot in gameState.consumeEnemyShots()) {
                mover.onEnemyShot()
            }
            mover.move(self, enemy, field, controls)
            aimer.aim(self, enemy, field, controls)
        } else {
            radar.search(controls)
        }
        controls.apply()
    }

    override fun onScannedRobot(event: ScannedRobotEvent) {
        pendingScan = event
    }

    override fun onBulletHit(event: BulletHitEvent) {
        aimer.onBulletHit()
        gameState.noteOurBulletHit(event.bullet.power)
    }

    override fun onBulletMissed(event: BulletMissedEvent) {
        aimer.onBulletMissed()
    }

    override fun onHitByBullet(event: HitByBulletEvent) {
        // M2: match the bullet to an enemy wave and learn danger from the hit GF.
    }

    private companion object {
        val BODY_COLOR = Color(0x1b, 0x1f, 0x2a)
        val GUN_COLOR = Color(0x4f, 0xc3, 0xf7)
        val RADAR_COLOR = Color(0xff, 0xd5, 0x4f)
    }
}
