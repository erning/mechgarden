package zen.mirage

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

    data class Selection(
        val profile: Profile,
        val adaptive: Boolean,
    )

    fun beginRound() {
        // Bullets left in flight when a round ended already spent their energy
        // but can no longer score. Charging that cost avoids favoring BALANCED
        // merely because its slower, higher-power bullets are censored more often.
        rewards.settlePending { -it.power }
    }

    fun select(
        defaultProfile: Profile = Profile.BALANCED,
        adaptiveEnabled: Boolean = false,
    ): Selection {
        // Firepower profile selection. Three modes (mirage.power system property):
        //  - <NAME>: force that profile (e.g. economy, aggressive) for A/B tuning.
        //  - auto: force explore/exploit adaptation for A/B tuning.
        //  - absent: adapt only when the caller's cross-round safety gate allows it.
        // Unknown values fail fast so a misspelled A/B run cannot silently produce
        // measurements for a different profile.
        // Non-adaptive shots are deliberately excluded from the reward model, so
        // cold-start ECONOMY rounds cannot bias a later score-pressure trial.
        val key = System.getProperty("mirage.power")?.trim()?.lowercase()
        return when (key) {
            "auto" -> adaptiveSelection()
            null -> if (adaptiveEnabled) adaptiveSelection() else Selection(defaultProfile, adaptive = false)
            "policy" -> Selection(defaultProfile, adaptive = false)
            else -> {
                val profile =
                    requireNotNull(PROFILES.firstOrNull { it.name.equals(key, ignoreCase = true) }) {
                        "Unknown mirage.power profile: $key"
                    }
                Selection(profile, adaptive = false)
            }
        }
    }

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
        selection: Selection,
        bullet: Bullet,
    ) {
        if (selection.adaptive) rewards.onFire(selection.profile.ordinal, bullet)
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
        var selected: Profile? = null
        var selectedAllocation = Long.MAX_VALUE
        for (profile in ACTIVE) {
            if (rewards.shotCount(profile.ordinal) >= EXPLORE_SHOTS) continue
            val allocation = rewards.allocatedCount(profile.ordinal)
            if (allocation < selectedAllocation) {
                selected = profile
                selectedAllocation = allocation
            }
        }
        return selected
    }

    private fun adaptiveSelection(): Selection = Selection(explorationProfile() ?: bestProfile(), adaptive = true)

    private fun bestProfile(): Profile {
        var best = ACTIVE[0]
        var bestReward = rewards.rewardPerShot(best.ordinal)
        for (profile in ACTIVE) {
            val candidate = rewards.rewardPerShot(profile.ordinal)
            if (candidate > bestReward) {
                best = profile
                bestReward = candidate
            }
        }
        return best
    }

    internal fun resolvedShots(profile: Profile): Long = rewards.shotCount(profile.ordinal)

    companion object {
        const val EXPLORE_SHOTS = 16L
        private const val PRIOR_REWARD = 0.0
        private const val PRIOR_WEIGHT = 4.0
        private const val HIT_BULLET_COST_SCALE = 0.35

        /** Cached enum array — avoids the per-call clone of Profile.values(). */
        private val PROFILES = Profile.values()

        /** Profiles the selector explores and compares in score-pressure play.
         *  BALANCED keeps the tuned power floor; ECONOMY drops to fast cheap bullets, which net
         *  more energy against hard-to-hit movers (smaller escape angle → more
         *  hits, less power wasted on misses). The high-pressure profiles stay
         *  reachable only through explicit mirage.power overrides since very high
         *  power is rarely EV-optimal against a competent dodger. */
        private val ACTIVE = arrayOf(Profile.BALANCED, Profile.ECONOMY)

        private val perEnemy = HashMap<String, FirePowerSelector>()

        fun forEnemy(name: String): FirePowerSelector = perEnemy.getOrPut(name) { FirePowerSelector() }
    }
}
