package zen.ronin

import kotlin.math.abs

/**
 * Per-shot reward bookkeeping shared by the firepower and shield-aim selectors:
 * profile-indexed shot counts and summed rewards, a pending queue matched back to
 * its profile by bullet power, and the prior-smoothed reward rate. The selection
 * *policy* (which profiles to explore, how to score an outcome) stays in each
 * selector; this only owns the common accounting.
 */
class PerShotRewards(
    size: Int,
    private val priorWeight: Double,
    private val priorReward: Double,
) {
    private class PendingShot(
        val ordinal: Int,
        val power: Double,
    )

    private val shots = LongArray(size)
    private val reward = DoubleArray(size)
    private val pending = mutableListOf<PendingShot>()

    fun beginRound() {
        pending.clear()
    }

    fun onFire(
        ordinal: Int,
        power: Double,
    ) {
        pending += PendingShot(ordinal, power)
    }

    fun shotCount(ordinal: Int): Long = shots[ordinal]

    /** Attribute an outcome to the oldest pending shot at [power]. */
    fun complete(
        power: Double,
        outcomeReward: Double,
    ) {
        val index = pending.indexOfFirst { abs(it.power - power) <= POWER_EPS }
        if (index < 0) return
        val shot = pending.removeAt(index)
        shots[shot.ordinal]++
        reward[shot.ordinal] += outcomeReward
    }

    fun rewardPerShot(ordinal: Int): Double = (reward[ordinal] + priorWeight * priorReward) / (shots[ordinal] + priorWeight)

    private companion object {
        const val POWER_EPS = 1e-9
    }
}
