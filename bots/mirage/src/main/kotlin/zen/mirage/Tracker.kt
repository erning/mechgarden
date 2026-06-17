package zen.mirage

import robocode.ScannedRobotEvent

/**
 * Tracker — the enemy-fact and history layer. Each scan it pairs our own
 * [RobotState] with the enemy observation into a [Frame], derives the
 * cross-tick [EnemyDerived], and keeps a bounded newest-first history. It is a
 * pure data layer: it holds no robot and issues no movement; it just turns
 * `(scan, self)` into frames. Aiming, movement, and wave/danger modelling read
 * these frames in their own layers.
 *
 * History depth is set at construction (default and minimum 2, since deriving the
 * cross-tick state needs the previous frame). [EnemyState] stays purely the enemy;
 * pairing it with our state lives here in [Frame].
 *
 * The [EnemyDerived.lateralDirection] is kept "sticky": [EnemyDerived] only yields
 * the raw sign of the lateral velocity, which collapses to 0 whenever the enemy
 * stops or moves straight at or away from us. This layer remembers the last
 * non-zero direction and stamps it back, so consumers keep a stable orbit sense
 * across those moments. It starts at 0 (unknown) until the enemy first moves
 * laterally.
 */
class Tracker(
    capacity: Int = 2,
) {
    /** At least 2: deriving the cross-tick state needs current plus previous. */
    private val capacity = capacity.coerceAtLeast(2)

    private val history = ArrayDeque<Frame>() // index 0 = newest

    /** Last non-zero lateral direction, carried across straight-line moments. */
    private var lateralDirection = 0

    /** Frames newest-first; index N is N ticks of scans ago. Read-only view. */
    val frames: List<Frame>
        get() = history

    /** The latest frame, or null before the first scan. */
    val current: Frame?
        get() = history.firstOrNull()

    /** The frame before [current], or null with fewer than two scans. */
    val previous: Frame?
        get() = history.getOrNull(1)

    /** Pair this scan with [self], derive the cross-tick state, and record it. */
    fun onScan(
        e: ScannedRobotEvent,
        self: RobotState,
    ): Frame {
        val enemy = EnemyState.from(e, self)
        val derived =
            history.firstOrNull()?.let { prev ->
                val raw = EnemyDerived.from(enemy, prev.enemy)
                if (raw.lateralDirection != 0) lateralDirection = raw.lateralDirection
                raw.copy(lateralDirection = lateralDirection)
            }
        val frame = Frame(self, enemy, derived)
        history.addFirst(frame)
        while (history.size > capacity) history.removeLast()
        return frame
    }

    /** One tick paired: our state, the enemy observation, and the cross-tick
     *  derivation (null on the first scan, before there is a previous frame). */
    data class Frame(
        val self: RobotState,
        val enemy: EnemyState,
        val derived: EnemyDerived?,
    )
}
