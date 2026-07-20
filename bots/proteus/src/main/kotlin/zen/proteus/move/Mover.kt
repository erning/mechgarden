package zen.proteus.move

import zen.proteus.control.Controls
import zen.proteus.core.Angles
import zen.proteus.core.Battlefield
import zen.proteus.state.BotState
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs

/**
 * Movement. M1: full-speed distance-band orbit with wall smoothing; flips orbit
 * direction on most detected enemy shots so linear/circular lead keeps landing
 * where we no longer are.
 *
 * Later milestones plug in behind this same interface: enemy waves with precise
 * intersection (M2), path-space best-first surfing over direction sequences (M3),
 * the gated danger ensemble (M5). Mover will then publish simulated future states
 * for the Aimer — the gun currently assumes the single-step orbit above.
 */
internal class Mover {
    private var orbitDirection = 1.0
    private val random = Random()

    fun onRoundStart() {
        orbitDirection = if (random.nextBoolean()) 1.0 else -1.0
    }

    /** Reacts to a detected enemy shot (see [zen.proteus.state.GameState.EnemyShot]). */
    fun onEnemyShot() {
        if (random.nextDouble() < FLIP_PROBABILITY) orbitDirection = -orbitDirection
    }

    fun move(
        self: BotState,
        enemy: BotState,
        field: Battlefield,
        controls: Controls,
    ) {
        val distance = self.distanceTo(enemy)
        val bearingToEnemyRadians = Angles.absoluteBearingRadians(self.x, self.y, enemy.x, enemy.y)
        val approach =
            when {
                distance > PREFERRED_MAX -> APPROACH_RADIANS
                distance < PREFERRED_MIN -> -RETREAT_RADIANS
                else -> 0.0
            }
        val desiredRadians = bearingToEnemyRadians + orbitDirection * (PI / 2.0 + approach)
        val smoothedRadians = field.smoothWall(self.x, self.y, desiredRadians, orbitDirection)

        // Back-as-front: if the target heading is behind us, drive in reverse and
        // turn the shorter way, so velocity swings toward the desired heading fast.
        var turnRadians = Angles.normalizeRelative(smoothedRadians - self.headingRadians)
        var ahead = MAX_AHEAD
        if (abs(turnRadians) > PI / 2.0) {
            turnRadians = Angles.normalizeRelative(turnRadians - PI)
            ahead = -MAX_AHEAD
        }
        controls.bodyTurnRadians = turnRadians
        controls.ahead = ahead
    }

    private companion object {
        const val PREFERRED_MIN = 250.0
        const val PREFERRED_MAX = 450.0
        const val APPROACH_RADIANS = PI / 5.0
        const val RETREAT_RADIANS = PI / 4.0
        const val FLIP_PROBABILITY = 0.7
        const val MAX_AHEAD = 128.0
    }
}
