package zen.proteus.wave

import kotlin.math.abs

/**
 * Our outgoing waves (real and virtual), used to learn the enemy's movement
 * profile in guess-factor space. Virtual waves carry no bullet: every tick's
 * wave still samples the enemy's covered GF interval when it passes them, so
 * the profile learns from far more than our ~30 bullets per round. Each entry
 * keeps the feature vector captured at wave creation for the KNN guns.
 */
internal class AimWaves {
    /** An outgoing wave plus the feature vector captured at its creation. */
    class Entry(
        val wave: Wave,
        val features: DoubleArray,
    )

    private val active = ArrayList<Entry>()

    fun add(entry: Entry) {
        active.add(entry)
    }

    fun clear() {
        active.clear()
    }

    /**
     * Advances all waves through the bullet phase of [time] against the enemy
     * square at (enemyX, enemyY). Returns entries whose waves finished passing,
     * each holding the enemy's covered GF interval.
     */
    fun update(
        enemyX: Double,
        enemyY: Double,
        time: Long,
    ): List<Entry> {
        val passed = ArrayList<Entry>()
        val iterator = active.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val wave = entry.wave
            val r1 = wave.radius(time)
            val interval = wave.intersection(enemyX, enemyY, r1 - wave.speed, r1)
            if (interval != null) {
                wave.recordVisit(interval[0], interval[1])
            } else if (r1 > wave.distanceTo(enemyX, enemyY) + PASS_MARGIN) {
                iterator.remove()
                if (wave.hasVisitInterval) passed.add(entry)
            }
        }
        return passed
    }

    /** Matches a real hit at (x, y) to a fired wave and removes it. */
    fun markHit(
        x: Double,
        y: Double,
        power: Double,
        time: Long,
    ): Entry? {
        var best: Entry? = null
        var bestDiff = Double.POSITIVE_INFINITY
        for (entry in active) {
            val wave = entry.wave
            if (!wave.bulletFired || abs(wave.power - power) > POWER_TOLERANCE) continue
            val diff = abs(wave.distanceTo(x, y) - wave.radius(time))
            if (diff < wave.speed + MATCH_SLACK && diff < bestDiff) {
                best = entry
                bestDiff = diff
            }
        }
        if (best != null) active.remove(best)
        return best
    }

    private companion object {
        const val PASS_MARGIN = 26.0
        const val POWER_TOLERANCE = 0.01
        const val MATCH_SLACK = 18.0
    }
}
