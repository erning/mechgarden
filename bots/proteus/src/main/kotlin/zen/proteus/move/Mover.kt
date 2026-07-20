package zen.proteus.move

import zen.proteus.aim.Features
import zen.proteus.control.Controls
import zen.proteus.core.Angles
import zen.proteus.core.Battlefield
import zen.proteus.move.danger.DangerEstimator
import zen.proteus.move.danger.EnemyWave
import zen.proteus.state.BotState
import zen.proteus.state.GameState
import zen.proteus.state.HitRate
import zen.proteus.wave.BulletShadows
import zen.proteus.wave.Wave
import zen.proteus.wave.Waves
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sign
import kotlin.math.sin

/**
 * Movement. Enemy shots become expanding waves (with the feature vector of our
 * movement at their fire time); while any wave is in flight we surf the nearest
 * ones against the [DangerEstimator] ensemble — gated, dynamically weighted,
 * bullet-shadow aware — either with the proven three-option surfer (default) or
 * the best-first path surfer behind [MOVEMENT_ENGINE]. With no waves we fall
 * back to a distance-band orbit.
 *
 * Learned state (estimator ensemble) is kept per enemy in a static registry so
 * it survives Robocode's per-round robot rebuild.
 */
internal class Mover {
    private val waves = Waves()
    private val ourBullets = OurBullets()
    private var estimator: DangerEstimator? = null
    private var surfer: Surfer? = null
    private var pathSurfer: PathSurfer? = null
    private var field: Battlefield? = null
    private var orbitDirection = 1.0
    private var lastPlan: PathSurfer.Plan? = null
    private var lastLatSign = 1.0
    private var dirChangeTicks = 0
    private var enemyAvgPower = 2.0
    private val random = Random()

    fun onRoundStart() {
        waves.clear()
        ourBullets.clear()
        orbitDirection = if (random.nextBoolean()) 1.0 else -1.0
        lastPlan = null
        lastLatSign = 1.0
        dirChangeTicks = 0
        // estimator intentionally survives: it belongs to the per-enemy registry.
    }

    fun onEnemyShot(
        shot: GameState.EnemyShot,
        battlefield: Battlefield,
    ) {
        enemyAvgPower = enemyAvgPower * POWER_DECAY + shot.power * (1.0 - POWER_DECAY)
        val wave =
            Wave(shot.originX, shot.originY, shot.power, shot.time, shot.directAngleRadians, shot.lateralDirection)
        // Features of OUR movement as the enemy saw it at fire time.
        val features =
            Features.compute(shot.enemyAtFire, shot.selfAtFire, shot.selfAtFirePrev, wave, battlefield, 0, dirChangeTicks)
        waves.add(EnemyWave(wave, features, shot.selfAtFire, shot.selfAtFirePrev))
    }

    /** Enemy realized hit rate against us, from the active estimator. */
    fun enemyHitRate(): HitRate? = estimator?.enemyHitRate

    /** Rolling average of the enemy's bullet power. */
    fun enemyAvgPower(): Double = enemyAvgPower

    /** Active enemy waves nearest to reaching us, for shadow-aware aiming. */
    fun surfWaves(
        self: BotState,
        time: Long,
    ): List<EnemyWave> = waves.surfable(self.x, self.y, time)

    /** Our bullets currently in flight, for shadow-aware aiming. */
    fun ourBulletsInFlight(): List<OurBullets.Tracked> = ourBullets.all()

    /** Danger of a GF interval on [entry] with extra hypothetical shadows added. */
    fun dangerWithShadows(
        entry: EnemyWave,
        self: BotState,
        gfLo: Double,
        gfHi: Double,
        extraShadows: List<DoubleArray>,
    ): Double = estimator?.danger(entry, self.x, self.y, gfLo, gfHi, extraShadows) ?: 0.0

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
        hitUs: Boolean,
    ) {
        val matched = waves.matchBullet(x, y, power, time) ?: return
        estimator?.onWaveResolved(matched.entry, matched.entry.wave.guessFactor(matched.angleRadians), hitUs)
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
        if (estimator == null && enemyName != null) {
            estimator = ESTIMATOR_REGISTRY.getOrPut(enemyName) { DangerEstimator() }
        }
        if (surfer == null) surfer = Surfer(battlefield)
        if (pathSurfer == null) pathSurfer = PathSurfer(battlefield)
        field = battlefield
        val currentEstimator = estimator

        if (enemy != null) {
            val bearingRadians = Angles.absoluteBearingRadians(self.x, self.y, enemy.x, enemy.y)
            val latSign = sign(self.velocity * sin(self.headingRadians - bearingRadians))
            if (abs(latSign) > 1e-3 && latSign != lastLatSign) {
                lastLatSign = latSign
                dirChangeTicks = 0
            }
            dirChangeTicks++
        }

        if (selfPrev != null) {
            for (passed in waves.update(selfPrev.x, selfPrev.y, time)) {
                currentEstimator?.onWavePassed(passed.entry)
            }
        }

        val surfWaves = waves.surfable(self.x, self.y, time)
        if (surfWaves.isNotEmpty() && currentEstimator != null && enemy != null) {
            ourBullets.prune(battlefield, time)
            val shadows = HashMap<Wave, List<DoubleArray>>(surfWaves.size)
            for (entry in surfWaves) {
                shadows[entry.wave] = BulletShadows.intervals(entry.wave, ourBullets.all(), self.x, self.y, time)
            }
            when (MOVEMENT_ENGINE) {
                MovementEngine.THREE_OPTION -> {
                    val choice = surfer!!.choose(self, enemy, surfWaves, currentEstimator, shadows, orbitDirection, time)
                    orbitDirection = choice.orbitDirection
                    executeSurf(self, enemy, choice, controls)
                }

                MovementEngine.PATH_SEARCH -> {
                    val plan = pathSurfer!!.plan(self, enemy, surfWaves, currentEstimator, shadows, orbitDirection, time, lastPlan)
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
        const val POWER_DECAY = 0.9

        // THREE_OPTION (default): the proven three-option surfer. PATH_SEARCH:
        // best-first path surfing; it threads narrow danger peaks too eagerly
        // for a crude danger model — kept for A/B re-evaluation now that the
        // ensemble exists.
        val MOVEMENT_ENGINE = MovementEngine.THREE_OPTION

        /** Per-enemy danger ensembles; survive Robocode's per-round robot rebuild. */
        val ESTIMATOR_REGISTRY = HashMap<String, DangerEstimator>()
    }

    enum class MovementEngine { THREE_OPTION, PATH_SEARCH }
}
