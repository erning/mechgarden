package zen.mirage

/**
 * Cross-round safety gate for score-oriented firepower adaptation.
 *
 * Mirage's survival policy deliberately defaults to ECONOMY. Once the current
 * opponent has demonstrated that Mirage already survives reliably and takes
 * little bullet damage, this controller allows the gun to compare ECONOMY with
 * BALANCED and convert that survival margin into bullet damage and kill bonus.
 * A death or heavy-damage round immediately schedules a short backoff.
 *
 * Instances live in a per-enemy registry so evidence survives Robocode's
 * per-round robot rebuild. The decision is frozen by [beginRound] for one round;
 * live energy and anti-ram emergency overrides remain owned by [Mirage].
 */
class ScorePressureController {
    private var completedRounds = 0L
    private var survivedRounds = 0L
    private var totalDamageTaken = 0.0
    private var backoffRounds = 0L
    private var activeForRound = false

    fun beginRound(): Boolean {
        activeForRound =
            if (backoffRounds > 0L) {
                backoffRounds--
                false
            } else {
                eligible()
            }
        return activeForRound
    }

    fun recordRound(
        survived: Boolean,
        damageTaken: Double,
    ) {
        completedRounds++
        if (survived) survivedRounds++
        val safeDamage = damageTaken.coerceAtLeast(0.0)
        totalDamageTaken += safeDamage
        if (!survived || safeDamage >= HEAVY_DAMAGE_BACKOFF) {
            backoffRounds = BACKOFF_ROUNDS
        }
    }

    fun debugSummary(): String =
        "scorePower=${if (activeForRound) "auto" else "economy"}" +
            "@$survivedRounds/$completedRounds" +
            "/${"%.1f".format(averageDamageTaken())}/backoff:$backoffRounds"

    private fun eligible(): Boolean =
        completedRounds >= MIN_COMPLETED_ROUNDS &&
            survivalRate() >= MIN_SURVIVAL_RATE &&
            averageDamageTaken() <= MAX_AVERAGE_DAMAGE_TAKEN

    private fun survivalRate(): Double = if (completedRounds == 0L) 0.0 else survivedRounds.toDouble() / completedRounds.toDouble()

    private fun averageDamageTaken(): Double = if (completedRounds == 0L) 0.0 else totalDamageTaken / completedRounds.toDouble()

    companion object {
        const val MIN_COMPLETED_ROUNDS = 8L
        const val MIN_SURVIVAL_RATE = 0.93
        const val MAX_AVERAGE_DAMAGE_TAKEN = 35.0
        const val HEAVY_DAMAGE_BACKOFF = 45.0
        const val BACKOFF_ROUNDS = 3L

        private val perEnemy = HashMap<String, ScorePressureController>()

        fun forEnemy(name: String): ScorePressureController = perEnemy.getOrPut(name) { ScorePressureController() }
    }
}
