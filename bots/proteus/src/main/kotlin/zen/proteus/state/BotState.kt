package zen.proteus.state

import kotlin.math.hypot

/** Immutable snapshot of one robot at one tick. Angles in radians. */
internal data class BotState(
    val time: Long,
    val x: Double,
    val y: Double,
    val headingRadians: Double,
    val velocity: Double,
    val energy: Double,
    val gunHeat: Double,
) {
    fun distanceTo(other: BotState): Double = hypot(other.x - x, other.y - y)
}
