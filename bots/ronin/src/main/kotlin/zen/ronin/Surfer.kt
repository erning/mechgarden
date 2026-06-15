package zen.ronin

import kotlin.math.hypot

/**
 * Wave surfer. Each tick it predicts the orbit-arrival position for each
 * direction, reads the (shadow-aware) enemy danger there, and drives the cheaper
 * orbit. Full speed in both directions is the hardest-to-hit base — max
 * tangential velocity, no slow/stop moments a gun can pick off — and [BulletShadows]
 * physically protect the guess factors our own bullets cross, so the surfer can
 * stand in those GFs at zero danger.
 *
 * A flattener (statistically de-correlating our visits) was tried and rejected:
 * against a strong adaptive GF gun it steered us off genuinely-safe GFs onto
 * rarely-visited but still-dangerous ones, because such a gun predicts *any*
 * statistical pattern. Physical shadows, which the gun cannot predict around, are
 * what actually reduce incoming damage here.
 */
class Surfer {
    private var danger: DangerModel = DangerModel()
    private var lastDir = 1
    private val rng = java.util.Random()
    var profile = MovementProfileSelector.Profile.BASE

    /** Choose and drive this tick's dodge. */
    fun surf(
        now: Long,
        self: Kinematics.Pose,
        enemy: Snapshot?,
        waves: List<EnemyWave>,
        motion: MotionController,
        fieldWidth: Double,
        fieldHeight: Double,
    ) {
        // Nearest two not-yet-arrived waves in a single pass (no list allocation).
        var incoming: EnemyWave? = null
        var incomingKey = Double.MAX_VALUE
        var second: EnemyWave? = null
        var secondKey = Double.MAX_VALUE
        for (w in waves) {
            if (w.hasPassed(now, self.x, self.y)) continue
            val key = hypot(self.x - w.sourceX, self.y - w.sourceY) - w.radius(now)
            if (key < incomingKey) {
                second = incoming
                secondKey = incomingKey
                incoming = w
                incomingKey = key
            } else if (key < secondKey) {
                second = w
                secondKey = key
            }
        }
        if (incoming == null) {
            if (enemy != null) motion.orbit(enemy.x, enemy.y, lastDir)
            return
        }

        // Score each orbit direction by the (shadow-aware) enemy danger at the
        // predicted arrival, plus the anticipated second wave and wall risk; drive
        // the cheaper orbit. Tiny inertia + tie-break noise keep us from locking
        // into a deterministic pattern the enemy gun can read.
        var bestDir = lastDir
        var bestCost = Double.MAX_VALUE
        for (dir in intArrayOf(1, -1)) {
            val end = predictArrival(incoming, self, dir, now, fieldWidth, fieldHeight)
            var c = waveDanger(incoming, end.x, end.y)
            if (second != null) {
                val end2 = predictArrival(second, self, dir, now, fieldWidth, fieldHeight)
                c += profile.secondWaveDiscount * waveDanger(second, end2.x, end2.y)
            }
            c += profile.wallWeight * wallRisk(end.x, end.y, fieldWidth, fieldHeight)
            if (dir == lastDir) c -= profile.inertiaBonus
            c += rng.nextDouble() * profile.tieNoise
            if (c < bestCost) {
                bestCost = c
                bestDir = dir
            }
        }

        if (profile.stopAllowed) {
            val stop = predictArrivalStop(incoming, self, now)
            var stopCost = waveDanger(incoming, stop.x, stop.y)
            if (second != null) {
                val stop2 = predictArrivalStop(second, self, now)
                stopCost += profile.secondWaveDiscount * waveDanger(second, stop2.x, stop2.y)
            }
            stopCost += profile.wallWeight * wallRisk(stop.x, stop.y, fieldWidth, fieldHeight)
            stopCost += profile.stopPenalty
            stopCost += rng.nextDouble() * profile.tieNoise
            if (stopCost < bestCost) {
                motion.driveAlong(self.headingDeg, STOP_SPEED)
                return
            }
        }

        lastDir = bestDir
        motion.orbit(incoming.sourceX, incoming.sourceY, bestDir)
    }

    /** Adopt [name]'s per-enemy danger model (call before any stat is used). */
    fun adoptEnemy(name: String) {
        danger = DangerModel.forEnemy(name)
    }

    /** Fused danger profile for a new wave fired in [features]'s situation — baked
     * once at fire time and carried by the wave. */
    fun bakeDanger(features: WaveFeatures): DoubleArray = danger.bake(features)

    /** Learn a wave that swept our hull without hitting — a missed shot. */
    fun learnVisit(
        wave: EnemyWave,
        px: Double,
        py: Double,
    ) {
        if (wave.coveredLowGf.isNaN()) wave.cover(px, py)
        danger.registerVisit(wave.features, wave.coveredLowGf, wave.coveredHighGf, VISIT_WEIGHT)
    }

    /** Learn a wave that hit us — the bullet's position is exact, weighted heavy. */
    fun learnHit(
        wave: EnemyWave,
        px: Double,
        py: Double,
    ) {
        danger.registerHit(wave.features, wave.guessFactor(px, py), HIT_WEIGHT)
    }

    /** Hull-window (shadow-aware) enemy danger at ([px], [py]) when [wave] arrives. */
    private fun waveDanger(
        wave: EnemyWave,
        px: Double,
        py: Double,
    ): Double {
        val gf = wave.guessFactor(px, py)
        val half = wave.hullHalfGf(hypot(px - wave.sourceX, py - wave.sourceY))
        return wave.dangerWindow(gf - half, gf + half)
    }

    private fun wallRisk(
        x: Double,
        y: Double,
        fieldWidth: Double,
        fieldHeight: Double,
    ): Double = nearWall(x) + nearWall(fieldWidth - x) + nearWall(y) + nearWall(fieldHeight - y)

    private fun nearWall(distanceToWall: Double): Double = (1.0 - distanceToWall / WALL_RANGE).coerceAtLeast(0.0)

    /** Roll our orbit ([dir]) forward, wall-smoothed, until [wave]'s front reaches
     * us. Inlines [Kinematics.step] with its defaults (max speed, no distance
     * brake) into locals so the ~30–70-tick loop allocates nothing per tick. */
    private fun predictArrival(
        wave: EnemyWave,
        from: Kinematics.Pose,
        dir: Int,
        now: Long,
        fieldWidth: Double,
        fieldHeight: Double,
    ): Kinematics.Pose {
        var x = from.x
        var y = from.y
        var heading = from.headingDeg
        var velocity = from.velocity
        var ticks = 0
        while (ticks < MAX_TICKS) {
            val centerToUs = Angles.absoluteBearing(wave.sourceX, wave.sourceY, x, y)
            val range = hypot(x - wave.sourceX, y - wave.sourceY)
            val tilted = centerToUs + dir * (90.0 + Distancing.tilt(range))
            val desired = WallSmoothing.smoothedHeading(x, y, tilted, dir > 0, fieldWidth, fieldHeight)
            // Kinematics.step(pose, desired) inlined (maxSpeed = MAX, distance = ∞):
            var angle = Angles.normalizeRelative(desired - heading)
            var driveSign = 1
            if (kotlin.math.abs(angle) > 90.0) {
                angle = Angles.normalizeRelative(angle + 180.0)
                driveSign = -1
            }
            val maxTurn = Kinematics.maxTurnRateDeg(velocity)
            heading = Angles.normalizeAbsolute(heading + angle.coerceIn(-maxTurn, maxTurn))
            velocity = Kinematics.nextVelocity(velocity, driveSign)
            val rad = Math.toRadians(heading)
            x += kotlin.math.sin(rad) * velocity
            y += Math.cos(rad) * velocity
            ticks++
            if (wave.radius(now + ticks) >= hypot(x - wave.sourceX, y - wave.sourceY)) break
        }
        return Kinematics.Pose(x, y, heading, velocity)
    }

    /** Roll forward with max velocity zero until [wave]'s front reaches us. */
    private fun predictArrivalStop(
        wave: EnemyWave,
        from: Kinematics.Pose,
        now: Long,
    ): Kinematics.Pose {
        var x = from.x
        var y = from.y
        val heading = from.headingDeg
        var velocity = from.velocity
        var ticks = 0
        while (ticks < MAX_TICKS) {
            velocity = brakeVelocity(velocity)
            val rad = Math.toRadians(heading)
            x += kotlin.math.sin(rad) * velocity
            y += Math.cos(rad) * velocity
            ticks++
            if (wave.radius(now + ticks) >= hypot(x - wave.sourceX, y - wave.sourceY)) break
        }
        return Kinematics.Pose(x, y, heading, velocity)
    }

    private fun brakeVelocity(velocity: Double): Double =
        when {
            velocity > 0.0 -> maxOf(0.0, velocity - Kinematics.DECELERATION)
            velocity < 0.0 -> minOf(0.0, velocity + Kinematics.DECELERATION)
            else -> 0.0
        }

    /** Persist the backbone danger model across rounds. */
    fun snapshot(): DoubleArray = danger.snapshot()

    fun restore(data: DoubleArray) {
        if (!danger.isWarm()) danger.restore(data)
    }

    private companion object {
        const val VISIT_WEIGHT = 0.5
        const val HIT_WEIGHT = 60.0

        // Orbit-prediction safety cap. The wave always reaches us in
        // distance/bulletSpeed ticks (≤ ~73 even at max range with the slowest
        // bullet, plus a little for outward drift), so this cap never actually
        // binds — 100 is comfortable headroom over the real ~30–70 tick reach.
        const val MAX_TICKS = 100

        const val STOP_SPEED = 0.0
        const val WALL_RANGE = 120.0
    }
}
