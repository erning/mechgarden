package zen.ronin

import robocode.Rules
import kotlin.math.abs

/**
 * Generic anti-shield aim selector.
 *
 * Bullet-shielding opponents tend to sit still and fire cheap bullets along the
 * center line. Center aim becomes easy to collide with, so under a generic
 * shield signal we aim at either body edge instead. The selector learns only
 * from real edge shots and stays per-enemy without hardcoding opponent names.
 */
class ShieldAimSelector {
    enum class Profile(
        val edgeSign: Int,
    ) {
        CENTER(0),
        EDGE_LEFT(-1),
        EDGE_RIGHT(+1),
    }

    private data class PendingShot(
        val profile: Profile,
        val power: Double,
    )

    private val shots = LongArray(Profile.values().size)
    private val reward = DoubleArray(Profile.values().size)
    private val pending = mutableListOf<PendingShot>()
    private var shieldSelections = 0L

    fun beginRound() {
        pending.clear()
    }

    fun select(shieldLikely: Boolean): Profile {
        if (!shieldLikely) return Profile.CENTER
        return explorationProfile() ?: bestEdgeProfile()
    }

    fun onFire(
        profile: Profile,
        power: Double,
    ) {
        if (profile == Profile.CENTER) return
        pending += PendingShot(profile, power)
        shieldSelections++
    }

    fun recordHit(power: Double) {
        complete(power, Rules.getBulletDamage(power) - power)
    }

    fun recordMiss(power: Double) {
        complete(power, -power)
    }

    fun recordHitBullet(power: Double) {
        complete(power, -power * HIT_BULLET_PENALTY)
    }

    private fun complete(
        power: Double,
        outcomeReward: Double,
    ) {
        val index = pending.indexOfFirst { abs(it.power - power) <= POWER_EPS }
        if (index < 0) return
        val shot = pending.removeAt(index)
        val profileIndex = shot.profile.ordinal
        shots[profileIndex]++
        reward[profileIndex] += outcomeReward
    }

    private fun explorationProfile(): Profile? {
        val start = (shieldSelections % EDGE_PROFILES.size).toInt()
        for (offset in EDGE_PROFILES.indices) {
            val profile = EDGE_PROFILES[(start + offset) % EDGE_PROFILES.size]
            if (shots[profile.ordinal] < EXPLORE_SHOTS) return profile
        }
        return null
    }

    private fun bestEdgeProfile(): Profile {
        var best = EDGE_PROFILES[0]
        var bestReward = rewardPerShot(best)
        for (profile in EDGE_PROFILES.drop(1)) {
            val candidate = rewardPerShot(profile)
            if (candidate > bestReward) {
                best = profile
                bestReward = candidate
            }
        }
        return best
    }

    private fun rewardPerShot(profile: Profile): Double {
        val index = profile.ordinal
        return (reward[index] + PRIOR_WEIGHT * PRIOR_REWARD) / (shots[index] + PRIOR_WEIGHT)
    }

    companion object {
        const val EXPLORE_SHOTS = 3L
        private const val PRIOR_REWARD = 0.0
        private const val PRIOR_WEIGHT = 2.0
        private const val HIT_BULLET_PENALTY = 1.6
        private const val POWER_EPS = 1e-9
        private val EDGE_PROFILES = arrayOf(Profile.EDGE_LEFT, Profile.EDGE_RIGHT)

        private val perEnemy = HashMap<String, ShieldAimSelector>()

        fun forEnemy(name: String): ShieldAimSelector = perEnemy.getOrPut(name) { ShieldAimSelector() }
    }
}
