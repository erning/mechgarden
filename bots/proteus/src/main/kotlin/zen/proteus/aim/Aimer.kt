package zen.proteus.aim

import robocode.AdvancedRobot
import robocode.Rules
import zen.proteus.control.Controls
import zen.proteus.core.Angles
import zen.proteus.core.Battlefield
import zen.proteus.state.BotState
import zen.proteus.state.HitRate
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Targeting. M1: iterative linear-prediction gun with distance-scaled power and
 * can-actually-hit fire gating; hit/miss outcomes feed [hitRate].
 *
 * Later milestones plug in behind this interface: every-tick virtual waves with
 * KNN guns and a main/anti-surfer switch gated on our hit-rate interval (M4),
 * expected-score firepower (M6), and shadow-aware shot selection that sometimes
 * fires for the bullet shadow instead of the hit (M6).
 */
internal class Aimer(
    private val robot: AdvancedRobot,
) {
    /** Our realized hit rate against the current enemy; later drives gun switching. */
    val hitRate = HitRate()

    fun onBulletHit() = hitRate.record(true)

    fun onBulletMissed() = hitRate.record(false)

    fun aim(
        self: BotState,
        enemy: BotState,
        field: Battlefield,
        controls: Controls,
    ) {
        val power = selectPower(self, enemy)
        val aimRadians = linearLead(self, enemy, field, power)
        val gunTurnRadians = Angles.normalizeRelative(aimRadians - robot.gunHeadingRadians)
        controls.gunTurnRadians = gunTurnRadians

        // Fire only when the gun is aligned well enough that the shot can connect:
        // the enemy robot square subtends atan(18 / distance) to each side.
        val aligned = abs(gunTurnRadians) <= atan2(Battlefield.ROBOT_HALF_SIZE, self.distanceTo(enemy))
        if (robot.gunHeat == 0.0 && aligned && self.energy > power + ENERGY_RESERVE) {
            controls.firePower = power
        }
    }

    /** Iterative linear lead; two passes converge for the flight times we see. */
    private fun linearLead(
        self: BotState,
        enemy: BotState,
        field: Battlefield,
        power: Double,
    ): Double {
        val bulletSpeed = Rules.getBulletSpeed(power)
        var targetX = enemy.x
        var targetY = enemy.y
        repeat(LEAD_ITERATIONS) {
            val flightTicks = hypot(targetX - self.x, targetY - self.y) / bulletSpeed
            targetX = field.clampX(enemy.x + sin(enemy.headingRadians) * enemy.velocity * flightTicks)
            targetY = field.clampY(enemy.y + cos(enemy.headingRadians) * enemy.velocity * flightTicks)
        }
        return Angles.absoluteBearingRadians(self.x, self.y, targetX, targetY)
    }

    private fun selectPower(
        self: BotState,
        enemy: BotState,
    ): Double {
        val distance = self.distanceTo(enemy)
        var power = (POWER_DISTANCE_SCALE / distance).coerceIn(Rules.MIN_BULLET_POWER, Rules.MAX_BULLET_POWER)
        if (self.energy < LOW_ENERGY) power = Rules.MIN_BULLET_POWER
        // Kill shot: no point spending more energy than the damage that ends it.
        if (enemy.energy <= Rules.getBulletDamage(power)) {
            power = (enemy.energy / 4.0).coerceIn(Rules.MIN_BULLET_POWER, Rules.MAX_BULLET_POWER)
        }
        return power
    }

    private companion object {
        const val LEAD_ITERATIONS = 2
        const val POWER_DISTANCE_SCALE = 600.0
        const val LOW_ENERGY = 2.0
        const val ENERGY_RESERVE = 0.05
    }
}
