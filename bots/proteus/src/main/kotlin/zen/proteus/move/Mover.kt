package zen.proteus.move

import zen.proteus.control.Controls
import zen.proteus.core.Angles
import zen.proteus.core.Battlefield
import zen.proteus.move.danger.EmpiricalDanger
import zen.proteus.state.BotState
import zen.proteus.state.GameState
import zen.proteus.wave.Wave
import zen.proteus.wave.Waves
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs

/**
 * Movement. Enemy shots become expanding waves; while any wave is in flight we
 * true-surf the nearest ones ([Surfer] simulates FORWARD / BACKWARD / STOP and
 * picks the lowest-danger covered GF interval). With no waves we fall back to a
 * distance-band orbit. Danger is learned per enemy and survives round rebuilds
 * via a static registry: real hits and mid-air bullet collisions train the hit
 * bins, waves that pass us train the visit bins.
 *
 * Later milestones replace the three fixed options with best-first path search
 * (M3) and swap [EmpiricalDanger] for the gated ensemble (M5) behind this same
 * orchestration.
 */
internal class Mover {
    private val waves = Waves()
    private var danger: EmpiricalDanger? = null
    private var surfer: Surfer? = null
    private var field: Battlefield? = null
    private var orbitDirection = 1.0
    private val random = Random()

    fun onRoundStart() {
        waves.clear()
        orbitDirection = if (random.nextBoolean()) 1.0 else -1.0
        // danger intentionally survives: it belongs to the per-enemy registry.
    }

    fun onEnemyShot(shot: GameState.EnemyShot) {
        waves.add(
            Wave(
                shot.originX,
                shot.originY,
                shot.power,
                shot.time,
                shot.directAngleRadians,
                shot.lateralDirection,
            ),
        )
    }

    /**
     * An enemy bullet ended mid-flight at (x, y) — it hit us or collided with
     * one of our bullets. Match it to a wave and learn where it was aimed.
     */
    fun onEnemyBulletAt(
        x: Double,
        y: Double,
        power: Double,
        time: Long,
    ) {
        val matched = waves.matchBullet(x, y, power, time) ?: return
        danger?.recordHit(matched.wave.guessFactor(matched.angleRadians))
    }

    fun move(
        self: BotState,
        selfPrev: BotState?,
        enemy: BotState?,
        enemyName: String?,
        battlefield: Battlefield,
        time: Long,
        controls: Controls,
    ) {
        if (danger == null && enemyName != null) {
            danger = DANGER_REGISTRY.getOrPut(enemyName) { EmpiricalDanger() }
        }
        if (surfer == null) surfer = Surfer(battlefield)
        field = battlefield
        val currentDanger = danger

        if (selfPrev != null) {
            for (passed in waves.update(selfPrev.x, selfPrev.y, time)) {
                currentDanger?.recordVisit(passed.gfLo, passed.gfHi)
            }
        }

        val surfWaves = waves.surfable(self.x, self.y, time)
        if (surfWaves.isNotEmpty() && currentDanger != null && enemy != null) {
            val choice = surfer!!.choose(self, enemy, surfWaves, currentDanger, orbitDirection, time)
            orbitDirection = choice.orbitDirection
            executeSurf(self, enemy, choice, controls)
        } else if (enemy != null) {
            orbit(self, enemy, battlefield, controls)
        }
    }

    private fun executeSurf(
        self: BotState,
        enemy: BotState,
        choice: Surfer.Choice,
        controls: Controls,
    ) {
        // Same formula the sim advances with, so execution tracks the plan.
        val biasRadians = Surfer.dampedBiasRadians(self.distanceTo(enemy))
        val desiredRadians =
            Angles.absoluteBearingRadians(self.x, self.y, enemy.x, enemy.y) +
                choice.orbitDirection * (PI / 2.0 + biasRadians)
        val smoothedRadians =
            field!!.smoothWall(self.x, self.y, desiredRadians, choice.orbitDirection)
        controls.bodyTurnRadians = Angles.normalizeRelative(smoothedRadians - self.headingRadians)
        controls.ahead = if (choice.option == Surfer.Option.STOP) 0.0 else MAX_AHEAD
    }

    /** No waves in flight: hold a distance band on the enemy with wall smoothing. */
    private fun orbit(
        self: BotState,
        enemy: BotState,
        field: Battlefield,
        controls: Controls,
    ) {
        val distance = self.distanceTo(enemy)
        val bearingToEnemyRadians = Angles.absoluteBearingRadians(self.x, self.y, enemy.x, enemy.y)
        val desiredRadians =
            bearingToEnemyRadians + orbitDirection * (PI / 2.0 + DistanceBand.biasRadians(distance))
        val smoothedRadians = field.smoothWall(self.x, self.y, desiredRadians, orbitDirection)

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
        const val MAX_AHEAD = 128.0

        /** Per-enemy learned danger; survives Robocode's per-round robot rebuild. */
        val DANGER_REGISTRY = HashMap<String, EmpiricalDanger>()
    }
}
