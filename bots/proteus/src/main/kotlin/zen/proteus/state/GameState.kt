package zen.proteus.state

import robocode.Rules
import robocode.ScannedRobotEvent
import zen.proteus.core.Angles

/**
 * Single source of truth for both robots' state. Subsystems read snapshots from
 * here instead of touching the robot API. Enemy position is reconstructed from
 * our scan (bearing/distance are relative to us), so enemy facts are only as
 * fresh as the last scan; ticks without a scan leave [enemy] stale.
 *
 * Enemy fire detection compares consecutive scans: an energy drop in
 * [Rules.MIN_BULLET_POWER, Rules.MAX_BULLET_POWER] while the enemy gun was cold
 * means a shot. Damage our bullets did to the enemy between the two scans is
 * compensated via [noteOurBulletHit]. Enemy wall hits in the same window can
 * still false-positive; later milestones add collision compensation.
 */
internal class GameState {
    /** An enemy shot inferred from an energy drop. */
    data class EnemyShot(
        val power: Double,
        val time: Long,
    )

    var self: BotState? = null
        private set
    var enemy: BotState? = null
        private set

    private val enemyShots = ArrayDeque<EnemyShot>()
    private var ourPendingDamageToEnemy = 0.0

    fun onRoundStart() {
        self = null
        enemy = null
        enemyShots.clear()
        ourPendingDamageToEnemy = 0.0
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
        self = BotState(time, x, y, headingRadians, velocity, energy, gunHeat)
    }

    fun onScan(
        scan: ScannedRobotEvent,
        gunCoolingRate: Double,
    ) {
        val s = self ?: return
        val prev = enemy
        val absoluteBearingRadians = s.headingRadians + scan.bearingRadians
        val enemyX = Angles.projectX(s.x, absoluteBearingRadians, scan.distance)
        val enemyY = Angles.projectY(s.y, absoluteBearingRadians, scan.distance)

        var gunHeat = 0.0
        if (prev != null && scan.time == prev.time + 1) {
            gunHeat = (prev.gunHeat - gunCoolingRate).coerceAtLeast(0.0)
            val energyDrop = prev.energy - scan.energy - ourPendingDamageToEnemy
            if (energyDrop in MIN_DETECTABLE_POWER..MAX_DETECTABLE_POWER &&
                prev.gunHeat <= gunCoolingRate
            ) {
                enemyShots.addLast(EnemyShot(energyDrop, scan.time))
                gunHeat = Rules.getGunHeat(energyDrop)
            }
        }
        ourPendingDamageToEnemy = 0.0

        enemy =
            BotState(
                scan.time,
                enemyX,
                enemyY,
                scan.headingRadians,
                scan.velocity,
                scan.energy,
                gunHeat,
            )
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
    }
}
