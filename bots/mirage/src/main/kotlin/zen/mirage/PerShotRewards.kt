package zen.mirage

import robocode.Bullet

/**
 * Per-shot reward bookkeeping shared by per-shot selectors (anti-shield edge aim,
 * and any future firepower selector): profile-indexed shot counts and summed
 * rewards, a pending queue keyed by the fired bullet's engine id, and a
 * prior-smoothed reward rate. The selection *policy* stays in each selector; this
 * only owns the common accounting.
 *
 * [Bullet.equals] compares Robocode's per-owner, per-round bullet id. Event
 * objects are reconstructed by the engine, so value equality (not reference
 * identity) is required to attribute an outcome to the exact fired shot. Two
 * in-flight shots of equal power still cannot be confused.
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

    /** Resolve shots that can no longer produce an event at the round boundary. */
    fun settlePending(rewardFor: (Bullet) -> Double) {
        for (shot in pending) {
            shots[shot.ordinal]++
            reward[shot.ordinal] += rewardFor(shot.bullet)
        }
        pending.clear()
    }

    fun onFire(
        ordinal: Int,
        bullet: Bullet,
    ) {
        pending += PendingShot(ordinal, bullet)
    }

    fun shotCount(ordinal: Int): Long = shots[ordinal]

    /** Resolved plus currently pending shots for balanced interleaved trials. */
    fun allocatedCount(ordinal: Int): Long = shots[ordinal] + pending.count { it.ordinal == ordinal }

    /** Attribute an outcome to the pending shot fired as [bullet]. */
    fun complete(
        bullet: Bullet,
        outcomeReward: Double,
    ) {
        val index = pending.indexOfFirst { it.bullet == bullet }
        if (index < 0) return
        val shot = pending.removeAt(index)
        shots[shot.ordinal]++
        reward[shot.ordinal] += outcomeReward
    }

    fun rewardPerShot(ordinal: Int): Double = (reward[ordinal] + priorWeight * priorReward) / (shots[ordinal] + priorWeight)
}
