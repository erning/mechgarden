package zen.mirage

import robocode.Bullet

/**
 * Per-shot reward bookkeeping shared by per-shot selectors (anti-shield edge aim,
 * and any future firepower selector): profile-indexed shot counts and summed
 * rewards, a pending queue keyed by the fired bullet's identity, and a
 * prior-smoothed reward rate. The selection *policy* stays in each selector; this
 * only owns the common accounting.
 *
 * Keying by the [Bullet] instance (not its power) attributes each outcome to the
 * exact shot that produced it — two in-flight shots of equal power can no longer
 * be confused. Ported to Mirage from the established workspace pattern.
 */
class PerShotRewards(
    size: Int,
    private val priorWeight: Double,
    private val priorReward: Double,
) {
    private class PendingShot(
        val ordinal: Int,
        val bullet: Bullet,
    )

    private val shots = LongArray(size)
    private val reward = DoubleArray(size)
    private val pending = mutableListOf<PendingShot>()

    fun beginRound() {
        pending.clear()
    }

    fun onFire(
        ordinal: Int,
        bullet: Bullet,
    ) {
        pending += PendingShot(ordinal, bullet)
    }

    fun shotCount(ordinal: Int): Long = shots[ordinal]

    /** Attribute an outcome to the pending shot fired as [bullet]. */
    fun complete(
        bullet: Bullet,
        outcomeReward: Double,
    ) {
        val index = pending.indexOfFirst { it.bullet === bullet }
        if (index < 0) return
        val shot = pending.removeAt(index)
        shots[shot.ordinal]++
        reward[shot.ordinal] += outcomeReward
    }

    fun rewardPerShot(ordinal: Int): Double = (reward[ordinal] + priorWeight * priorReward) / (shots[ordinal] + priorWeight)
}
