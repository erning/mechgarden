package zen.mirage

import kotlin.math.abs

/**
 * Detects an opponent that repeatedly drives at our current position.
 *
 * This is deliberately behavior-based: the target catalog is a benchmark, not a
 * name allow-list. A short evidence streak activates the signal early, while a
 * latch keeps one noisy scan from flapping close-range firepower or movement.
 */
class RamThreatDetector {
    private var evidenceTicks = 0L
    private var latchTicks = 0L
    private var lastObservationTime: Long? = null

    fun observe(frame: Tracker.Frame) {
        val derived = frame.derived ?: return
        val enemy = frame.enemy
        val elapsed =
            lastObservationTime
                ?.let { (enemy.time - it).coerceIn(1L, MAX_SCAN_GAP_TICKS) }
                ?: 1L
        lastObservationTime = enemy.time

        val travelHeading =
            if (enemy.velocity >= 0.0) {
                enemy.headingRadians
            } else {
                Angles.normalizeAbsolute(enemy.headingRadians + Angles.PI)
            }
        val towardUs = Angles.normalizeAbsolute(enemy.absoluteBearingRadians + Angles.PI)
        val headingError = abs(Angles.normalizeRelative(travelHeading - towardUs))
        val closingSpeed = -derived.distanceRate

        var score = 0
        if (derived.advancingVelocity >= MIN_ADVANCING_SPEED) score += 2
        if (closingSpeed >= MIN_CLOSING_SPEED) score += 2
        if (headingError <= MAX_PURSUIT_HEADING_ERROR) score += 2
        if (abs(derived.lateralVelocity) <= MAX_PURSUIT_LATERAL_SPEED) score++
        if (enemy.distance <= CLOSE_EVIDENCE_DISTANCE) score++

        if (score >= MIN_OBSERVATION_SCORE) {
            evidenceTicks = (evidenceTicks + elapsed).coerceAtMost(MAX_EVIDENCE_TICKS)
            if (evidenceTicks >= MIN_EVIDENCE_TICKS) latchTicks = ACTIVE_LATCH_TICKS
        } else {
            evidenceTicks = (evidenceTicks - elapsed * EVIDENCE_DECAY).coerceAtLeast(0L)
            latchTicks = (latchTicks - elapsed).coerceAtLeast(0L)
        }
    }

    fun recordCollision() {
        evidenceTicks = maxOf(evidenceTicks, MIN_EVIDENCE_TICKS)
        latchTicks = ACTIVE_LATCH_TICKS
    }

    fun active(): Boolean = latchTicks > 0L

    fun evidence(): Long = evidenceTicks

    fun latch(): Long = latchTicks

    fun debugSummary(): String = "ramThreat=${if (active()) "on" else "off"}/$evidenceTicks/$latchTicks"

    private companion object {
        const val MAX_SCAN_GAP_TICKS = 8L
        const val MIN_ADVANCING_SPEED = 4.0
        const val MIN_CLOSING_SPEED = 3.0
        const val MAX_PURSUIT_LATERAL_SPEED = 2.0
        const val CLOSE_EVIDENCE_DISTANCE = 350.0
        const val MIN_OBSERVATION_SCORE = 6
        const val MIN_EVIDENCE_TICKS = 6L
        const val MAX_EVIDENCE_TICKS = 20L
        const val EVIDENCE_DECAY = 2L
        const val ACTIVE_LATCH_TICKS = 40L
        val MAX_PURSUIT_HEADING_ERROR = Math.toRadians(20.0)
    }
}
