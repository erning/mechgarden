package zen.ronin

/**
 * Per-opponent, round-level selector over compact movement profiles.
 *
 * Movement attribution is noisy at a single-tick or single-wave level, so this
 * selector chooses one profile per round and scores it by real bullet damage
 * taken. The state is per-enemy and only lives in the current battle/JVM.
 */
class MovementProfileSelector {
    enum class Profile(
        val stopAllowed: Boolean,
        val secondWaveDiscount: Double,
        val wallWeight: Double,
        val inertiaBonus: Double,
        val tieNoise: Double,
        val stopPenalty: Double,
    ) {
        BASE(
            stopAllowed = true,
            secondWaveDiscount = 0.45,
            wallWeight = 0.03,
            inertiaBonus = 0.004,
            tieNoise = 0.003,
            stopPenalty = 0.01,
        ),
        ORBIT_ONLY(
            stopAllowed = false,
            secondWaveDiscount = 0.45,
            wallWeight = 0.03,
            inertiaBonus = 0.004,
            tieNoise = 0.003,
            stopPenalty = 0.01,
        ),
        WALL_SAFE(
            stopAllowed = true,
            secondWaveDiscount = 0.45,
            wallWeight = 0.06,
            inertiaBonus = 0.003,
            tieNoise = 0.003,
            stopPenalty = 0.012,
        ),
        SECOND_WAVE(
            stopAllowed = true,
            secondWaveDiscount = 0.70,
            wallWeight = 0.03,
            inertiaBonus = 0.002,
            tieNoise = 0.003,
            stopPenalty = 0.014,
        ),
    }

    private val rounds = LongArray(PROFILES.size)
    private val damage = DoubleArray(PROFILES.size)
    private var roundsDecided = 0L

    var chosen: Profile = Profile.BASE
        private set

    fun profileForRound(): Profile {
        val profile = explorationProfile() ?: bestProfile()
        roundsDecided++
        chosen = profile
        return profile
    }

    fun recordDamage(damageTaken: Double) {
        val index = chosen.ordinal
        rounds[index]++
        damage[index] += damageTaken
    }

    private fun explorationProfile(): Profile? {
        val start = (roundsDecided % PROFILES.size).toInt()
        for (offset in PROFILES.indices) {
            val profile = PROFILES[(start + offset) % PROFILES.size]
            if (rounds[profile.ordinal] < EXPLORE_ROUNDS) return profile
        }
        return null
    }

    private fun bestProfile(): Profile {
        var best = Profile.BASE
        var bestDamage = damagePerRound(best)
        for (profile in PROFILES) {
            val candidate = damagePerRound(profile)
            if (candidate < bestDamage) {
                best = profile
                bestDamage = candidate
            }
        }
        return best
    }

    private fun damagePerRound(profile: Profile): Double {
        val index = profile.ordinal
        return (damage[index] + DAMAGE_PRIOR_WEIGHT * DAMAGE_PRIOR) / (rounds[index] + DAMAGE_PRIOR_WEIGHT)
    }

    companion object {
        const val EXPLORE_ROUNDS = 2L
        private const val DAMAGE_PRIOR = 30.0
        private const val DAMAGE_PRIOR_WEIGHT = 2.0

        /** Cached enum array — avoids the per-call clone of Profile.values(). */
        private val PROFILES = Profile.values()
        private val perEnemy = HashMap<String, MovementProfileSelector>()

        fun forEnemy(name: String): MovementProfileSelector = perEnemy.getOrPut(name) { MovementProfileSelector() }
    }
}
