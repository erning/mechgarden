package zen.proteus.aim

import zen.proteus.core.Battlefield
import zen.proteus.state.BotState
import zen.proteus.wave.Wave
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sin

/**
 * Feature pool for the KNN guns, computed once per aim wave at creation and for
 * the live query at aim time. Everything is normalized to roughly [0, 1];
 * [WEIGHTS] are the per-dimension embedding weights (hand-tuned until the M8
 * offline training loop replaces them).
 */
internal object Features {
    const val COUNT = 11

    const val BFT = 0
    const val LAT_VEL = 1
    const val ADV_VEL = 2
    const val VELOCITY = 3
    const val ACCEL = 4
    const val DIR_CHANGE = 5
    const val DISTANCE = 6
    const val WALL_AHEAD = 7
    const val WALL_REVERSE = 8
    const val CURRENT_GF = 9
    const val VIRTUALITY = 10

    // Learned offline by EmbeddingTrainer on basic+classic datasets
    // (24 epochs, density loss 1.4267 -> 1.3897); was hand-tuned before.
    val WEIGHTS =
        doubleArrayOf(
            1.8903, // BFT
            1.2381, // LAT_VEL
            1.1851, // ADV_VEL
            0.6180, // VELOCITY
            0.5407, // ACCEL
            0.9389, // DIR_CHANGE
            0.7590, // DISTANCE
            1.5148, // WALL_AHEAD
            1.4971, // WALL_REVERSE
            0.4501, // CURRENT_GF
            0.3679, // VIRTUALITY
        )

    fun compute(
        self: BotState,
        enemy: BotState,
        enemyPrev: BotState?,
        wave: Wave,
        field: Battlefield,
        virtuality: Int,
        dirChangeTicks: Int,
    ): DoubleArray {
        val distance = self.distanceTo(enemy)
        val bft = distance / wave.speed
        val bearingRadians = wave.directAngleRadians
        val latVel = enemy.velocity * sin(enemy.headingRadians - bearingRadians)
        val advVel = enemy.velocity * kotlin.math.cos(enemy.headingRadians - bearingRadians)
        val accel = if (enemyPrev != null && enemyPrev.time == enemy.time - 1) enemy.velocity - enemyPrev.velocity else 0.0

        val features = DoubleArray(COUNT)
        features[BFT] = bft / 60.0
        features[LAT_VEL] = (latVel / 8.0 + 1.0) / 2.0
        features[ADV_VEL] = (advVel + 16.0) / 8.0
        features[VELOCITY] = abs(enemy.velocity) / 8.0
        features[ACCEL] = (accel + 2.0) / 4.0
        features[DIR_CHANGE] = min(dirChangeTicks.toDouble(), 70.0) / 70.0
        features[DISTANCE] = distance / 800.0
        features[WALL_AHEAD] = wallDistance(enemy.x, enemy.y, enemy.headingRadians, field) / 800.0
        features[WALL_REVERSE] = wallDistance(enemy.x, enemy.y, enemy.headingRadians + Math.PI, field) / 800.0
        features[CURRENT_GF] = (
            wave.guessFactor(
                zen.proteus.core.Angles
                    .absoluteBearingRadians(wave.originX, wave.originY, enemy.x, enemy.y),
            ) + 1.0
        ) / 2.0
        features[VIRTUALITY] = virtuality / 5.0
        return features
    }

    /** Distance from (x, y) to the field edge along [headingRadians]. */
    fun wallDistance(
        x: Double,
        y: Double,
        headingRadians: Double,
        field: Battlefield,
    ): Double {
        val dx = sin(headingRadians)
        val dy = kotlin.math.cos(headingRadians)
        var t = Double.POSITIVE_INFINITY
        val half = Battlefield.ROBOT_HALF_SIZE
        if (dx > 1e-9) t = min(t, (field.width - half - x) / dx)
        if (dx < -1e-9) t = min(t, (half - x) / dx)
        if (dy > 1e-9) t = min(t, (field.height - half - y) / dy)
        if (dy < -1e-9) t = min(t, (half - y) / dy)
        return if (t.isInfinite()) 0.0 else t
    }
}
