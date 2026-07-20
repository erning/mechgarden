package zen.proteus.knn

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * KNN model for the guns: stores (features, covered GF interval, didHit) per
 * completed wave and answers density-peak guess factors. Neighbor search is a
 * weighted Manhattan-distance linear scan — correct first; a KD-tree replaces
 * it when tree sizes make the scan hot. Capacity-bounded FIFO.
 */
internal class KnnModel(
    private val capacity: Int,
    private val weights: DoubleArray,
) {
    class Entry(
        val features: DoubleArray,
        val gfLo: Double,
        val gfHi: Double,
        val didHit: Boolean,
    )

    private class Neighbor(
        val distance: Double,
        val entry: Entry,
    )

    private val entries = ArrayList<Entry>()

    val size: Int
        get() = entries.size

    fun add(entry: Entry) {
        entries.add(entry)
        if (entries.size > capacity) {
            entries.removeAt(0)
        }
    }

    /**
     * Aim GF of the weighted density peak over the k nearest neighbors'
     * intervals. [includeDidHit] false excludes post-hit samples — the
     * anti-surfer query, since enemies dodge away from where they were hit.
     */
    fun aimGuessFactor(
        query: DoubleArray,
        includeDidHit: Boolean,
    ): Double {
        if (entries.isEmpty()) return 0.0
        val neighbors = nearest(query, includeDidHit)
        if (neighbors.isEmpty()) return 0.0
        return densityPeak(neighbors)
    }

    /** Neighbor-weighted density mass over [gfLo, gfHi], normalized to [0, 1]. */
    fun densityAt(
        query: DoubleArray,
        gfLo: Double,
        gfHi: Double,
        includeDidHit: Boolean,
    ): Double {
        val neighbors = nearest(query, includeDidHit)
        if (neighbors.isEmpty()) return 0.0
        var total = 0.0
        var covered = 0.0
        for (neighbor in neighbors) {
            val weight = 1.0 / (0.1 + neighbor.distance)
            total += weight
            val overlapLo = max(gfLo, neighbor.entry.gfLo)
            val overlapHi = min(gfHi, neighbor.entry.gfHi)
            if (overlapLo < overlapHi) covered += weight
        }
        return if (total > 0.0) covered / total else 0.0
    }

    /** The k nearest entries to [query] by weighted Manhattan distance. */
    private fun nearest(
        query: DoubleArray,
        includeDidHit: Boolean,
    ): List<Neighbor> {
        val k = min(MAX_NEIGHBORS, max(MIN_NEIGHBORS, entries.size / K_DIVIDER))
        val best = ArrayList<Neighbor>(k + 1)
        for (entry in entries) {
            if (!includeDidHit && entry.didHit) continue
            val distance = distance(query, entry.features)
            if (best.size < k) {
                best.add(Neighbor(distance, entry))
                if (best.size == k) best.sortByDescending { it.distance }
            } else if (distance < best[0].distance) {
                best[0] = Neighbor(distance, entry)
                var i = 0
                while (i + 1 < best.size && best[i].distance < best[i + 1].distance) {
                    val tmp = best[i]
                    best[i] = best[i + 1]
                    best[i + 1] = tmp
                    i++
                }
            }
        }
        return best
    }

    private fun distance(
        a: DoubleArray,
        b: DoubleArray,
    ): Double {
        var sum = 0.0
        for (i in a.indices) {
            sum += weights[i] * abs(a[i] - b[i])
        }
        return sum
    }

    /** Scanline argmax over weighted intervals (box-kernel KDE). */
    private fun densityPeak(neighbors: List<Neighbor>): Double {
        // Start events carry +weight, end events -weight; sweep keeps the level.
        val events = ArrayList<Pair<Double, Double>>(neighbors.size * 2)
        for (neighbor in neighbors) {
            val weight = 1.0 / (0.1 + neighbor.distance)
            events.add(neighbor.entry.gfLo to weight)
            events.add(neighbor.entry.gfHi to -weight)
        }
        events.sortBy { it.first }

        var current = 0.0
        var bestHeight = -1.0
        var bestStart = 0.0
        var bestEnd = 0.0
        var lastPos = 0.0
        var i = 0
        while (i < events.size) {
            val pos = events[i].first
            if (current > bestHeight && i > 0) {
                bestHeight = current
                bestStart = lastPos
                bestEnd = pos
            }
            while (i < events.size && events[i].first == pos) {
                current += events[i].second
                i++
            }
            lastPos = pos
        }
        if (current > bestHeight) {
            bestStart = lastPos
            bestEnd = lastPos + 0.05
        }
        return ((bestStart + bestEnd) / 2.0).coerceIn(-1.0, 1.0)
    }

    private companion object {
        const val MAX_NEIGHBORS = 100
        const val MIN_NEIGHBORS = 10
        const val K_DIVIDER = 4
    }
}
