package zen.mirage

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Chooses the safer escape direction while a behavior-based ram threat is active.
 *
 * Both candidate directions are rolled forward against a simple pursuer model.
 * The planner prefers the path that preserves separation and open wall space, but
 * only contributes a bounded direction penalty to [Surfer], so a clearly safer
 * incoming-wave dodge can still win. A collision arms a short emergency latch;
 * this covers the scans immediately after contact even if pursuit evidence is
 * temporarily noisy.
 */
class AntiRamPlanner {
    data class Plan(
        val preferredDirection: Int,
        val targetRange: Double,
        val directionPenalty: Double,
        val escapeHeadingRadians: Double?,
        val emergency: Boolean,
    )

    private var emergencyTicks = 0L
    private var preferredDirection = 1
    private var lastPositiveScore = 0.0
    private var lastNegativeScore = 0.0

    fun recordCollision() {
        emergencyTicks = EMERGENCY_LATCH_TICKS
    }

    fun plan(
        frame: Tracker.Frame,
        threat: RamThreatDetector.Snapshot,
        fieldWidth: Double,
        fieldHeight: Double,
    ): Plan? {
        val emergency = emergencyTicks > 0L
        val closeThreat =
            threat.active &&
                (
                    frame.enemy.distance <= MOVEMENT_ACTIVATION_DISTANCE ||
                        threat.collisionTicks <= MOVEMENT_ACTIVATION_COLLISION_TICKS
                )
        if (!closeThreat && !emergency) return null
        if (emergency) emergencyTicks--

        lastPositiveScore = escapeScore(frame, 1, fieldWidth, fieldHeight)
        lastNegativeScore = escapeScore(frame, -1, fieldWidth, fieldHeight)
        if (lastPositiveScore > lastNegativeScore + SWITCH_MARGIN) {
            preferredDirection = 1
        } else if (lastNegativeScore > lastPositiveScore + SWITCH_MARGIN) {
            preferredDirection = -1
        }

        val urgency =
            if (threat.collisionTicks.isFinite()) {
                (1.0 - threat.collisionTicks / URGENT_COLLISION_TICKS).coerceIn(0.0, 1.0)
            } else {
                0.0
            }
        val penalty =
            BASE_DIRECTION_PENALTY +
                threat.confidence * CONFIDENCE_PENALTY +
                urgency * URGENCY_PENALTY +
                if (emergency) EMERGENCY_PENALTY else 0.0
        val directEscape =
            emergency ||
                frame.enemy.distance <= DIRECT_ESCAPE_DISTANCE ||
                threat.collisionTicks <= DIRECT_ESCAPE_COLLISION_TICKS
        val escapeHeadingRadians =
            if (directEscape) {
                val centerToSelfRadians = Angles.absoluteBearing(frame.enemy.x, frame.enemy.y, frame.self.x, frame.self.y)
                WallSmoothing.smoothedHeadingRadians(
                    frame.self.x,
                    frame.self.y,
                    centerToSelfRadians + preferredDirection * ESCAPE_OFFSET_RADIANS,
                    preferredDirection > 0,
                    fieldWidth,
                    fieldHeight,
                )
            } else {
                null
            }
        return Plan(
            preferredDirection = preferredDirection,
            targetRange = ESCAPE_TARGET_RANGE,
            directionPenalty = penalty,
            escapeHeadingRadians = escapeHeadingRadians,
            emergency = emergency,
        )
    }

    internal fun escapeScore(
        frame: Tracker.Frame,
        direction: Int,
        fieldWidth: Double,
        fieldHeight: Double,
    ): Double {
        var selfX = frame.self.x
        var selfY = frame.self.y
        var selfHeadingRadians = frame.self.headingRadians
        var selfVelocity = frame.self.velocity
        var enemyX = frame.enemy.x
        var enemyY = frame.enemy.y
        var enemyHeadingRadians = frame.enemy.headingRadians
        var enemyVelocity = frame.enemy.velocity
        var minimumSeparation = Double.POSITIVE_INFINITY
        var minimumWallSpace = Double.POSITIVE_INFINITY
        var closePressure = 0.0

        repeat(PREDICTION_TICKS) {
            val centerToSelfRadians = Angles.absoluteBearing(enemyX, enemyY, selfX, selfY)
            val desiredSelfHeadingRadians = centerToSelfRadians + direction * ESCAPE_OFFSET_RADIANS
            val selfGoHeadingRadians =
                WallSmoothing.smoothedHeadingRadians(
                    selfX,
                    selfY,
                    desiredSelfHeadingRadians,
                    direction > 0,
                    fieldWidth,
                    fieldHeight,
                )
            val nextSelf = advance(selfX, selfY, selfHeadingRadians, selfVelocity, selfGoHeadingRadians)
            selfX = nextSelf.x.coerceIn(Kinematics.HALF_BOT, fieldWidth - Kinematics.HALF_BOT)
            selfY = nextSelf.y.coerceIn(Kinematics.HALF_BOT, fieldHeight - Kinematics.HALF_BOT)
            selfHeadingRadians = nextSelf.headingRadians
            selfVelocity = nextSelf.velocity

            val desiredEnemyHeadingRadians = Angles.absoluteBearing(enemyX, enemyY, selfX, selfY)
            val nextEnemy = advance(enemyX, enemyY, enemyHeadingRadians, enemyVelocity, desiredEnemyHeadingRadians)
            enemyX = nextEnemy.x.coerceIn(Kinematics.HALF_BOT, fieldWidth - Kinematics.HALF_BOT)
            enemyY = nextEnemy.y.coerceIn(Kinematics.HALF_BOT, fieldHeight - Kinematics.HALF_BOT)
            enemyHeadingRadians = nextEnemy.headingRadians
            enemyVelocity = nextEnemy.velocity

            val predictedSeparation = hypot(selfX - enemyX, selfY - enemyY)
            minimumSeparation = minOf(minimumSeparation, predictedSeparation)
            minimumWallSpace =
                minOf(
                    minimumWallSpace,
                    selfX - Kinematics.HALF_BOT,
                    fieldWidth - Kinematics.HALF_BOT - selfX,
                    selfY - Kinematics.HALF_BOT,
                    fieldHeight - Kinematics.HALF_BOT - selfY,
                )
            val closeRatio = ((CLOSE_PRESSURE_RANGE - predictedSeparation) / CLOSE_PRESSURE_RANGE).coerceAtLeast(0.0)
            closePressure += closeRatio * closeRatio
        }

        val finalSeparation = hypot(selfX - enemyX, selfY - enemyY)
        return minimumSeparation * MINIMUM_SEPARATION_WEIGHT +
            finalSeparation * FINAL_SEPARATION_WEIGHT +
            minimumWallSpace.coerceAtLeast(0.0) * WALL_SPACE_WEIGHT -
            closePressure * CLOSE_PRESSURE_WEIGHT
    }

    fun debugSummary(): String =
        "antiRam=${if (emergencyTicks > 0L) "emergency" else "ready"}/$preferredDirection " +
            "ramScore=${"%.1f".format(lastPositiveScore)}/${"%.1f".format(lastNegativeScore)}"

    private fun advance(
        x: Double,
        y: Double,
        headingRadians: Double,
        velocity: Double,
        goHeadingRadians: Double,
    ): Kinematics.Pose {
        var turnRadians = Angles.normalizeRelative(goHeadingRadians - headingRadians)
        var driveSign = 1
        if (abs(turnRadians) > Angles.HALF_PI) {
            turnRadians = Angles.normalizeRelative(turnRadians + Angles.PI)
            driveSign = -1
        }
        val nextHeadingRadians =
            Angles.normalizeAbsolute(
                headingRadians + turnRadians.coerceIn(-Kinematics.maxTurnRateRadians(velocity), Kinematics.maxTurnRateRadians(velocity)),
            )
        val nextVelocity = Kinematics.nextVelocity(velocity, driveSign)
        return Kinematics.Pose(
            x = x + sin(nextHeadingRadians) * nextVelocity,
            y = y + cos(nextHeadingRadians) * nextVelocity,
            headingRadians = nextHeadingRadians,
            velocity = nextVelocity,
        )
    }

    private companion object {
        const val PREDICTION_TICKS = 28
        const val EMERGENCY_LATCH_TICKS = 18L
        const val ESCAPE_TARGET_RANGE = 540.0
        const val URGENT_COLLISION_TICKS = 30.0
        const val MOVEMENT_ACTIVATION_DISTANCE = 220.0
        const val MOVEMENT_ACTIVATION_COLLISION_TICKS = 35.0
        const val DIRECT_ESCAPE_DISTANCE = 110.0
        const val DIRECT_ESCAPE_COLLISION_TICKS = 12.0
        const val SWITCH_MARGIN = 6.0
        const val BASE_DIRECTION_PENALTY = 0.03
        const val CONFIDENCE_PENALTY = 0.04
        const val URGENCY_PENALTY = 0.04
        const val EMERGENCY_PENALTY = 0.05
        const val CLOSE_PRESSURE_RANGE = 180.0
        const val MINIMUM_SEPARATION_WEIGHT = 1.6
        const val FINAL_SEPARATION_WEIGHT = 0.45
        const val WALL_SPACE_WEIGHT = 0.35
        const val CLOSE_PRESSURE_WEIGHT = 16.0
        val ESCAPE_OFFSET_RADIANS = Math.toRadians(35.0)
    }
}
