package zen.mirage

/**
 * Threat-aware "harvest" distance control (Plan A, `mirage.harvest`).
 *
 * Against a low-threat enemy (one that rarely hits us) the engagement range
 * steps down a ladder (350 -> 300 -> 260) to shorten kill time and the damage we
 * take while finishing. The ladder only descends while the threat tier stays
 * [Tier.LOW]; a heavy-hit round (damage taken > [BACKOFF_DAMAGE]) snaps back to
 * the baseline and locks the tier at [Tier.NORMAL] for [BACKOFF_LOCK_ROUNDS]
 * rounds — the same latch semantics as the movement flattener.
 *
 * Only the ladder/latch counters are stateful; they live in a per-enemy registry
 * ([forEnemy]) so they survive Robocode's per-round robot rebuild. The tier
 * inputs (enemy hit rate, waves observed) come from [ThreatStats]; this
 * controller does not hold that reference, callers pass the live signal.
 */
class HarvestController {
    private var stepIndex = 0
    private var roundsAtStep = 0L
    private var backoffLock = 0L

    enum class Tier {
        LOW,
        NORMAL,
    }

    fun tier(
        enemyHitRate: Double,
        wavesObserved: Long,
    ): Tier {
        if (backoffLock > 0) return Tier.NORMAL
        if (wavesObserved < minWaves()) return Tier.NORMAL
        return if (!enemyHitRate.isNaN() && enemyHitRate < maxLowRate()) Tier.LOW else Tier.NORMAL
    }

    /** LOW-tier engagement range at the current ladder step. */
    fun lowRange(): Double = LOW_LADDER[stepIndex.coerceIn(0, LOW_LADDER.lastIndex)]

    /** Advance the ladder and latch at round end. [enemyHitRate]/[wavesObserved]
     *  re-state the live signal so descent only happens on a genuinely LOW round. */
    fun recordRound(
        enemyHitRate: Double,
        wavesObserved: Long,
        damageTaken: Double,
    ) {
        when {
            backoffLock > 0 -> {
                backoffLock--
                resetLadder()
            }
            damageTaken > BACKOFF_DAMAGE -> {
                backoffLock = BACKOFF_LOCK_ROUNDS
                resetLadder()
            }
            tier(enemyHitRate, wavesObserved) == Tier.LOW -> {
                roundsAtStep++
                if (roundsAtStep >= STEP_HOLD_ROUNDS && stepIndex < LOW_LADDER.lastIndex) {
                    stepIndex++
                    roundsAtStep = 0L
                }
            }
            else -> resetLadder()
        }
    }

    private fun resetLadder() {
        stepIndex = 0
        roundsAtStep = 0L
    }

    companion object {
        const val BACKOFF_DAMAGE = 12.0
        const val BACKOFF_LOCK_ROUNDS = 3L
        const val STEP_HOLD_ROUNDS = 3L
        val LOW_LADDER = doubleArrayOf(350.0, 300.0, 260.0)

        /** Minimum resolved waves before the hit-rate signal is trusted. High
         *  enough to suppress cold-start flashes (a strong gun reads briefly low
         *  before its hit rate stabilizes). Tunable via mirage.harvestWaves. */
        fun minWaves(): Long = System.getProperty("mirage.harvestWaves")?.toLongOrNull()?.coerceAtLeast(1L) ?: DEFAULT_MIN_WAVES

        /** Enemy hit-rate ceiling for the LOW tier. Tunable via mirage.harvestRate. */
        fun maxLowRate(): Double = System.getProperty("mirage.harvestRate")?.toDoubleOrNull()?.coerceIn(0.0, 1.0) ?: DEFAULT_MAX_LOW_RATE

        private const val DEFAULT_MIN_WAVES = 60L
        private const val DEFAULT_MAX_LOW_RATE = 0.08

        private val perEnemy = HashMap<String, HarvestController>()

        fun forEnemy(name: String): HarvestController = perEnemy.getOrPut(name) { HarvestController() }
    }
}
