package zen.fencer

/**
 * How much to trust what we know about the enemy, as a function of **staleness**
 * (ticks since the last scan). The longer we go without a fresh scan, the more
 * the enemy could have moved, so downstream layers back off:
 *
 * - radar: [shouldReacquire] → sweep to re-find the enemy;
 * - fire gate: [tooStaleToFire] → hold fire on a stale fix.
 *
 * Pure functions of `scanAge` — no state.
 */
object Uncertainty {
    /** Lock dropped a beat — the radar should sweep to re-acquire. */
    fun shouldReacquire(scanAge: Long): Boolean = scanAge > REACQUIRE_TICKS

    /** Too stale to aim reliably — a fire gate should hold fire. */
    fun tooStaleToFire(scanAge: Long): Boolean = scanAge > FIRE_STALE_TICKS

    private const val REACQUIRE_TICKS = 3L
    private const val FIRE_STALE_TICKS = 6L
}
