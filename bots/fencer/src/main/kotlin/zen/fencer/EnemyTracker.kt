package zen.fencer

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Foundation: turn delayed, discrete radar
 * scans into **time-consistent, physics-correct facts** about the enemy.
 *
 * On each scan it derives the enemy's absolute position from our position + the
 * absolute bearing + distance (the coordinate transform) and computes a set of
 * **derived facts** — turn rate, lateral/advancing
 * velocity, acceleration, time-since-velocity/direction-change, nearest-wall
 * distance, and forward travel space — all with **proper Δt handling** (a missed
 * scan divides by / advances by the real tick gap, not 1).
 *
 * Facts and derived facts only. Event inference, prediction and statistics are
 * later layers built on top of these.
 */
class EnemyTracker {
    /** Latest enemy / our state, captured at the same tick. Null until first scan. */
    var enemy: Snapshot? = null
        private set
    var self: Snapshot? = null
        private set
    var lastScanTime: Long = -1L
        private set

    // --- Derived facts, relative to the latest scan (valid after the 2nd scan;
    //     cleared / held when the inter-scan gap is too large to trust). ---

    /** Signed body turn rate, degrees/tick (clockwise +). */
    var turnRateDegPerTick: Double = 0.0
        private set

    /** Enemy speed across our line of sight — drives aiming offset. */
    var lateralVelocity: Double = 0.0
        private set

    /** Enemy speed toward us (+ = closing) — drives distance / bullet flight time. */
    var advancingVelocity: Double = 0.0
        private set

    /** Change in the enemy's *speed* per tick (+ = speeding up, − = braking). */
    var acceleration: Double = 0.0
        private set

    /** Ticks the enemy has held (nearly) constant speed. */
    var timeSinceVelocityChange: Long = 0L
        private set

    /** Ticks since the enemy's lateral direction (orbit sense around us) flipped. */
    var timeSinceDirectionChange: Long = 0L
        private set

    /** Bearing from us to the enemy, degrees clockwise from north. */
    var absoluteBearingDeg: Double = 0.0
        private set
    var distance: Double = 0.0
        private set

    /** Enemy's distance to the nearest wall, pixels. */
    var wallDistance: Double = 0.0
        private set

    /** Distance the enemy can travel along its current motion before a wall. */
    var forwardWallSpace: Double = 0.0
        private set

    private var lastLateralSign: Int = 0

    /** Ticks since the last scan (huge if never scanned) — a cheap staleness/uncertainty proxy. */
    fun scanAge(now: Long): Long = if (lastScanTime < 0L) Long.MAX_VALUE else now - lastScanTime

    /**
     * Ingest one scan. `self*` are our state this tick; the enemy is given as the
     * engine reports it (absolute bearing + distance + its heading/velocity/energy),
     * and we transform it into an absolute [Snapshot]. [fieldWidth]/[fieldHeight]
     * size the arena for the wall-distance facts.
     */
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
        // Coordinate transform: enemy absolute position from our position + bearing.
        val bearingRad = Math.toRadians(absoluteBearingDeg)
        val ex = selfX + sin(bearingRad) * enemyDistance
        val ey = selfY + cos(bearingRad) * enemyDistance
        val now = Snapshot(time, ex, ey, enemyHeadingDeg, enemyVelocity, enemyEnergy)

        val prev = enemy
        val dt = if (prev != null) time - prev.time else 0L
        val trusted = prev != null && dt in 1L..MAX_DT
        val step = if (trusted) dt else 1L

        // Turn rate and acceleration (both per-tick, Δt-aware).
        if (trusted) {
            turnRateDegPerTick = Angles.normalizeRelative(enemyHeadingDeg - prev.headingDeg) / dt
            acceleration = (abs(enemyVelocity) - abs(prev.velocity)) / dt
        } else if (dt > MAX_DT) {
            turnRateDegPerTick = 0.0
            acceleration = 0.0
        }

        // Relative components at this tick's line of sight (same time slice).
        val rel = Math.toRadians(enemyHeadingDeg - absoluteBearingDeg)
        lateralVelocity = enemyVelocity * sin(rel)
        advancingVelocity = -enemyVelocity * cos(rel)
        this.absoluteBearingDeg = absoluteBearingDeg
        this.distance = enemyDistance

        // Time-since-event counters (advance by the real gap; reset on the event).
        if (abs(acceleration) > ACCEL_EPS) timeSinceVelocityChange = 0L else timeSinceVelocityChange += step
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

        // Wall facts (need the arena size).
        wallDistance = minOf(ex, fieldWidth - ex, ey, fieldHeight - ey).coerceAtLeast(0.0)
        val travelHeading = if (enemyVelocity >= 0.0) enemyHeadingDeg else enemyHeadingDeg + 180.0
        forwardWallSpace = distanceToWall(ex, ey, travelHeading, fieldWidth, fieldHeight)

        val selfSnap = Snapshot(time, selfX, selfY, selfHeadingDeg, selfVelocity, selfEnergy)
        enemy = now
        self = selfSnap
        lastScanTime = time
    }

    /** Distance from (x, y) along [headingDeg] to the first arena wall, pixels. */
    private fun distanceToWall(
        x: Double,
        y: Double,
        headingDeg: Double,
        fieldWidth: Double,
        fieldHeight: Double,
    ): Double {
        val rad = Math.toRadians(headingDeg)
        val dx = sin(rad)
        val dy = cos(rad)
        var dist = Double.MAX_VALUE
        if (dx > EPS) dist = minOf(dist, (fieldWidth - x) / dx)
        if (dx < -EPS) dist = minOf(dist, -x / dx)
        if (dy > EPS) dist = minOf(dist, (fieldHeight - y) / dy)
        if (dy < -EPS) dist = minOf(dist, -y / dy)
        return if (dist == Double.MAX_VALUE) 0.0 else dist.coerceAtLeast(0.0)
    }

    private companion object {
        /** Beyond this many ticks between scans, a derived rate isn't trustworthy. */
        const val MAX_DT = 8L

        /** Speed change above this counts as the enemy changing velocity. */
        const val ACCEL_EPS = 0.4

        /** Lateral speed magnitude above this gives a meaningful orbit-direction sign. */
        const val LATERAL_EPS = 0.5

        const val EPS = 1e-9
    }
}
