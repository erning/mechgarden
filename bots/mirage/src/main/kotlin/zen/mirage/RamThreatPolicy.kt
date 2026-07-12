package zen.mirage

/**
 * Carries confirmed ram behavior across the detector's short active latch and
 * across rounds against the same opponent.
 *
 * A direct charger benefits from sustained high-power fire, including the quiet
 * intervals after it overshoots. The memory decays after consecutive quiet
 * rounds so a transient pursuit pattern cannot permanently select that tactic.
 */
class RamThreatPolicy {
    private var confirmed = false
    private var quietRounds = 0

    fun aggressiveFireRecommended(): Boolean = confirmed

    fun recordRound(threatSeen: Boolean) {
        if (threatSeen) {
            confirmed = true
            quietRounds = 0
            return
        }

        if (!confirmed) return
        quietRounds++
        if (quietRounds >= QUIET_ROUNDS_TO_FORGET) {
            confirmed = false
            quietRounds = 0
        }
    }

    companion object {
        private const val QUIET_ROUNDS_TO_FORGET = 3
        private val perEnemy = HashMap<String, RamThreatPolicy>()

        fun forEnemy(name: String): RamThreatPolicy = perEnemy.getOrPut(name) { RamThreatPolicy() }
    }
}
