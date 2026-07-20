package zen.proteus

import robocode.AdvancedRobot
import robocode.BulletHitBulletEvent
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
 *   2. bullet-end events — matched to waves for danger learning
 *   3. [Radar] — infinity lock, or reacquire sweep on ticks without a scan
 *   4. [Mover] — body channel (wave updates, then surf or orbit)
 *   5. [Aimer] — gun and fire channels
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
    private val pendingEnemyBulletEnds = ArrayList<EnemyBulletEnd>()

    /** An enemy bullet that ended mid-flight (hit us, or collided with ours). */
    private data class EnemyBulletEnd(
        val x: Double,
        val y: Double,
        val power: Double,
        val time: Long,
    )

    override fun run() {
        setColors(BODY_COLOR, GUN_COLOR, RADAR_COLOR)
        setAdjustGunForRobotTurn(true)
        setAdjustRadarForGunTurn(true)
        field = Battlefield(battleFieldWidth, battleFieldHeight)
        gameState.onRoundStart()
        radar.onRoundStart()
        mover.onRoundStart()
        aimer.onRoundStart()
        while (true) {
            tick()
            execute()
        }
    }

    private fun tick() {
        gameState.onStatus(time, x, y, headingRadians, velocity, energy, gunHeat)
        for (end in pendingEnemyBulletEnds) {
            mover.onEnemyBulletAt(end.x, end.y, end.power, end.time)
        }
        pendingEnemyBulletEnds.clear()

        val scan = pendingScan
        pendingScan = null
        val controls = Controls(this)
        if (scan != null && scan.time == time) {
            gameState.onScan(scan, gunCoolingRate)
            radar.onScan(Angles.normalizeAbsolute(headingRadians + scan.bearingRadians), controls)
            for (shot in gameState.consumeEnemyShots()) {
                mover.onEnemyShot(shot)
            }
        } else {
            radar.search(controls)
        }

        val self = gameState.self!!
        val enemy = gameState.enemy
        mover.move(self, gameState.previousSelf(), enemy, gameState.enemyName, field, time, controls)
        if (scan != null && enemy != null) {
            aimer.aim(self, enemy, gameState.enemyName, field, controls)
        }
        controls.apply()
    }

    override fun onScannedRobot(event: ScannedRobotEvent) {
        pendingScan = event
    }

    override fun onBulletHit(event: BulletHitEvent) {
        aimer.onBulletHit(event.bullet.x, event.bullet.y, event.bullet.power, event.time)
        gameState.noteOurBulletHit(event.bullet.power)
    }

    override fun onBulletMissed(event: BulletMissedEvent) {
        aimer.onBulletMissed()
    }

    override fun onHitByBullet(event: HitByBulletEvent) {
        pendingEnemyBulletEnds.add(
            EnemyBulletEnd(event.bullet.x, event.bullet.y, event.bullet.power, event.time),
        )
    }

    override fun onBulletHitBullet(event: BulletHitBulletEvent) {
        pendingEnemyBulletEnds.add(
            EnemyBulletEnd(
                event.hitBullet.x,
                event.hitBullet.y,
                event.hitBullet.power,
                event.time,
            ),
        )
    }

    override fun onHitRobot(event: robocode.HitRobotEvent) {
        gameState.noteRobotCollision()
    }

    private companion object {
        val BODY_COLOR = Color(0x1b, 0x1f, 0x2a)
        val GUN_COLOR = Color(0x4f, 0xc3, 0xf7)
        val RADAR_COLOR = Color(0xff, 0xd5, 0x4f)
    }
}
