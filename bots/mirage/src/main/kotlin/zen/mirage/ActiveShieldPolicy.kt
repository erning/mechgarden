package zen.mirage

/**
 * Cross-round behavioral selector for active shielding. A badly losing normal
 * round schedules one shield trial. After enough normal and shield samples, the
 * selector latches only when the shield's score-oriented utility is materially
 * better. This keeps opponent identity out of the policy and bounds exploration.
 */
class ActiveShieldPolicy {
    private var normalRounds = 0
    private var shieldRounds = 0
    private var normalUtility = 0.0
    private var shieldUtility = 0.0
    private var pendingTrial = false
    private var selectedForRound = false
    private var latched = false

    fun beginRound() {
        selectedForRound = latched || pendingTrial
        pendingTrial = false
    }

    fun recordRound(
        dealt: Double,
        taken: Double,
        survived: Boolean,
        usedActiveShield: Boolean,
    ) {
        val safeDealt = dealt.coerceAtLeast(0.0)
        val safeTaken = taken.coerceAtLeast(0.0)
        val utility = safeDealt - safeTaken + if (survived) SURVIVAL_UTILITY else 0.0
        if (usedActiveShield) {
            shieldRounds++
            shieldUtility += utility
        } else {
            normalRounds++
            normalUtility += utility
            if (normalRounds >= MIN_NORMAL_ROUNDS &&
                averageNormalUtility() <= MAX_NORMAL_UTILITY_FOR_TRIAL &&
                !survived &&
                safeTaken >= TRIAL_MIN_TAKEN &&
                safeDealt <= TRIAL_MAX_DEALT
            ) {
                pendingTrial = true
            }
        }
        if (normalRounds >= MIN_NORMAL_ROUNDS &&
            shieldRounds >= MIN_SHIELD_ROUNDS &&
            averageShieldUtility() >= averageNormalUtility() + MIN_UTILITY_ADVANTAGE
        ) {
            latched = true
        }
    }

    fun activeForRound(): Boolean = selectedForRound

    fun debugSummary(): String =
        "shieldPolicy=${if (selectedForRound) "trial" else "normal"}/${if (latched) "latched" else "open"}/" +
            "$normalRounds:${"%.1f".format(averageNormalUtility())}/$shieldRounds:${"%.1f".format(averageShieldUtility())}"

    private fun averageNormalUtility(): Double = if (normalRounds == 0) 0.0 else normalUtility / normalRounds

    private fun averageShieldUtility(): Double = if (shieldRounds == 0) 0.0 else shieldUtility / shieldRounds

    companion object {
        private const val SURVIVAL_UTILITY = 50.0
        private const val TRIAL_MIN_TAKEN = 65.0
        private const val TRIAL_MAX_DEALT = 40.0
        private const val MAX_NORMAL_UTILITY_FOR_TRIAL = 10.0
        private const val MIN_NORMAL_ROUNDS = 3
        private const val MIN_SHIELD_ROUNDS = 2
        private const val MIN_UTILITY_ADVANTAGE = 10.0

        /** Per-enemy battle/JVM memory; no data-file persistence. */
        private val registry = mutableMapOf<String, ActiveShieldPolicy>()

        fun forEnemy(name: String): ActiveShieldPolicy = registry.getOrPut(name) { ActiveShieldPolicy() }
    }
}
