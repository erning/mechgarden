package zen.mirage

/**
 * Generic survival-first policy selector.
 *
 * The selector does not know opponent names. It explores a small set of reusable
 * movement/firepower policies, records survival and damage outcomes, then
 * exploits the policy that has kept Mirage alive most reliably against the
 * current opponent behavior.
 */
class SurvivalPolicySelector {
    enum class Kind {
        ANTICIPATE_MAX,
        NO_STOP_MAX,
        CLOSE_PRESSURE,
        SURVIVAL_SEARCH,
        ANTICIPATE_WEIGHTED,
        LOW_EXPOSURE_MAX,
        NOISY_WEIGHTED,
    }

    data class Policy(
        val kind: Kind,
        val dangerMode: Surfer.DangerMode,
        val movementProfile: MovementProfileSelector.Profile?,
        val powerProfile: FirePowerSelector.Profile,
        val powerFloor: Double,
        val simulatedPriorWeight: Double,
        val virtualWaves: Boolean,
        val virtualLeadTicks: Long,
        val virtualPriorWeight: Double,
        val targetRangeOverride: Double?,
        val stopAllowed: Boolean?,
    )

    private class Stats {
        var rounds = 0L
        var survived = 0L
        var damageTaken = 0.0
        var damageDealt = 0.0
    }

    private val stats = Array(Kind.values().size) { Stats() }
    private var roundsSelected = 0L
    private var enemyFireCount = 0L
    private var enemyPowerTotal = 0.0
    private var enemyLowPowerCount = 0L

    fun policyForRound(): Policy {
        forcedPolicy()?.let { return it }

        roundsSelected++
        val defaultStats = stats[Kind.ANTICIPATE_MAX.ordinal]
        val fastProbe = highPressure(defaultStats)
        if (!fastProbe && (defaultStats.rounds < DEFAULT_PROBE_ROUNDS || defaultWorks(defaultStats))) {
            return policy(Kind.ANTICIPATE_MAX)
        }
        val order = explorationOrder()
        val probeRounds = if (fastProbe) FAST_EXPLORE_ROUNDS else EXPLORE_ROUNDS
        val underExplored = explorationPolicy(order, probeRounds)
        if (underExplored != null) return policy(underExplored)
        val best = bestPolicy(order)
        val defaultScore = score(Kind.ANTICIPATE_MAX)
        val margin = if (fastProbe) FAST_SWITCH_MARGIN else SWITCH_MARGIN
        return policy(if (score(best) > defaultScore + margin) best else Kind.ANTICIPATE_MAX)
    }

    fun recordEnemyFire(power: Double) {
        enemyFireCount++
        enemyPowerTotal += power
        if (power <= LOW_POWER_FIRE_MAX) enemyLowPowerCount++
    }

    fun recordRound(
        policy: Policy,
        survived: Boolean,
        damageTaken: Double,
        damageDealt: Double,
    ) {
        val s = stats[policy.kind.ordinal]
        s.rounds++
        if (survived) s.survived++
        s.damageTaken += damageTaken
        s.damageDealt += damageDealt
    }

    private fun explorationPolicy(
        order: Array<Kind>,
        probeRounds: Long,
    ): Kind? {
        for (kind in order) {
            if (kind == Kind.ANTICIPATE_MAX) continue
            if (stats[kind.ordinal].rounds < probeRounds) return kind
        }
        return null
    }

    private fun explorationOrder(): Array<Kind> =
        if (enemyFireCount >= BEHAVIOR_FIRE_SAMPLE && lowPowerFireRate() >= LOW_POWER_FIRE_RATE) {
            LOW_POWER_ORDER
        } else {
            DEFAULT_ORDER
        }

    private fun bestPolicy(order: Array<Kind>): Kind {
        var best = Kind.ANTICIPATE_MAX
        var bestScore = score(best)
        for (kind in order) {
            if (kind != Kind.ANTICIPATE_MAX && stats[kind.ordinal].rounds == 0L) continue
            val candidate = score(kind)
            if (candidate > bestScore) {
                best = kind
                bestScore = candidate
            }
        }
        return best
    }

    private fun score(kind: Kind): Double {
        val s = stats[kind.ordinal]
        val rounds = s.rounds + PRIOR_ROUNDS
        val survivalRate = (s.survived + PRIOR_SURVIVAL_RATE * PRIOR_ROUNDS) / rounds
        val damageRate = (s.damageTaken + PRIOR_DAMAGE_TAKEN * PRIOR_ROUNDS) / rounds
        val dealtRate = (s.damageDealt + PRIOR_DAMAGE_DEALT * PRIOR_ROUNDS) / rounds
        return survivalRate * SURVIVAL_WEIGHT - damageRate * DAMAGE_WEIGHT + dealtRate * DEALT_WEIGHT
    }

    private fun defaultWorks(stats: Stats): Boolean = survivalRate(stats) >= GOOD_SURVIVAL_RATE || damageRate(stats) <= GOOD_DAMAGE_RATE

    private fun survivalRate(stats: Stats): Double =
        if (stats.rounds == 0L) PRIOR_SURVIVAL_RATE else stats.survived.toDouble() / stats.rounds.toDouble()

    private fun damageRate(stats: Stats): Double =
        if (stats.rounds == 0L) PRIOR_DAMAGE_TAKEN else stats.damageTaken / stats.rounds.toDouble()

    private fun highPressure(stats: Stats): Boolean =
        stats.rounds >= FAST_PROBE_ROUNDS &&
            !defaultWorks(stats) &&
            (survivalRate(stats) <= FAST_SURVIVAL_RATE || damageRate(stats) >= FAST_DAMAGE_RATE)

    private fun lowPowerFireRate(): Double = if (enemyFireCount == 0L) 0.0 else enemyLowPowerCount.toDouble() / enemyFireCount.toDouble()

    private fun forcedPolicy(): Policy? {
        val key = System.getProperty("mirage.policy")?.trim()?.lowercase() ?: return null
        val kind =
            Kind.values().firstOrNull {
                it.name.equals(key, ignoreCase = true) ||
                    it.name.lowercase().replace("_", "-") == key
            } ?: return null
        return policy(kind)
    }

    private fun policy(kind: Kind): Policy =
        when (kind) {
            Kind.ANTICIPATE_MAX ->
                Policy(
                    kind = kind,
                    dangerMode = Surfer.DangerMode.MAX,
                    movementProfile = null,
                    powerProfile = FirePowerSelector.Profile.ECONOMY,
                    powerFloor = SURVIVAL_POWER_FLOOR,
                    simulatedPriorWeight = 0.0,
                    virtualWaves = true,
                    virtualLeadTicks = 1L,
                    virtualPriorWeight = 0.25,
                    targetRangeOverride = null,
                    stopAllowed = null,
                )
            Kind.NO_STOP_MAX ->
                Policy(
                    kind = kind,
                    dangerMode = Surfer.DangerMode.MAX,
                    movementProfile = null,
                    powerProfile = FirePowerSelector.Profile.ECONOMY,
                    powerFloor = SURVIVAL_POWER_FLOOR,
                    simulatedPriorWeight = 0.0,
                    virtualWaves = true,
                    virtualLeadTicks = 1L,
                    virtualPriorWeight = 0.25,
                    targetRangeOverride = null,
                    stopAllowed = false,
                )
            Kind.CLOSE_PRESSURE ->
                Policy(
                    kind = kind,
                    dangerMode = Surfer.DangerMode.MAX,
                    movementProfile = null,
                    powerProfile = FirePowerSelector.Profile.ECONOMY,
                    powerFloor = SURVIVAL_POWER_FLOOR,
                    simulatedPriorWeight = 0.0,
                    virtualWaves = true,
                    virtualLeadTicks = 1L,
                    virtualPriorWeight = 0.25,
                    targetRangeOverride = CLOSE_PRESSURE_RANGE,
                    stopAllowed = null,
                )
            Kind.SURVIVAL_SEARCH ->
                Policy(
                    kind = kind,
                    dangerMode = Surfer.DangerMode.MAX,
                    movementProfile = MovementProfileSelector.Profile.SURVIVAL_SEARCH,
                    powerProfile = FirePowerSelector.Profile.ECONOMY,
                    powerFloor = SURVIVAL_POWER_FLOOR,
                    simulatedPriorWeight = 0.0,
                    virtualWaves = true,
                    virtualLeadTicks = 1L,
                    virtualPriorWeight = 0.25,
                    targetRangeOverride = null,
                    stopAllowed = null,
                )
            Kind.ANTICIPATE_WEIGHTED ->
                Policy(
                    kind = kind,
                    dangerMode = Surfer.DangerMode.WEIGHTED,
                    movementProfile = null,
                    powerProfile = FirePowerSelector.Profile.ECONOMY,
                    powerFloor = SURVIVAL_POWER_FLOOR,
                    simulatedPriorWeight = 0.0,
                    virtualWaves = true,
                    virtualLeadTicks = 1L,
                    virtualPriorWeight = 0.25,
                    targetRangeOverride = null,
                    stopAllowed = null,
                )
            Kind.LOW_EXPOSURE_MAX ->
                Policy(
                    kind = kind,
                    dangerMode = Surfer.DangerMode.MAX,
                    movementProfile = null,
                    powerProfile = FirePowerSelector.Profile.ECONOMY,
                    powerFloor = SURVIVAL_POWER_FLOOR,
                    simulatedPriorWeight = 0.0,
                    virtualWaves = false,
                    virtualLeadTicks = 1L,
                    virtualPriorWeight = 0.0,
                    targetRangeOverride = null,
                    stopAllowed = null,
                )
            Kind.NOISY_WEIGHTED ->
                Policy(
                    kind = kind,
                    dangerMode = Surfer.DangerMode.WEIGHTED,
                    movementProfile = MovementProfileSelector.Profile.NOISY_ORBIT,
                    powerProfile = FirePowerSelector.Profile.ECONOMY,
                    powerFloor = SURVIVAL_POWER_FLOOR,
                    simulatedPriorWeight = 0.0,
                    virtualWaves = false,
                    virtualLeadTicks = 1L,
                    virtualPriorWeight = 0.0,
                    targetRangeOverride = null,
                    stopAllowed = null,
                )
        }

    companion object {
        val DEFAULT: Policy =
            Policy(
                kind = Kind.ANTICIPATE_MAX,
                dangerMode = Surfer.DangerMode.MAX,
                movementProfile = null,
                powerProfile = FirePowerSelector.Profile.ECONOMY,
                powerFloor = SURVIVAL_POWER_FLOOR,
                simulatedPriorWeight = 0.0,
                virtualWaves = true,
                virtualLeadTicks = 1L,
                virtualPriorWeight = 0.25,
                targetRangeOverride = null,
                stopAllowed = null,
            )

        private const val DEFAULT_PROBE_ROUNDS = 12L
        private const val FAST_PROBE_ROUNDS = 5L
        private const val EXPLORE_ROUNDS = 2L
        private const val FAST_EXPLORE_ROUNDS = 1L
        private const val PRIOR_ROUNDS = 3.0
        private const val PRIOR_SURVIVAL_RATE = 0.58
        private const val PRIOR_DAMAGE_TAKEN = 40.0
        private const val PRIOR_DAMAGE_DEALT = 40.0
        private const val GOOD_SURVIVAL_RATE = 0.62
        private const val GOOD_DAMAGE_RATE = 38.0
        private const val SWITCH_MARGIN = 8.0
        private const val FAST_SWITCH_MARGIN = 4.0
        private const val FAST_SURVIVAL_RATE = 0.50
        private const val FAST_DAMAGE_RATE = 45.0
        private const val SURVIVAL_POWER_FLOOR = 0.5
        private const val CLOSE_PRESSURE_RANGE = 380.0
        private const val SURVIVAL_WEIGHT = 180.0
        private const val DAMAGE_WEIGHT = 0.45
        private const val DEALT_WEIGHT = 0.04
        private const val BEHAVIOR_FIRE_SAMPLE = 4L
        private const val LOW_POWER_FIRE_MAX = 0.45
        private const val LOW_POWER_FIRE_RATE = 0.35

        private val DEFAULT_ORDER =
            arrayOf(
                Kind.ANTICIPATE_MAX,
                Kind.NOISY_WEIGHTED,
                Kind.ANTICIPATE_WEIGHTED,
                Kind.LOW_EXPOSURE_MAX,
            )

        private val LOW_POWER_ORDER =
            arrayOf(
                Kind.LOW_EXPOSURE_MAX,
                Kind.NOISY_WEIGHTED,
                Kind.ANTICIPATE_MAX,
                Kind.ANTICIPATE_WEIGHTED,
            )

        private val perEnemy = HashMap<String, SurvivalPolicySelector>()

        fun forEnemy(name: String): SurvivalPolicySelector = perEnemy.getOrPut(name) { SurvivalPolicySelector() }
    }
}
