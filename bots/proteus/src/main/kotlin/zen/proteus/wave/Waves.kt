package zen.proteus.wave

import zen.proteus.core.Battlefield
import kotlin.math.abs
import kotlin.math.atan2

/** Registry of enemy waves in flight: advances them, matches bullet events to
 *  waves, and selects the waves worth surfing. */
internal class Waves {
    /** A wave that finished passing us without hitting: the GF interval we covered. */
    class PassedWave(
        val gfLo: Double,
        val gfHi: Double,
    )

    /** A wave matched to a real bullet, with the bullet's absolute angle. */
    class MatchedWave(
        val wave: Wave,
        val angleRadians: Double,
    )

    private val active = ArrayList<Wave>()

    val isActive: Boolean
        get() = active.isNotEmpty()

    fun add(wave: Wave) {
        active.add(wave)
    }

    fun clear() {
        active.clear()
    }

    /**
     * Advances all waves through the bullet phase of [time]: the ring segment
     * flown this turn is tested against the robot square at (prevX, prevY) —
     * our position before this turn's move, which is the square the engine
     * collides bullets against. Returns waves that finished passing us.
     */
    fun update(
        prevX: Double,
        prevY: Double,
        time: Long,
    ): List<PassedWave> {
        val passed = ArrayList<PassedWave>()
        val iterator = active.iterator()
        while (iterator.hasNext()) {
            val wave = iterator.next()
            val r1 = wave.radius(time)
            val r0 = r1 - wave.speed
            val interval = wave.intersection(prevX, prevY, r0, r1)
            if (interval != null) {
                wave.recordVisit(interval[0], interval[1])
            } else if (r1 > wave.distanceTo(prevX, prevY) + WAVE_PASS_MARGIN) {
                iterator.remove()
                if (wave.hasVisitInterval) {
                    passed.add(PassedWave(wave.visitGfLo, wave.visitGfHi))
                }
            }
        }
        return passed
    }

    /**
     * Finds the wave matching a bullet that ended at (x, y): same power and a
     * radius consistent with the distance flown. Removes it from the registry.
     */
    fun matchBullet(
        x: Double,
        y: Double,
        power: Double,
        time: Long,
    ): MatchedWave? {
        var best: Wave? = null
        var bestDiff = Double.POSITIVE_INFINITY
        for (wave in active) {
            if (abs(wave.power - power) > POWER_TOLERANCE) continue
            val diff = abs(wave.distanceTo(x, y) - wave.radius(time))
            if (diff < wave.speed + MATCH_SLACK && diff < bestDiff) {
                best = wave
                bestDiff = diff
            }
        }
        if (best != null) {
            active.remove(best)
            return MatchedWave(best, atan2(x - best.originX, y - best.originY))
        }
        return null
    }

    /** Up to two active waves nearest to reaching (x, y), soonest first. */
    fun surfable(
        x: Double,
        y: Double,
        time: Long,
    ): List<Wave> =
        active
            .sortedBy { it.ticksUntilArrival(it.distanceTo(x, y), time) }
            .take(MAX_SURFED_WAVES)

    private companion object {
        // > 18 * sqrt(2): the ring is past every point of the robot square.
        const val WAVE_PASS_MARGIN = 26.0
        const val POWER_TOLERANCE = 0.01

        // Hit events report victim-relative positions, so allow extra slack.
        const val MATCH_SLACK = Battlefield.ROBOT_HALF_SIZE
        const val MAX_SURFED_WAVES = 2
    }
}
