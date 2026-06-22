package zen.mirage

import robocode.ScannedRobotEvent

/**
 * Tracker — the enemy-fact and history layer. Each scan it pairs our own
 * [RobotState] with the enemy observation into a [Frame], derives the cross-tick
 * [EnemyDerived], and keeps a bounded newest-first history. It is a pure data
 * layer: it holds no robot and issues no movement.
 *
 * Beyond Mirage's paired frames it also exposes the field-dependent and stateful
 * facts the gun and surfer need: the enemy's forward wall room, the ticks since
 * its lateral direction last flipped, and the scan age.
 *
 * [EnemyDerived.lateralDirection] is kept "sticky": [EnemyDerived] only yields the
 * raw sign of the lateral velocity, which collapses to 0 whenever the enemy stops
 * or moves straight at or away from us. This layer remembers the last non-zero
 * direction and stamps it back, so consumers keep a stable orbit sense across
 * those moments.
 */
class Tracker(
    capacity: Int = DEFAULT_CAPACITY,
) {
    /** At least 2: deriving the cross-tick state needs current plus previous. */
    private val capacity = capacity.coerceAtLeast(2)

    private val history = ArrayDeque<Frame>() // index 0 = newest

    /** Last non-zero lateral direction, carried across straight-line moments. */
    private var lateralDirection = 0

    /** Ticks since the enemy's lateral direction last flipped (the oscillation
     *  phase a surf-style mover reverses on; a strong DC-gun key). */
    private var timeSinceDirectionChange = 0L

    private var lastLateralSign = 0
    private var lastScanTime = -1L

    /** Distance the enemy can travel along its current travel heading before a
     *  wall. */
    var forwardWallSpace = 0.0
        private set

    /** Ticks the enemy has been (near) stationary — a bullet-shielding signal:
     *  shielders sit still to fire intercept bullets along the center line. */
    var stationaryTicks = 0L
        private set

    /** Ticks since the enemy fired a low-power bullet — shielders fire cheap 0.1
     *  bullets to intercept ours. Recent low-power fire + stationary => likely
     *  shielding. */
    var ticksSinceLowPowerFire = LOW_POWER_RECENT_TICKS + 1L
        private set

    /** True when the enemy looks like a bullet-shielder: stationary and recently
     *  fired low-power. Drives anti-shield edge aiming in the gun. */
    val shieldLikely: Boolean
        get() = stationaryTicks >= SHIELD_STATIONARY_TICKS && ticksSinceLowPowerFire <= LOW_POWER_RECENT_TICKS

    /** Record that the enemy fired at [power] (from energy-drop fire detection).
     *  Low power resets the shield-fire clock. */
    fun recordEnemyFire(power: Double) {
        if (power <= LOW_POWER_FIRE_MAX) ticksSinceLowPowerFire = 0L
    }

    /** Frames newest-first; index N is N ticks of scans ago. Read-only view. */
    val frames: List<Frame>
        get() = history

    /** The latest frame, or null before the first scan. */
    val current: Frame?
        get() = history.firstOrNull()

    /** The frame before [current], or null with fewer than two scans. */
    val previous: Frame?
        get() = history.getOrNull(1)

    /** Ticks since the last scan, or a large value before the first scan. */
    fun scanAge(now: Long): Long = if (lastScanTime < 0L) Long.MAX_VALUE else now - lastScanTime

    /** Ticks since the enemy's lateral direction last flipped. */
    fun timeSinceDirectionChange(): Long = timeSinceDirectionChange

    /** Pair this scan with [self], derive the cross-tick state, and record it. */
    fun onScan(
        e: ScannedRobotEvent,
        self: RobotState,
        fieldWidth: Double,
        fieldHeight: Double,
    ): Frame {
        val enemy = EnemyState.from(e, self)
        val prevEnemy = history.firstOrNull()?.enemy
        val dt = if (prevEnemy != null) enemy.time - prevEnemy.time else 1L
        val trusted = prevEnemy != null && dt in 1L..MAX_DT
        val step = if (trusted) dt else 1L

        val rawDerived = prevEnemy?.let { EnemyDerived.from(enemy, it) }
        val derived =
            rawDerived?.let {
                if (it.lateralDirection != 0) lateralDirection = it.lateralDirection
                it.copy(lateralDirection = lateralDirection)
            }
        if (rawDerived != null) {
            val sign = rawDerived.lateralDirection
            if (sign != 0) {
                if (lastLateralSign != 0 && sign != lastLateralSign) timeSinceDirectionChange = 0L else timeSinceDirectionChange += step
                lastLateralSign = sign
            } else {
                timeSinceDirectionChange += step
            }
        }

        val travelHeading =
            if (enemy.velocity >= 0.0) enemy.headingRadians else Angles.normalizeAbsolute(enemy.headingRadians + Angles.PI)
        forwardWallSpace = distanceToWall(enemy.x, enemy.y, travelHeading, fieldWidth, fieldHeight)

        stationaryTicks = if (Math.abs(enemy.velocity) <= SHIELD_STATIONARY_SPEED) stationaryTicks + step else 0L
        ticksSinceLowPowerFire = (ticksSinceLowPowerFire + step).coerceAtMost(LOW_POWER_RECENT_TICKS + 1L)

        val frame = Frame(self, enemy, derived)
        history.addFirst(frame)
        while (history.size > capacity) history.removeLast()
        lastScanTime = enemy.time
        return frame
    }

    /** One tick paired: our state, the enemy observation, and the cross-tick
     *  derivation (null on the first scan, before there is a previous frame). */
    data class Frame(
        val self: RobotState,
        val enemy: EnemyState,
        val derived: EnemyDerived?,
    )

    private companion object {
        const val DEFAULT_CAPACITY = 5
        const val MAX_DT = 8L

        // Bullet-shield detection thresholds shared with the established tracker pattern.
        const val SHIELD_STATIONARY_SPEED = 0.2
        const val SHIELD_STATIONARY_TICKS = 8L
        const val LOW_POWER_FIRE_MAX = 0.35
        const val LOW_POWER_RECENT_TICKS = 30L
    }
}
