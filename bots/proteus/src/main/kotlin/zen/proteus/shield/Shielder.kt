package zen.proteus.shield

import robocode.AdvancedRobot
import robocode.Rules
import zen.proteus.control.Controls
import zen.proteus.core.Angles
import zen.proteus.state.BotState
import zen.proteus.state.GameState
import zen.proteus.state.HitRate
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Bullet shielding: against an enemy whose aim is fully predictable, stand
 * nearly still and shoot their bullets out of the air. [AimPredictors]
 * classifies their aim online from resolved shots; once it is confident and
 * they already struggle to hit us, [active] engages and replaces normal
 * movement and aiming entirely. We exit when they stop shooting, when the
 * shield bleeds too much, or when the classifier loses them — then hit-rate
 * stats are reset (shielding distorts them) and normal mode resumes.
 */
internal class Shielder(
    private val robot: AdvancedRobot,
) {
    var active = false
        private set

    private val predictors = AimPredictors()
    private val pendingShots = ArrayList<PendingShot>()
    private var bestPredictor: Pair<AimPredictors.Id, Double>? = null
    private var plan: Plan? = null
    private var damageTaken = 0.0
    private var lastShotTime = Long.MIN_VALUE

    private class PendingShot(
        val originX: Double,
        val originY: Double,
        val power: Double,
        val fireTime: Long,
        val speed: Double,
        val predictions: Map<AimPredictors.Id, Double>,
    )

    private class Plan(
        val power: Double,
        val angleRadians: Double,
        val fireTime: Long,
    )

    fun onRoundStart() {
        pendingShots.clear()
        plan = null
        // active and the classifier intentionally persist across rounds.
    }

    /** Entry/exit evaluation; returns true when the mode changed this tick. */
    fun update(
        enemy: BotState?,
        enemyHitRate: HitRate?,
        time: Long,
    ): Boolean {
        if (active) {
            val stopped = time - lastShotTime > STOPPED_FIRING_TICKS
            val bleeding = damageTaken > MAX_SHIELD_DAMAGE
            val lost = predictors.best() == null
            if (stopped || bleeding || lost) {
                active = false
                predictors.reset()
                plan = null
                robot.out.println("SHIELD off stopped=$stopped bleeding=$bleeding lost=$lost damage=$damageTaken")
                return true
            }
            return false
        }
        if (enemy == null) return false
        bestPredictor = predictors.best()
        val confident = bestPredictor != null
        val hittable = enemyHitRate?.overlaps(0.0, ENTRY_MAX_HIT_RATE) == true
        if (confident && hittable && enemy.energy > ENTRY_MIN_ENEMY_ENERGY) {
            active = true
            damageTaken = 0.0
            robot.out.println("SHIELD on predictor=${bestPredictor?.first}")
            return true
        }
        return false
    }

    /** An enemy shot was detected; register it for learning and plan a block. */
    fun onEnemyShot(
        shot: GameState.EnemyShot,
        time: Long,
    ) {
        lastShotTime = time
        val predictions =
            predictors.predict(
                shot.originX,
                shot.originY,
                Rules.getBulletSpeed(shot.power),
                shot.selfAtFire,
                shot.selfAtFirePrev,
            )
        pendingShots.add(
            PendingShot(shot.originX, shot.originY, shot.power, shot.time, Rules.getBulletSpeed(shot.power), predictions),
        )
        if (!active) return
        val best = bestPredictor ?: return
        val predictedAngle =
            Angles.normalizeAbsolute((predictions[best.first] ?: return) + best.second)
        plan = buildPlan(shot, predictedAngle, time)
    }

    /** A bullet ended at (x, y): learn the predictor error, or count damage. */
    fun onBulletEnded(
        x: Double,
        y: Double,
        power: Double,
        time: Long,
        hitUs: Boolean,
    ) {
        val match = matchShot(x, y, power, time)
        if (match != null) {
            pendingShots.remove(match)
            val actualAngle = Angles.absoluteBearingRadians(match.originX, match.originY, x, y)
            predictors.noteResolved(match.predictions, actualAngle)
        }
        if (hitUs) {
            damageTaken += Rules.getBulletDamage(power)
        }
    }

    /** Per-tick shield behavior: body parallel to the enemy line, gun on the
     *  intercept angle, fire when the plan says so. */
    fun shield(
        self: BotState,
        enemy: BotState?,
        controls: Controls,
    ) {
        val currentPlan = plan
        if (currentPlan != null && robot.time >= currentPlan.fireTime && robot.gunHeat == 0.0) {
            val gunTurn =
                Angles.normalizeRelative(currentPlan.angleRadians - robot.gunHeadingRadians)
            controls.gunTurnRadians = gunTurn
            if (abs(gunTurn) < FIRE_TOLERANCE) {
                controls.firePower = currentPlan.power
                plan = null
            }
        } else if (enemy != null) {
            // Idle between plans: hold position, gun at the enemy.
            val aimRadians = Angles.absoluteBearingRadians(self.x, self.y, enemy.x, enemy.y)
            controls.gunTurnRadians = Angles.normalizeRelative(aimRadians - robot.gunHeadingRadians)
        }
        // Body parallel to the enemy line (small, calm silhouette).
        if (enemy != null) {
            val parallel =
                Angles.absoluteBearingRadians(self.x, self.y, enemy.x, enemy.y) + PI / 2.0
            controls.bodyTurnRadians = Angles.normalizeRelative(parallel - self.headingRadians)
        }
        controls.ahead = 0.0
    }

    /** Solve where our bullet can intersect theirs, choosing the cheapest
     *  power that reaches in time; the aim point is offset ~0.1px off their
     *  path so the bullets cross instead of flying exactly co-linear. */
    private fun buildPlan(
        shot: GameState.EnemyShot,
        enemyAngleRadians: Double,
        time: Long,
    ): Plan? {
        val enemySpeed = Rules.getBulletSpeed(shot.power)
        val self = shot.selfAtFire
        for (power in POWERS) {
            val ourSpeed = Rules.getBulletSpeed(power)
            // Their bullet at s ticks from now: origin + dir * enemySpeed * (s + 1).
            // Ours, fired this tick: self + aim * ourSpeed * s.
            var previousF = Double.NaN
            for (s in 1..MAX_CROSS_TICKS) {
                val x = shot.originX + Math.sin(enemyAngleRadians) * enemySpeed * (s + 1)
                val y = shot.originY + Math.cos(enemyAngleRadians) * enemySpeed * (s + 1)
                val f = hypot(x - self.x, y - self.y) - ourSpeed * s
                if (previousF != Double.NaN && previousF > 0.0 && f <= 0.0) {
                    // Linear interpolation of the crossing between s-1 and s.
                    val fraction = previousF / (previousF - f)
                    val crossS = s - 1 + fraction
                    val crossX = shot.originX + Math.sin(enemyAngleRadians) * enemySpeed * (crossS + 1)
                    val crossY = shot.originY + Math.cos(enemyAngleRadians) * enemySpeed * (crossS + 1)
                    val wiggleX = Math.sin(enemyAngleRadians + PI / 2.0) * WIGGLE_OFFSET
                    val wiggleY = Math.cos(enemyAngleRadians + PI / 2.0) * WIGGLE_OFFSET
                    val aim =
                        Angles.absoluteBearingRadians(self.x, self.y, crossX + wiggleX, crossY + wiggleY)
                    return Plan(power, aim, time)
                }
                previousF = f
            }
        }
        return null
    }

    private fun matchShot(
        x: Double,
        y: Double,
        power: Double,
        time: Long,
    ): PendingShot? {
        var best: PendingShot? = null
        var bestDiff = Double.POSITIVE_INFINITY
        for (shot in pendingShots) {
            if (abs(shot.power - power) > POWER_TOLERANCE) continue
            val radius = (time - shot.fireTime + 1) * shot.speed
            val diff = abs(hypot(x - shot.originX, y - shot.originY) - radius)
            if (diff < shot.speed + MATCH_SLACK && diff < bestDiff) {
                best = shot
                bestDiff = diff
            }
        }
        return best
    }

    private companion object {
        val POWERS = doubleArrayOf(0.5, 0.75, 1.0, 1.5, 2.0, 2.5, 3.0)
        const val MAX_CROSS_TICKS = 80
        const val WIGGLE_OFFSET = 0.1
        const val STOPPED_FIRING_TICKS = 300
        const val MAX_SHIELD_DAMAGE = 150.0
        const val ENTRY_MAX_HIT_RATE = 0.12
        const val ENTRY_MIN_ENEMY_ENERGY = 10.0
        const val FIRE_TOLERANCE = 0.001
        const val POWER_TOLERANCE = 0.01
        const val MATCH_SLACK = 18.0
    }
}
