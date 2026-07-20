package zen.proteus.core

import robocode.Rules
import kotlin.math.abs

/**
 * Engine-rule physics as pure functions, self-contained for prediction use.
 * Mirrors docs/robocode-physics.md; constants come from `robocode.Rules`
 * instead of being hardcoded here.
 */
internal object Kinematics {
    /** Max body turn per tick at the given velocity. */
    fun maxBodyTurnRadians(velocity: Double): Double = Math.toRadians(Rules.getTurnRate(abs(velocity)))

    /** Ticks for a bullet of [power] to cover [distance]. */
    fun bulletFlightTicks(
        distance: Double,
        power: Double,
    ): Double = distance / Rules.getBulletSpeed(power)

    /** Ticks the gun needs to cool after firing [power]. */
    fun gunCoolingTicks(
        power: Double,
        coolingRate: Double,
    ): Double = Rules.getGunHeat(power) / coolingRate
}
