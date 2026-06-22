package zen.mirage

/**
 * Bullet-shielding detector.
 *
 * A bullet-shielding opponent predicts our bullet's path and fires a cheap bullet
 * to collide with it (a [robocode.BulletHitBulletEvent]). When nearly every shot
 * we fire gets intercepted this way we never deal damage yet still pay the fire
 * power in energy, so the opponent out-waits us while we burn ourselves down.
 *
 * The detector keeps exponentially-decayed counts of our recent fires and of how
 * many were intercepted, and holds fire once the intercept ratio is high with
 * enough samples. Counts decay so the detector recovers if the opponent stops
 * shielding (e.g. it starts moving). [holdFire] also latches for a short cool-down
 * after triggering, so a single good intercept window doesn't flicker the gun.
 *
 * Call [onOurFire] for every real bullet we fire, [onIntercepted] for every
 * [robocode.BulletHitBulletEvent], and [tick] once per scan so the decay advances
 * in battle time rather than event time.
 */
class ShieldDetector {
    /** Decayed count of our recent real fires. */
    private var fires = 0.0

    /** Decayed count of our recent fires that were intercepted (BulletHitBullet). */
    private var intercepted = 0.0

    /** Ticks remaining in the hold-fire latch (counts down in [tick]). */
    private var holdTicks = 0L

    /** True while the latch is active — callers should hold fire. */
    val holdFire: Boolean
        get() = holdTicks > 0L

    /** Record one real bullet we fired this scan. */
    fun onOurFire() {
        fires += 1.0
    }

    /** Record one of our bullets being destroyed by an enemy bullet. */
    fun onIntercepted() {
        intercepted += 1.0
        if (fires >= MIN_FIRES && intercepted / fires >= INTERCEPT_RATIO) {
            holdTicks = HOLD_TICKS
        }
    }

    /** Advance the decay and latch one scan. Call once per tick. */
    fun tick() {
        fires *= DECAY
        intercepted *= DECAY
        if (holdTicks > 0L) holdTicks--
    }

    private companion object {
        /** Per-scan retention of the fire/intercept counts (≈ last ~30 ticks). */
        const val DECAY = 0.97

        /** Minimum recent fires before trusting the ratio (avoids early noise). */
        const val MIN_FIRES = 3.0

        /** Intercepted/fires ratio at which shielding is considered active. */
        const val INTERCEPT_RATIO = 0.6

        /** How long to keep holding fire once triggered (≈ one gun-cool cycle plus
         *  headroom, in ticks). */
        const val HOLD_TICKS = 25L
    }
}
