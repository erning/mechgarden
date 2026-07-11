package zen.mirage

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Pure behavioral detector for periodic stop-and-go movement.
 *
 * A stop episode begins only after the target has first reached [HIGH_SPEED], then
 * falls to [LOW_SPEED] or below. The episode completes when it leaves the low-speed
 * band. Stop intervals are measured between episode starts; this distinguishes a
 * recurring long-period movement cycle from ordinary wave-surfing reversals,
 * isolated braking, or a target that simply parks.
 *
 * [beginRound] scores and clears the current round's evidence because Robocode time
 * resets at the round boundary. Three qualifying rounds inside the latest five-round
 * window enable the classification; three consecutive misses disable it again, so
 * an accidental match cannot contaminate the rest of a battle.
 */
class StopGoDetector {
    private var classified = false
    private var recentQualifyingRounds = 0
    private var consecutiveNonQualifyingRounds = 0
    private var lastTime: Long? = null
    private var armedByHighSpeed = false
    private var inLowSpeedEpisode = false
    private var lowSpeedStart = 0L
    private var lastStopStart: Long? = null

    private var completedLowSpeedEpisodes = 0
    private var totalLowSpeedDwell = 0L
    private var stopIntervals = 0
    private var stopIntervalTotal = 0.0
    private var stopIntervalSquareTotal = 0.0

    val likely: Boolean
        get() = classified

    /** Observe one velocity sample at monotonically increasing battle [time]. */
    fun observe(
        time: Long,
        velocity: Double,
    ) {
        require(time >= 0L) { "time must not be negative" }
        require(velocity.isFinite()) { "velocity must be finite" }
        val previousTime = lastTime
        require(previousTime == null || time > previousTime) { "time must increase within a round" }
        if (previousTime != null && time - previousTime > MAX_SAMPLE_GAP) {
            // Do not turn a radar gap into a long stop or a valid periodic interval.
            armedByHighSpeed = false
            inLowSpeedEpisode = false
            lastStopStart = null
        }
        lastTime = time

        val speed = abs(velocity)
        if (inLowSpeedEpisode && speed > LOW_SPEED) completeLowSpeedEpisode(time)
        if (speed >= HIGH_SPEED) armedByHighSpeed = true
        if (!inLowSpeedEpisode && armedByHighSpeed && speed <= LOW_SPEED) startLowSpeedEpisode(time)
    }

    /** Score the completed round, update the rolling classification, and reset samples. */
    fun beginRound() {
        val qualifies = lastTime != null && currentEvidenceQualifies()
        recentQualifyingRounds =
            ((recentQualifyingRounds shl 1) or if (qualifies) 1 else 0) and ROUND_EVIDENCE_MASK
        consecutiveNonQualifyingRounds =
            if (qualifies) 0 else (consecutiveNonQualifyingRounds + 1).coerceAtMost(DEACTIVATE_AFTER_MISSES)
        if (!classified && Integer.bitCount(recentQualifyingRounds) >= REQUIRED_QUALIFYING_ROUNDS) {
            classified = true
        } else if (classified && consecutiveNonQualifyingRounds >= DEACTIVATE_AFTER_MISSES) {
            classified = false
        }
        lastTime = null
        armedByHighSpeed = false
        inLowSpeedEpisode = false
        lowSpeedStart = 0L
        lastStopStart = null
        completedLowSpeedEpisodes = 0
        totalLowSpeedDwell = 0L
        stopIntervals = 0
        stopIntervalTotal = 0.0
        stopIntervalSquareTotal = 0.0
    }

    fun debugSummary(): String =
        "stopGo=${if (classified) "likely" else "open"}/${Integer.bitCount(recentQualifyingRounds)} " +
            "episodes=$completedLowSpeedEpisodes dwell=${format(averageLowSpeedDwell())} " +
            "intervals=$stopIntervals mean=${format(meanStopInterval())} cv=${format(stopIntervalCv())}"

    private fun startLowSpeedEpisode(time: Long) {
        val previousStart = lastStopStart
        if (previousStart != null) {
            val interval = (time - previousStart).toDouble()
            stopIntervals++
            stopIntervalTotal += interval
            stopIntervalSquareTotal += interval * interval
        }
        lastStopStart = time
        lowSpeedStart = time
        inLowSpeedEpisode = true
        armedByHighSpeed = false
    }

    private fun completeLowSpeedEpisode(time: Long) {
        completedLowSpeedEpisodes++
        totalLowSpeedDwell += (time - lowSpeedStart).coerceAtLeast(1L)
        inLowSpeedEpisode = false
    }

    private fun currentEvidenceQualifies(): Boolean {
        if (completedLowSpeedEpisodes < MIN_COMPLETED_EPISODES) return false
        if (averageLowSpeedDwell() < MIN_AVERAGE_DWELL) return false
        if (stopIntervals < MIN_STOP_INTERVALS) return false
        val mean = meanStopInterval()
        if (mean !in MIN_MEAN_INTERVAL..MAX_MEAN_INTERVAL) return false
        return stopIntervalCv() <= MAX_INTERVAL_CV
    }

    private fun averageLowSpeedDwell(): Double =
        if (completedLowSpeedEpisodes == 0) Double.NaN else totalLowSpeedDwell.toDouble() / completedLowSpeedEpisodes

    private fun meanStopInterval(): Double = if (stopIntervals == 0) Double.NaN else stopIntervalTotal / stopIntervals

    private fun stopIntervalCv(): Double {
        val mean = meanStopInterval()
        if (!mean.isFinite() || mean <= 0.0) return Double.NaN
        val variance = (stopIntervalSquareTotal / stopIntervals - mean * mean).coerceAtLeast(0.0)
        return sqrt(variance) / mean
    }

    private fun format(value: Double): String = if (value.isFinite()) "%.2f".format(value) else "n/a"

    companion object {
        private const val HIGH_SPEED = 4.0
        private const val LOW_SPEED = 1.0
        private const val MIN_COMPLETED_EPISODES = 10
        private const val MIN_AVERAGE_DWELL = 2.2
        private const val MIN_STOP_INTERVALS = 10
        private const val MIN_MEAN_INTERVAL = 27.0
        private const val MAX_MEAN_INTERVAL = 45.0
        private const val MAX_INTERVAL_CV = 0.75
        private const val MAX_SAMPLE_GAP = 2L
        private const val REQUIRED_QUALIFYING_ROUNDS = 3
        private const val ROUND_EVIDENCE_WINDOW = 5
        private const val DEACTIVATE_AFTER_MISSES = 3
        private const val ROUND_EVIDENCE_MASK = (1 shl ROUND_EVIDENCE_WINDOW) - 1

        private val perEnemy = HashMap<String, StopGoDetector>()

        fun forEnemy(name: String): StopGoDetector = perEnemy.getOrPut(name) { StopGoDetector() }
    }
}
