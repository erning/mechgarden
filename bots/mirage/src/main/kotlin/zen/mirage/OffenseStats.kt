package zen.mirage

/**
 * Per-enemy realized accuracy for our real bullets.
 *
 * A resolved shot is either a robot hit, a miss, or a bullet collision. Bullet
 * collisions count as non-hits so the metric reflects useful offensive accuracy
 * against shielders. Instances live in a per-enemy registry so the signal
 * accumulates across rounds.
 */
class OffenseStats {
    private var hits = 0L
    private var nonHits = 0L

    fun recordHit() {
        hits++
    }

    fun recordNonHit() {
        nonHits++
    }

    fun resolvedShots(): Long = hits + nonHits

    fun hitRate(): Double {
        val total = resolvedShots()
        return if (total == 0L) Double.NaN else hits.toDouble() / total.toDouble()
    }

    companion object {
        private val perEnemy = HashMap<String, OffenseStats>()

        fun forEnemy(name: String): OffenseStats = perEnemy.getOrPut(name) { OffenseStats() }
    }
}
