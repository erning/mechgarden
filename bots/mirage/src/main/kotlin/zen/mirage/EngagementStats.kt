package zen.mirage

import kotlin.math.min

/**
 * Round-local close-contact diagnostics.
 *
 * The existing dealt/taken counters intentionally describe bullet damage. This
 * object keeps ramming and positioning evidence separate so anti-ram changes can
 * be evaluated without changing those established metrics.
 */
class EngagementStats {
    private var lastScanTime: Long? = null
    private var collisionCount = 0L
    private var atFaultCollisionCount = 0L
    private var closestDistance = Double.POSITIVE_INFINITY
    private var closestWallSpace = Double.POSITIVE_INFINITY
    private var ticksUnder100 = 0L
    private var ticksUnder150 = 0L
    private var peakClosingSpeed = 0.0
    private var enemyShots = 0L
    private var enemyPower = 0.0

    fun recordScan(
        time: Long,
        distance: Double,
        closingSpeed: Double,
        wallSpace: Double,
    ) {
        val previousTime = lastScanTime
        val observedTicks =
            if (previousTime == null) {
                1L
            } else {
                (time - previousTime).coerceIn(1L, MAX_SCAN_GAP_TICKS)
            }
        lastScanTime = time

        if (distance.isFinite() && distance >= 0.0) {
            closestDistance = min(closestDistance, distance)
            if (distance < CLOSE_DISTANCE_100) ticksUnder100 += observedTicks
            if (distance < CLOSE_DISTANCE_150) ticksUnder150 += observedTicks
        }
        if (wallSpace.isFinite()) closestWallSpace = min(closestWallSpace, wallSpace.coerceAtLeast(0.0))
        if (closingSpeed.isFinite()) peakClosingSpeed = maxOf(peakClosingSpeed, closingSpeed.coerceAtLeast(0.0))
    }

    fun recordCollision(atFault: Boolean) {
        collisionCount++
        if (atFault) atFaultCollisionCount++
    }

    fun recordEnemyFire(power: Double) {
        if (!power.isFinite() || power <= 0.0) return
        enemyShots++
        enemyPower += power
    }

    fun averageEnemyFirePower(): Double = if (enemyShots == 0L) Double.NaN else enemyPower / enemyShots

    fun collisions(): Long = collisionCount

    fun atFaultCollisions(): Long = atFaultCollisionCount

    fun minDistance(): Double = closestDistance

    fun minWallSpace(): Double = closestWallSpace

    fun closeTicks100(): Long = ticksUnder100

    fun closeTicks150(): Long = ticksUnder150

    fun maxClosingSpeed(): Double = peakClosingSpeed

    fun debugSummary(): String =
        "ram=$collisionCount/$atFaultCollisionCount " +
            "ramTaken=${format(collisionCount * RAM_ENERGY_LOSS)} " +
            "minDist=${format(closestDistance)} close=$ticksUnder100/$ticksUnder150 " +
            "wall=${format(closestWallSpace)} closing=${format(peakClosingSpeed)} " +
            "enemyPower=${format(averageEnemyFirePower())}@$enemyShots"

    fun reset() {
        lastScanTime = null
        collisionCount = 0L
        atFaultCollisionCount = 0L
        closestDistance = Double.POSITIVE_INFINITY
        closestWallSpace = Double.POSITIVE_INFINITY
        ticksUnder100 = 0L
        ticksUnder150 = 0L
        peakClosingSpeed = 0.0
        enemyShots = 0L
        enemyPower = 0.0
    }

    private fun format(value: Double): String = if (value.isFinite()) "%.1f".format(value) else "n/a"

    private companion object {
        const val MAX_SCAN_GAP_TICKS = 8L
        const val CLOSE_DISTANCE_100 = 100.0
        const val CLOSE_DISTANCE_150 = 150.0
        const val RAM_ENERGY_LOSS = 0.6
    }
}
