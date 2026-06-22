package zen.mirage

/**
 * Per-enemy, round-level selector over compact movement profiles.
 *
 * Movement attribution is too noisy at a single-wave level, so Mirage commits to
 * one profile per round and scores it by the real bullet damage taken that round.
 * Lower damage per round is a good survival proxy: a lost round accumulates
 * roughly lethal damage (~100), a won round far less, so the average falls as the
 * win rate rises. The profiles blend the useful movement lessons from the
 * existing robots:
 *
 * - PURE_SURF: full-speed, no-flattener baseline (the safe default against strong
 *   guns; flattening was tried and rejected — it shoved us off genuinely-safe GFs
 *   onto rarely-visited but still-dangerous ones).
 * - FLAT_SURF: PURE_SURF plus a moderate visit flattener (counter to strong
 *   *adaptive* guns, e.g. SandboxDT, that lock onto our visit pattern; reached
 *   only via the survival policy's per-enemy selection so it is not forced against
 *   the simple guns flattening hurts).
 * - NOISY_ORBIT: a noisy orbit with a light visit flattener (beats flattenable
 *   adaptive movers).
 * - WALL_SAFE: a conservative wall-risk variant.
 * - STOP_SURF: a stop-enabled fallback (niche — only wins where stopping does).
 * - SURVIVAL_SEARCH: wider path/target search for high-hit-pressure guns.
 *
 * Selection is explore-then-exploit. Each profile is played [EXPLORE_ROUNDS]
 * times so it gets a damage estimate; thereafter the least-damaged profile is
 * exploited, with a Bayesian prior damping early single-round noise. Exploitation
 * is sticky: the incumbent is kept unless another profile is clearly better
 * ([SWITCH_MARGIN] with enough data), so the movement does not flap round to round
 * on noisy estimates. Bullet-shielders force PURE_SURF throughout — flattening or
 * stop-and-go hands a stationary center-line interceptor easy kills.
 *
 * The selector lives in a per-enemy static registry, so its accumulated damage
 * estimates survive Robocode's per-round robot rebuild and keep learning across a
 * battle's rounds.
 */
class MovementProfileSelector {
    enum class Profile(
        val stopAllowed: Boolean,
        val secondWaveDiscount: Double,
        val wallWeight: Double,
        val inertiaBonus: Double,
        val tieNoise: Double,
        val stopPenalty: Double,
        val flattenerWeight: Double,
    ) {
        PURE_SURF(
            stopAllowed = false,
            secondWaveDiscount = 0.45,
            wallWeight = 0.03,
            inertiaBonus = 0.004,
            tieNoise = 0.003,
            stopPenalty = 0.014,
            flattenerWeight = 0.0,
        ),
        FLAT_SURF(
            stopAllowed = false,
            secondWaveDiscount = 0.45,
            wallWeight = 0.03,
            inertiaBonus = 0.004,
            tieNoise = 0.003,
            stopPenalty = 0.014,
            flattenerWeight = FLAT_SURF_WEIGHT,
        ),
        NOISY_ORBIT(
            stopAllowed = false,
            secondWaveDiscount = 0.45,
            wallWeight = 0.03,
            inertiaBonus = 0.004,
            tieNoise = 0.02,
            stopPenalty = 0.014,
            flattenerWeight = 0.25,
        ),
        WALL_SAFE(
            stopAllowed = false,
            secondWaveDiscount = 0.45,
            wallWeight = 0.06,
            inertiaBonus = 0.003,
            tieNoise = 0.004,
            stopPenalty = 0.014,
            flattenerWeight = 0.15,
        ),
        STOP_SURF(
            stopAllowed = true,
            secondWaveDiscount = 0.45,
            wallWeight = 0.03,
            inertiaBonus = 0.004,
            tieNoise = 0.003,
            stopPenalty = 0.010,
            flattenerWeight = 0.0,
        ),
        SURVIVAL_SEARCH(
            stopAllowed = false,
            secondWaveDiscount = 0.70,
            wallWeight = 0.06,
            inertiaBonus = 0.002,
            tieNoise = 0.003,
            stopPenalty = 0.014,
            flattenerWeight = 0.0,
        ),
    }

    private val rounds = LongArray(PROFILES.size)
    private val damage = DoubleArray(PROFILES.size)
    private var roundsDecided = 0L
    private var shieldStyle = false

    /** Whether the explore phase has completed; flips the first round we exploit. */
    private var exploiting = false

    /** Exponential moving average of total bullet damage taken per round (all
     *  policies), and the hysteresis latch for the flattener trigger. Flattening
     *  helps against a gun that is hitting us hard — there are no genuinely-safe
     *  GFs to be shoved off of, so redistributing our visits disrupts an adaptive
     *  gun's lock — but hurts against weak guns we already dodge (it shoves us off
     *  the safe GFs onto still-dangerous ones). The trigger turns flattening on
     *  only while the enemy gun is sustained-accurate. See [flattenRecommended]. */
    private var recentDamageEma = Double.NaN
    private var flattenOn = false

    var chosen: Profile = Profile.PURE_SURF
        private set

    fun profileForRound(): Profile {
        // Profile selection. Three modes:
        //  - mirage.profile=<NAME>: force that profile for the whole battle (A/B
        //    tuning experiments).
        //  - mirage.profile=auto: re-enable the explore/exploit adaptation that
        //    samples profiles and exploits the least-damaged.
        //  - absent (normal play): the stable PURE_SURF default.
        // Live per-round exploration was measured to not improve and to slightly
        // disrupt adaptive wave-surfer duels, where a stable visit pattern can be
        // harder for an adaptive gun to lock onto than one that keeps changing.
        // The default therefore keeps movement constant. (PURE_SURF is also the
        // safe baseline against strong guns; flattening variants were tried and
        // rejected, see the class doc.)
        val key = System.getProperty("mirage.profile")?.trim()?.lowercase()
        val profile =
            when (key) {
                "auto" -> adaptiveProfile()
                null -> Profile.PURE_SURF
                else -> explicitProfile(key) ?: Profile.PURE_SURF
            }
        roundsDecided++
        chosen = profile
        return profile
    }

    /** Explore-then-exploit selection (mirage.profile=auto only): round-robin each
     *  active profile a few rounds, then exploit the least-damaged with sticky
     *  hysteresis so the movement does not flap. Bullet-shielders force PURE_SURF. */
    private fun adaptiveProfile(): Profile {
        if (shieldStyle) return Profile.PURE_SURF
        return explorationProfile() ?: run {
            if (!exploiting) exploiting = true
            bestProfile(sticky = exploiting && roundsDecided > ACTIVE.size * EXPLORE_ROUNDS)
        }
    }

    private fun explicitProfile(key: String): Profile? = PROFILES.firstOrNull { it.name.equals(key, ignoreCase = true) }

    fun markShieldStyle() {
        shieldStyle = true
    }

    fun recordDamage(damageTaken: Double) {
        val index = chosen.ordinal
        rounds[index]++
        damage[index] += damageTaken
        // Overall recent-damage EMA for the flattener trigger (independent of which
        //  profile was chosen). NaN until the first round; exponential smoothing so a
        //  single hot/cold round does not flip it.
        recentDamageEma =
            if (recentDamageEma.isNaN()) damageTaken else DAMAGE_EMA_RETAIN * recentDamageEma + (1.0 - DAMAGE_EMA_RETAIN) * damageTaken
        val armed = roundsDecided >= FLATTEN_ARM_ROUNDS
        if (armed && !flattenOn && recentDamageEma >= FLATTEN_ON_DAMAGE) flattenOn = true
        if (flattenOn && recentDamageEma <= FLATTEN_OFF_DAMAGE) flattenOn = false
    }

    /** Whether the visit-flattener movement ([Profile.FLAT_SURF]) should be active
     *  this round. Driven by the recent-damage latch: on against sustained-accurate
     *  guns (SandboxDT, Firestarter, …) where flattening disrupts their lock and
     *  there are no safe GFs to lose; off against weak guns we already dodge, where
     *  flattening would shove us off safe GFs. */
    fun flattenRecommended(): Boolean = flattenOn

    /** Round-robin the next under-explored active profile, or null once each has
     *  been played [EXPLORE_ROUNDS] times. Only the two fundamental strategies
     *  are explored live: the safe no-flattener orbit and the light-flattener
     *  orbit. The stop-enabled and extra-wall-cautious profiles stay available
     *  via the mirage.profile override but are too niche/risky to spend battle
     *  rounds sampling blind. */
    private fun explorationProfile(): Profile? {
        val start = (roundsDecided % ACTIVE.size).toInt()
        for (offset in ACTIVE.indices) {
            val profile = ACTIVE[(start + offset) % ACTIVE.size]
            if (rounds[profile.ordinal] < EXPLORE_ROUNDS) return profile
        }
        return null
    }

    /** Least-damage-per-round profile. When [sticky], the incumbent [chosen] wins
     *  ties and is only unseated by a clearly better (margin), well-sampled rival,
     *  so the movement does not flap round to round on noisy estimates. */
    private fun bestProfile(sticky: Boolean): Profile {
        if (!sticky) {
            var best = ACTIVE[0]
            var bestRate = damagePerRound(best)
            for (profile in ACTIVE) {
                val rate = damagePerRound(profile)
                if (rate < bestRate) {
                    best = profile
                    bestRate = rate
                }
            }
            return best
        }
        val incumbent = if (chosen in ACTIVE) chosen else ACTIVE[0]
        var best = incumbent
        var bestRate = damagePerRound(incumbent)
        for (profile in ACTIVE) {
            if (profile == incumbent) continue
            if (rounds[profile.ordinal] < MIN_SWITCH_ROUNDS) continue
            val rate = damagePerRound(profile)
            if (rate < bestRate - SWITCH_MARGIN) {
                best = profile
                bestRate = rate
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
        private const val MIN_SWITCH_ROUNDS = 3L
        private const val SWITCH_MARGIN = 4.0
        private const val DAMAGE_PRIOR = 30.0
        private const val DAMAGE_PRIOR_WEIGHT = 2.0

        /** Visit-flattener weight for the [Profile.FLAT_SURF] movement profile.
         *  A moderate flattener (validated A/B vs SandboxDT and Firestarter) pushes
         *  the surfer off frequently-visited guess factors, disrupting a strong
         *  adaptive gun's lock on our visit pattern and lowering damage taken.
         *  It is not a global default: flattening hurts simple guns (it shoves us
         *  off genuinely-safe GFs), so FLAT_SURF is reached only via the
         *  damage-latched [flattenRecommended] trigger, which turns it on only
         *  against sustained-accurate guns. Measured optimum around 0.35
         *  (0.2 weaker, 0.5+ over-flattens and regresses). */
        private const val FLAT_SURF_WEIGHT = 0.35

        /** Flattener-trigger tuning. The trigger arms after [FLATTEN_ARM_ROUNDS]
         *  rounds of damage data, latches ON when the recent-damage EMA crosses
         *  [FLATTEN_ON_DAMAGE], and latches OFF again below [FLATTEN_OFF_DAMAGE]
         *  (hysteresis, so it does not flap on a single hot/cold round). Tuned so a
         *  strong gun hitting ~46–50 dmg/round (SandboxDT, Firestarter) latches ON
         *  and stays on, while a weak gun we already dodge (~15–25, FloodMini /
         *  RaikoNano) never arms. */
        private const val FLATTEN_ARM_ROUNDS = 3L
        private const val FLATTEN_ON_DAMAGE = 40.0
        private const val FLATTEN_OFF_DAMAGE = 30.0
        private const val DAMAGE_EMA_RETAIN = 0.7

        private val PROFILES = Profile.values()

        /** Profiles the selector explores and compares in normal play. The two
         *  fundamentally different, both-safe strategies; the niche stop-enabled
         *  and extra-wall-cautious profiles stay reachable only via the override. */
        private val ACTIVE = arrayOf(Profile.PURE_SURF, Profile.NOISY_ORBIT)

        private val perEnemy = HashMap<String, MovementProfileSelector>()

        fun forEnemy(name: String): MovementProfileSelector = perEnemy.getOrPut(name) { MovementProfileSelector() }
    }
}
