package zen.ronin

import robocode.Rules
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin

/**
 * Immutable world-state of one robot at a known tick — a fact, never a prediction.
 * Robocode frame: +x east, +y north; heading degrees clockwise from north.
 */
data class Snapshot(
    val time: Long,
    val x: Double,
    val y: Double,
    val headingDeg: Double,
    val velocity: Double,
    val energy: Double,
)

/**
 * Turn delayed, discrete radar scans into time-consistent, physics-correct facts
 * about the enemy: absolute position, turn rate, lateral/advancing velocity,
 * acceleration, time-since-change counters, distance and wall room. All rates are
 * Δt-aware (a missed scan divides by the real tick gap).
 */
class EnemyTracker {
    var enemy: Snapshot? = null
        private set
    var self: Snapshot? = null
        private set
    var lastScanTime: Long = -1L
        private set

    var turnRateDegPerTick: Double = 0.0
        private set
    var lateralVelocity: Double = 0.0
        private set
    var advancingVelocity: Double = 0.0
        private set
    var acceleration: Double = 0.0
        private set
    var absoluteBearingDeg: Double = 0.0
        private set
    var distance: Double = 0.0
        private set

    /** Distance the enemy can travel along its current heading before a wall. */
    var forwardWallSpace: Double = 0.0
        private set

    /** Ticks since the enemy's lateral direction (orbit sense around us) flipped —
     * the oscillation phase a surf-style mover reverses on; a strong DC-gun key. */
    var timeSinceDirectionChange: Long = 0L
        private set

    var stationaryTicks: Long = 0L
        private set
    var ticksSinceLowPowerFire: Long = LOW_POWER_RECENT_TICKS + 1L
        private set
    val shieldLikely: Boolean
        get() = stationaryTicks >= SHIELD_STATIONARY_TICKS && ticksSinceLowPowerFire <= LOW_POWER_RECENT_TICKS

    private var lastLateralSign: Int = 0

    fun scanAge(now: Long): Long = if (lastScanTime < 0L) Long.MAX_VALUE else now - lastScanTime

    fun onScan(
        time: Long,
        selfX: Double,
        selfY: Double,
        selfHeadingDeg: Double,
        selfVelocity: Double,
        selfEnergy: Double,
        absoluteBearingDeg: Double,
        enemyDistance: Double,
        enemyHeadingDeg: Double,
        enemyVelocity: Double,
        enemyEnergy: Double,
        fieldWidth: Double,
        fieldHeight: Double,
    ) {
        val bearingRad = Math.toRadians(absoluteBearingDeg)
        val ex = selfX + sin(bearingRad) * enemyDistance
        val ey = selfY + cos(bearingRad) * enemyDistance
        val now = Snapshot(time, ex, ey, enemyHeadingDeg, enemyVelocity, enemyEnergy)

        val prev = enemy
        val dt = if (prev != null) time - prev.time else 0L
        val trusted = prev != null && dt in 1L..MAX_DT
        val step = if (trusted) dt else 1L

        if (trusted) {
            turnRateDegPerTick = Angles.normalizeRelative(enemyHeadingDeg - prev.headingDeg) / dt
            acceleration = (abs(enemyVelocity) - abs(prev.velocity)) / dt
        } else if (dt > MAX_DT) {
            turnRateDegPerTick = 0.0
            acceleration = 0.0
        }
        val speedAbs = abs(enemyVelocity)
        stationaryTicks = if (speedAbs <= SHIELD_STATIONARY_SPEED) stationaryTicks + step else 0L
        ticksSinceLowPowerFire = (ticksSinceLowPowerFire + step).coerceAtMost(LOW_POWER_RECENT_TICKS + 1L)

        val rel = Math.toRadians(enemyHeadingDeg - absoluteBearingDeg)
        lateralVelocity = enemyVelocity * sin(rel)
        advancingVelocity = -enemyVelocity * cos(rel)
        this.absoluteBearingDeg = absoluteBearingDeg
        this.distance = enemyDistance

        val travelHeading = if (enemyVelocity >= 0.0) enemyHeadingDeg else enemyHeadingDeg + 180.0
        forwardWallSpace = distanceToWall(ex, ey, travelHeading, fieldWidth, fieldHeight)

        val latSign =
            when {
                lateralVelocity > LATERAL_EPS -> 1
                lateralVelocity < -LATERAL_EPS -> -1
                else -> 0
            }
        if (latSign != 0) {
            if (lastLateralSign != 0 && latSign != lastLateralSign) timeSinceDirectionChange = 0L else timeSinceDirectionChange += step
            lastLateralSign = latSign
        } else {
            timeSinceDirectionChange += step
        }

        enemy = now
        self = Snapshot(time, selfX, selfY, selfHeadingDeg, selfVelocity, selfEnergy)
        lastScanTime = time
    }

    fun recordEnemyFire(power: Double) {
        if (power <= LOW_POWER_FIRE_MAX) ticksSinceLowPowerFire = 0L
    }

    private companion object {
        const val MAX_DT = 8L
        const val LATERAL_EPS = 0.5
        const val SHIELD_STATIONARY_SPEED = 0.2
        const val SHIELD_STATIONARY_TICKS = 8L
        const val LOW_POWER_FIRE_MAX = 0.35
        const val LOW_POWER_RECENT_TICKS = 30L
    }
}

/**
 * Infer enemy shots from energy. We can't see enemy bullets, but firing costs
 * energy. We keep an energy account isolating the fire-only drop:
 *   fire ≈ (prevEnergy − energy) − damageWeDealt + energyEnemyGained
 * A drop in the legal power range that respects the gun-heat cooldown is a shot.
 */
class FireDetector(
    private val coolingRate: Double,
) {
    private var prevEnergy = Double.NaN
    private var damageDealtPending = 0.0
    private var energyGainedPending = 0.0
    private var nextFireAllowedTime = Long.MIN_VALUE

    fun ourBulletHitEnemy(power: Double) {
        damageDealtPending += Rules.getBulletDamage(power)
    }

    fun enemyBulletHitUs(power: Double) {
        energyGainedPending += Rules.getBulletHitBonus(power)
    }

    fun detect(
        time: Long,
        enemyEnergy: Double,
    ): Double? {
        if (prevEnergy.isNaN()) {
            prevEnergy = enemyEnergy
            return null
        }
        val netLoss = prevEnergy - enemyEnergy
        val firePower = netLoss - damageDealtPending + energyGainedPending
        damageDealtPending = 0.0
        energyGainedPending = 0.0
        prevEnergy = enemyEnergy

        val legal = firePower >= Rules.MIN_BULLET_POWER - SLACK && firePower <= Rules.MAX_BULLET_POWER + SLACK
        if (legal && time >= nextFireAllowedTime) {
            val power = firePower.coerceIn(Rules.MIN_BULLET_POWER, Rules.MAX_BULLET_POWER)
            val cooldown = ceil((1.0 + power / 5.0) / coolingRate).toLong()
            nextFireAllowedTime = time + cooldown
            return power
        }
        return null
    }

    private companion object {
        const val SLACK = 0.01
    }
}
