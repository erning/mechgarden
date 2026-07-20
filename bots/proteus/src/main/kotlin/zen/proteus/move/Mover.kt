package zen.proteus.move

import zen.proteus.control.Controls
import zen.proteus.core.Angles
import zen.proteus.core.Battlefield
import zen.proteus.move.danger.EmpiricalDanger
import zen.proteus.state.BotState
import zen.proteus.state.GameState
import zen.proteus.wave.BulletShadows
import zen.proteus.wave.Wave
import zen.proteus.wave.Waves
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs

/**
 * Movement. Enemy shots become expanding waves; while any wave is in flight we
 * surf the nearest ones with [PathSurfer]: a best-first search over per-tick
 * forward / stop / reverse options along the orbit tangent, scored by the
 * covered guess-factor danger (bullet-shadow aware). With no waves we fall back
 * to a distance-band orbit. Danger is learned per enemy and survives round
 * rebuilds via a static registry: real hits and mid-air bullet collisions train
 * the hit bins, waves that pass us train the visit bins.
 *
 * Later milestones swap [EmpiricalDanger] for the gated ensemble (M5) behind
 * this same orchestration.
 */
internal class Mover {
    private val waves = Waves()
    private val ourBullets = OurBullets()
    private var danger: EmpiricalDanger? = null
    private var surfer: Surfer? = null
    private var pathSurfer: PathSurfer? = null
    private var field: Battlefield? = null
    private var orbitDirection = 1.0
    private var lastPlan: PathSurfer.Plan? = null
    private val random = Random()

    fun onRoundStart() {
        waves.clear()
        ourBullets.clear()
        orbitDirection = if (random.nextBoolean()) 1.0 else -1.0
        lastPlan = null
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

    /** One of our bullets just left the barrel. */
    fun onOurBullet(
        x: Double,
        y: Double,
        angleRadians: Double,
        speed: Double,
        power: Double,
        fireTime: Long,
    ) {
        ourBullets.add(OurBullets.Tracked(x, y, angleRadians, speed, fireTime, power))
    }

    /** One of our bullets ended at (x, y) (hit, missed, or collided). */
    fun onOurBulletEnd(
        x: Double,
        y: Double,
        time: Long,
    ) {
        ourBullets.removeNear(x, y, time)
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
        if (pathSurfer == null) pathSurfer = PathSurfer(battlefield)
        field = battlefield
        val currentDanger = danger

        if (selfPrev != null) {
            for (passed in waves.update(selfPrev.x, selfPrev.y, time)) {
                currentDanger?.recordVisit(passed.gfLo, passed.gfHi)
            }
        }

        val surfWaves = waves.surfable(self.x, self.y, time)
        if (surfWaves.isNotEmpty() && currentDanger != null && enemy != null) {
            ourBullets.prune(battlefield, time)
            val shadows = HashMap<Wave, List<DoubleArray>>(surfWaves.size)
            for (wave in surfWaves) {
                shadows[wave] = BulletShadows.intervals(wave, ourBullets.all(), self.x, self.y, time)
            }
            when (MOVEMENT_ENGINE) {
                MovementEngine.THREE_OPTION -> {
                    val choice = surfer!!.choose(self, enemy, surfWaves, currentDanger, shadows, orbitDirection, time)
                    orbitDirection = choice.orbitDirection
                    executeSurf(self, enemy, choice, controls)
                }

                MovementEngine.PATH_SEARCH -> {
                    val plan = pathSurfer!!.plan(self, enemy, surfWaves, currentDanger, shadows, orbitDirection, time, lastPlan)
                    orbitDirection = plan.line
                    lastPlan =
                        if (plan.options.size > 1) {
                            plan.copy(options = plan.options.copyOfRange(1, plan.options.size))
                        } else {
                            null
                        }
                    executePlan(self, enemy, plan, controls)
                }
            }
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
        val biasRadians = DistanceBand.dampedBiasRadians(self.distanceTo(enemy))
        val desiredRadians =
            Angles.absoluteBearingRadians(self.x, self.y, enemy.x, enemy.y) +
                choice.orbitDirection * (PI / 2.0 + biasRadians)
        val smoothedRadians =
            field!!.smoothWall(self.x, self.y, desiredRadians, choice.orbitDirection)
        controls.bodyTurnRadians = Angles.normalizeRelative(smoothedRadians - self.headingRadians)
        controls.ahead = if (choice.option == Surfer.Option.STOP) 0.0 else MAX_AHEAD
    }

    private fun executePlan(
        self: BotState,
        enemy: BotState,
        plan: PathSurfer.Plan,
        controls: Controls,
    ) {
        // Same formula the search advances with, so execution tracks the plan.
        val option = plan.options.firstOrNull() ?: 1
        val biasRadians = DistanceBand.dampedBiasRadians(self.distanceTo(enemy))
        val desiredRadians =
            Angles.absoluteBearingRadians(self.x, self.y, enemy.x, enemy.y) +
                plan.line * (PI / 2.0 + biasRadians)
        val smoothedRadians =
            field!!.smoothWall(self.x, self.y, desiredRadians, plan.line)
        controls.bodyTurnRadians = Angles.normalizeRelative(smoothedRadians - self.headingRadians)
        controls.ahead = option * MAX_AHEAD
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

        // THREE_OPTION (default): the M2 three-option surfer, proven against this
        // danger model. PATH_SEARCH: best-first path surfing; it threads narrow
        // danger peaks too eagerly for the crude empirical model and loses in
        // battle — revisit once the M5 ensemble feeds it.
        val MOVEMENT_ENGINE = MovementEngine.THREE_OPTION

        /** Per-enemy learned danger; survives Robocode's per-round robot rebuild. */
        val DANGER_REGISTRY = HashMap<String, EmpiricalDanger>()
    }

    enum class MovementEngine { THREE_OPTION, PATH_SEARCH }
}
