package zen.ronin

import robocode.Bullet
import robocode.Rules

/**
 * Per-opponent, per-shot selector over firepower profiles.
 *
 * A fired bullet has a clear outcome: hit, miss, or bullet collision. That makes
 * firepower a good fit for per-shot learning. The selector only shapes the
 * expected-value power chosen by [Gun]; legal energy, reserve, and overkill caps
 * remain enforced by the gun.
 */
class FirePowerSelector {
    enum class Profile(
        val evScale: Double,
        val bias: Double,
        val floorScale: Double,
        val floorBonus: Double,
        val maxPower: Double,
    ) {
        BALANCED(
            evScale = 1.0,
            bias = 0.0,
            floorScale = 1.0,
            floorBonus = 0.0,
            maxPower = Rules.MAX_BULLET_POWER,
        ),
        ECONOMY(
            evScale = 0.75,
            bias = -0.05,
            floorScale = 0.45,
            floorBonus = 0.0,
            maxPower = 1.4,
        ),
        PRESSURE(
            evScale = 1.10,
            bias = 0.15,
            floorScale = 1.0,
            floorBonus = 0.2,
            maxPower = 2.4,
        ),
        AGGRESSIVE(
            evScale = 1.25,
            bias = 0.35,
            floorScale = 1.0,
            floorBonus = 0.6,
            maxPower = Rules.MAX_BULLET_POWER,
        ),
    }

    private val rewards = PerShotRewards(PROFILES.size, PRIOR_WEIGHT, PRIOR_REWARD)

    fun beginRound() {
        rewards.beginRound()
    }

    fun selectProfile(): Profile = explorationProfile() ?: bestProfile()

    fun apply(
        profile: Profile,
        evPower: Double,
        baseFloor: Double,
    ): Double {
        val maxPower = profile.maxPower.coerceIn(Rules.MIN_BULLET_POWER, Rules.MAX_BULLET_POWER)
        val floor = (baseFloor * profile.floorScale + profile.floorBonus).coerceIn(Rules.MIN_BULLET_POWER, maxPower)
        return (evPower * profile.evScale + profile.bias).coerceIn(floor, maxPower)
    }

    fun onFire(
        profile: Profile,
        bullet: Bullet,
    ) {
        rewards.onFire(profile.ordinal, bullet)
    }

    fun recordHit(bullet: Bullet) {
        rewards.complete(bullet, Rules.getBulletDamage(bullet.power) - bullet.power)
    }

    fun recordMiss(bullet: Bullet) {
        rewards.complete(bullet, -bullet.power)
    }

    fun recordHitBullet(bullet: Bullet) {
        rewards.complete(bullet, -bullet.power * HIT_BULLET_COST_SCALE)
    }

    private fun explorationProfile(): Profile? {
        for (profile in PROFILES) {
            if (rewards.shotCount(profile.ordinal) < EXPLORE_SHOTS) return profile
        }
        return null
    }

    private fun bestProfile(): Profile {
        var best = Profile.BALANCED
        var bestReward = rewards.rewardPerShot(best.ordinal)
        for (profile in PROFILES) {
            val candidate = rewards.rewardPerShot(profile.ordinal)
            if (candidate > bestReward) {
                best = profile
                bestReward = candidate
            }
        }
        return best
    }

    companion object {
        const val EXPLORE_SHOTS = 8L
        private const val PRIOR_REWARD = 0.0
        private const val PRIOR_WEIGHT = 4.0
        private const val HIT_BULLET_COST_SCALE = 0.35

        /** Cached enum array — avoids the per-call clone of Profile.values(). */
        private val PROFILES = Profile.values()
        private val perEnemy = HashMap<String, FirePowerSelector>()

        fun forEnemy(name: String): FirePowerSelector = perEnemy.getOrPut(name) { FirePowerSelector() }
    }
}
