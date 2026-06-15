package zen.ronin

import robocode.Rules

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

    private val rewards = PerShotRewards(Profile.values().size, PRIOR_WEIGHT, PRIOR_REWARD)
    private var shieldSelections = 0L

    fun beginRound() {
        rewards.beginRound()
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
        rewards.onFire(profile.ordinal, power)
        shieldSelections++
    }

    fun recordHit(power: Double) {
        rewards.complete(power, Rules.getBulletDamage(power) - power)
    }

    fun recordMiss(power: Double) {
        rewards.complete(power, -power)
    }

    fun recordHitBullet(power: Double) {
        rewards.complete(power, -power * HIT_BULLET_PENALTY)
    }

    private fun explorationProfile(): Profile? {
        val start = (shieldSelections % EDGE_PROFILES.size).toInt()
        for (offset in EDGE_PROFILES.indices) {
            val profile = EDGE_PROFILES[(start + offset) % EDGE_PROFILES.size]
            if (rewards.shotCount(profile.ordinal) < EXPLORE_SHOTS) return profile
        }
        return null
    }

    private fun bestEdgeProfile(): Profile {
        var best = EDGE_PROFILES[0]
        var bestReward = rewards.rewardPerShot(best.ordinal)
        for (profile in EDGE_PROFILES.drop(1)) {
            val candidate = rewards.rewardPerShot(profile.ordinal)
            if (candidate > bestReward) {
                best = profile
                bestReward = candidate
            }
        }
        return best
    }

    companion object {
        const val EXPLORE_SHOTS = 3L
        private const val PRIOR_REWARD = 0.0
        private const val PRIOR_WEIGHT = 2.0
        private const val HIT_BULLET_PENALTY = 1.6
        private val EDGE_PROFILES = arrayOf(Profile.EDGE_LEFT, Profile.EDGE_RIGHT)

        private val perEnemy = HashMap<String, ShieldAimSelector>()

        fun forEnemy(name: String): ShieldAimSelector = perEnemy.getOrPut(name) { ShieldAimSelector() }
    }
}
