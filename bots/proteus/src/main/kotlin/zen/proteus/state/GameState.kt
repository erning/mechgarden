package zen.proteus.state

import robocode.Rules
import robocode.ScannedRobotEvent
import zen.proteus.core.Angles
import kotlin.math.sin

/**
 * Single source of truth for both robots' state. Subsystems read snapshots from
 * here instead of touching the robot API. Enemy position is reconstructed from
 * our scan (bearing/distance are relative to us), so enemy facts are only as
 * fresh as the last scan; ticks without a scan leave [enemy] stale.
 *
 * Enemy fire detection compares consecutive scans: an energy drop in
 * [MIN_DETECTABLE_POWER, MAX_DETECTABLE_POWER] while the enemy gun was cold
 * means a shot. Damage our bullets did to the enemy between the two scans is
 * compensated via [noteOurBulletHit]. Enemy wall hits in the same window can
 * still false-positive; later milestones add collision compensation.
 */
internal class GameState {
    /**
     * An enemy shot inferred from an energy drop. The bullet spawns at the start
     * of the detection turn at the enemy's position one tick earlier (bullets
     * load before robots move), so [originX], [originY] is the previous enemy
     * position and [directAngleRadians] aims at our previous position.
     * [lateralDirection] is the sign of our lateral velocity at fire time.
     */
    data class EnemyShot(
        val power: Double,
        val time: Long,
        val originX: Double,
        val originY: Double,
        val directAngleRadians: Double,
        val lateralDirection: Double,
    )

    var self: BotState? = null
        private set
    var enemy: BotState? = null
        private set
    var enemyName: String? = null
        private set

    private val selfHistory = ArrayDeque<BotState>()
    private val enemyHistory = ArrayDeque<BotState>()
    private val enemyShots = ArrayDeque<EnemyShot>()
    private var ourPendingDamageToEnemy = 0.0
    private var collisionPendingDamage = 0.0

    fun onRoundStart() {
        self = null
        enemy = null
        enemyName = null
        selfHistory.clear()
        enemyHistory.clear()
        enemyShots.clear()
        ourPendingDamageToEnemy = 0.0
        collisionPendingDamage = 0.0
    }

    fun onStatus(
        time: Long,
        x: Double,
        y: Double,
        headingRadians: Double,
        velocity: Double,
        energy: Double,
        gunHeat: Double,
    ) {
        val state = BotState(time, x, y, headingRadians, velocity, energy, gunHeat)
        self = state
        selfHistory.addLast(state)
        while (selfHistory.size > HISTORY_LIMIT) selfHistory.removeFirst()
    }

    /** Our snapshot one tick before the latest one, if available. */
    fun previousSelf(): BotState? = if (selfHistory.size >= 2) selfHistory[selfHistory.size - 2] else null

    fun selfAt(time: Long): BotState? = selfHistory.lastOrNull { it.time == time }

    fun enemyAt(time: Long): BotState? = enemyHistory.lastOrNull { it.time == time }

    fun onScan(
        scan: ScannedRobotEvent,
        gunCoolingRate: Double,
    ) {
        val s = self ?: return
        if (enemyName == null) enemyName = scan.name
        val prev = enemy
        val absoluteBearingRadians = s.headingRadians + scan.bearingRadians
        val enemyX = Angles.projectX(s.x, absoluteBearingRadians, scan.distance)
        val enemyY = Angles.projectY(s.y, absoluteBearingRadians, scan.distance)

        var gunHeat = 0.0
        if (prev != null && scan.time == prev.time + 1) {
            gunHeat = (prev.gunHeat - gunCoolingRate).coerceAtLeast(0.0)
            val energyDrop =
                prev.energy - scan.energy - ourPendingDamageToEnemy - collisionPendingDamage
            if (energyDrop in MIN_DETECTABLE_POWER..MAX_DETECTABLE_POWER &&
                prev.gunHeat <= gunCoolingRate
            ) {
                val ourAtFire = previousSelf() ?: s
                val directAngleRadians =
                    Angles.absoluteBearingRadians(prev.x, prev.y, ourAtFire.x, ourAtFire.y)
                val lateralVelocity =
                    ourAtFire.velocity * sin(ourAtFire.headingRadians - directAngleRadians)
                enemyShots.addLast(
                    EnemyShot(
                        energyDrop,
                        scan.time,
                        prev.x,
                        prev.y,
                        directAngleRadians,
                        if (lateralVelocity >= 0.0) 1.0 else -1.0,
                    ),
                )
                gunHeat = Rules.getGunHeat(energyDrop)
            }
        }
        ourPendingDamageToEnemy = 0.0
        collisionPendingDamage = 0.0

        val state =
            BotState(
                scan.time,
                enemyX,
                enemyY,
                scan.headingRadians,
                scan.velocity,
                scan.energy,
                gunHeat,
            )
        enemy = state
        enemyHistory.addLast(state)
        while (enemyHistory.size > HISTORY_LIMIT) enemyHistory.removeFirst()
    }

    /** We collided with the enemy robot; both sides take Rules.ROBOT_HIT_DAMAGE. */
    fun noteRobotCollision() {
        collisionPendingDamage += Rules.ROBOT_HIT_DAMAGE
    }

    /** Shots detected since the last call, in firing order. */
    fun consumeEnemyShots(): List<EnemyShot> {
        if (enemyShots.isEmpty()) return emptyList()
        val shots = enemyShots.toList()
        enemyShots.clear()
        return shots
    }

    /** Our bullet of [power] hit the enemy; compensate the next energy-drop check. */
    fun noteOurBulletHit(power: Double) {
        ourPendingDamageToEnemy += Rules.getBulletDamage(power)
    }

    private companion object {
        // Firing costs exactly the bullet power; slack absorbs double rounding.
        const val MIN_DETECTABLE_POWER = 0.0999
        const val MAX_DETECTABLE_POWER = 3.0001
        const val HISTORY_LIMIT = 120
    }
}
