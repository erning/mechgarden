package zen.ronin

import robocode.Rules
import kotlin.math.abs

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

    private data class PendingShot(
        val profile: Profile,
        val power: Double,
    )

    private val shots = LongArray(Profile.values().size)
    private val reward = DoubleArray(Profile.values().size)
    private val pending = mutableListOf<PendingShot>()

    fun beginRound() {
        pending.clear()
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
        power: Double,
    ) {
        pending += PendingShot(profile, power)
    }

    fun recordHit(power: Double) {
        complete(power, Rules.getBulletDamage(power) - power)
    }

    fun recordMiss(power: Double) {
        complete(power, -power)
    }

    fun recordHitBullet(power: Double) {
        complete(power, -power * HIT_BULLET_COST_SCALE)
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
        for (profile in Profile.values()) {
            if (shots[profile.ordinal] < EXPLORE_SHOTS) return profile
        }
        return null
    }

    private fun bestProfile(): Profile {
        var best = Profile.BALANCED
        var bestReward = rewardPerShot(best)
        for (profile in Profile.values()) {
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
        const val EXPLORE_SHOTS = 8L
        private const val PRIOR_REWARD = 0.0
        private const val PRIOR_WEIGHT = 4.0
        private const val HIT_BULLET_COST_SCALE = 0.35
        private const val POWER_EPS = 1e-9

        private val perEnemy = HashMap<String, FirePowerSelector>()

        fun forEnemy(name: String): FirePowerSelector = perEnemy.getOrPut(name) { FirePowerSelector() }
    }
}
