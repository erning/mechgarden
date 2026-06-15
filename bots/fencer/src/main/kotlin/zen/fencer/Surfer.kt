package zen.fencer

import kotlin.math.abs
import kotlin.math.hypot

/**
 * Wave surfer.
 *
 * Predicts candidate orbit directions to incoming waves, reads learned danger
 * at arrival, and drives the lower-cost candidate. Against guns that are already
 * landing hits, it can switch to a GoTo candidate that stops at intermediate
 * guess factors.
 */
class Surfer(
    private val profile: SurfProfile = SurfProfile.DEFAULT,
) {
    /** The per-enemy multi-buffer danger model; swapped to the opponent's
     * registry entry on first sight ([adoptEnemy]) so the full ensemble
     * survives Robocode's per-round robot rebuild. */
    private var danger = DangerBuffers()
    private var lastDir = 1
    private val rng = java.util.Random()

    /** Enemy's hits on us / total shots at us, accumulated across the battle —
     * the gate signal for whether GoTo is worth engaging. */
    private var enemyHits = 0.0
    private var enemyShots = 0.0

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
        val sorted =
            waves
                .filter { !it.hasPassed(now, self.x, self.y) }
                .sortedBy { hypot(self.x - it.sourceX, self.y - it.sourceY) - it.radius(now) }
        val incoming = sorted.firstOrNull()
        if (incoming == null) {
            if (enemy != null) motion.orbit(enemy.x, enemy.y, lastDir)
            return
        }
        // Second-nearest wave, only when the profile anticipates it (MultiWave).
        val second = if (profile.secondWaveDiscount > 0.0) sorted.getOrNull(1) else null

        // GoTo only against guns that actually hit us a lot (gateOpen) — there the
        // ability to settle on an intermediate guess factor the gun doesn't expect
        // pays off. Against simple/low-hit guns it's a net loss (it slows us and
        // makes us more predictable), so we keep the plain full-speed orbit.
        if (gateOpen()) {
            surfGoTo(now, incoming, second, self, motion, fieldWidth, fieldHeight)
            return
        }

        // Evaluate each orbit direction × candidate speed; pick the lowest cost.
        // +1 first so cost ties keep clockwise (the original tie-break); full
        // speed listed first so speed ties keep tangential speed.
        var bestDir = lastDir
        var bestSpeed = Kinematics.MAX_VELOCITY
        var bestCost = Double.MAX_VALUE
        for (dir in intArrayOf(1, -1)) {
            for (speed in profile.speeds) {
                var c = cost(incoming, second, self, dir, speed, enemy, now, fieldWidth, fieldHeight)
                if (dir == lastDir) c -= profile.inertiaBonus
                if (profile.tieBreakNoise > 0.0) c += rng.nextDouble() * profile.tieBreakNoise
                if (c < bestCost) {
                    bestCost = c
                    bestDir = dir
                    bestSpeed = speed
                }
            }
        }
        lastDir = bestDir
        motion.orbit(incoming.sourceX, incoming.sourceY, bestDir, bestSpeed)
    }

    /**
     * GoTo dodge: for each orbit direction, roll the full-speed orbit forward and
     * score the danger at *every* position we could brake to a stop at before the
     * wave breaks — not just the max-escape endpoint a plain two-candidate surfer
     * is stuck with. Settling on an intermediate guess factor is what an
     * anti-surfer gun (which learns we always run to ±max-escape) can't predict.
     * We then drive that far and stop. Only used when [gateOpen].
     */
    private fun surfGoTo(
        now: Long,
        incoming: EnemyWave,
        second: EnemyWave?,
        self: Kinematics.Pose,
        motion: MotionController,
        fieldWidth: Double,
        fieldHeight: Double,
    ) {
        var bestDir = lastDir
        var bestDist = Double.MAX_VALUE
        var bestRunThrough = true
        var bestCost = Double.MAX_VALUE
        for (dir in intArrayOf(lastDir, -lastDir)) {
            val reach = reachable(incoming, self, dir, now, fieldWidth, fieldHeight)
            val maxArc = reach.last().dist.coerceAtLeast(1.0)
            for (d in reach) {
                var c = waveDanger(incoming, d.pose.x, d.pose.y)
                if (second != null) {
                    c += profile.secondWaveDiscount * waveDanger(second, d.pose.x, d.pose.y)
                }
                c += profile.wallWeight * wallRisk(d.pose.x, d.pose.y, fieldWidth, fieldHeight)
                // Keep-speed bias: braking short of full escape pays a small surcharge,
                // so we only settle on a slower/intermediate stop when it's clearly
                // safer — staying fast by default avoids the lost-tangential-speed trap.
                c += STOP_BIAS * (1.0 - d.dist / maxArc)
                if (c < bestCost) {
                    bestCost = c
                    bestDir = dir
                    bestDist = d.dist
                    bestRunThrough = d === reach.last()
                }
            }
        }
        lastDir = bestDir
        // Drive. The run-through endpoint was *scored* at full speed, so it must
        // be *driven* at full speed too: an arc-length setAhead would auto-brake
        // over the last ticks before the wave breaks (the engine brakes to stop
        // within the commanded distance — Kinematics.maxVelocityForDistance),
        // silently bleeding tangential speed on every wave. Cruise for the
        // run-through; brake-to-arc only for a genuine stop candidate.
        if (bestRunThrough) {
            motion.orbit(incoming.sourceX, incoming.sourceY, bestDir, Kinematics.MAX_VELOCITY)
        } else {
            motion.orbit(incoming.sourceX, incoming.sourceY, bestDir, Kinematics.MAX_VELOCITY, bestDist)
        }
    }

    /** One reachable stop point on the orbit: a [pose] we'd hold when the wave
     * breaks, [dist] pixels of arc travel away (so the driver brakes to it). */
    private data class Dest(
        val pose: Kinematics.Pose,
        val dist: Double,
    )

    /**
     * The positions we could be holding when [wave] breaks if we orbit [dir]:
     * roll the full-speed orbit forward (wall-smoothed) tick by tick until the
     * wave front reaches us, accumulating arc length. Each tick's pose is a
     * candidate stop point (we could brake to a stop there instead of running on
     * to full escape); the start pose (arc 0 = brake now) leads the list.
     */
    private fun reachable(
        wave: EnemyWave,
        from: Kinematics.Pose,
        dir: Int,
        now: Long,
        fieldWidth: Double,
        fieldHeight: Double,
    ): List<Dest> {
        val dests = ArrayList<Dest>(MAX_TICKS)
        var pose = from
        var arc = 0.0
        dests += Dest(pose, 0.0)
        var ticks = 0
        while (ticks < MAX_TICKS) {
            val centerToUs = Angles.absoluteBearing(wave.sourceX, wave.sourceY, pose.x, pose.y)
            val range = hypot(pose.x - wave.sourceX, pose.y - wave.sourceY)
            val tilted = centerToUs + dir * (90.0 + Distancing.tilt(range))
            val desired = WallSmoothing.smoothedHeading(pose.x, pose.y, tilted, dir > 0, fieldWidth, fieldHeight)
            pose = Kinematics.step(pose, desired, Kinematics.MAX_VELOCITY)
            arc += abs(pose.velocity)
            ticks++
            dests += Dest(pose, arc)
            if (wave.radius(now + ticks) >= hypot(pose.x - wave.sourceX, pose.y - wave.sourceY)) break
        }
        return dests
    }

    /** Open GoTo once the enemy has shown a high enough hit rate against us
     * (a strong adaptive gun) over a meaningful sample — otherwise stay on the
     * plain full-speed orbit, which is better against simple guns. */
    private fun gateOpen(): Boolean = enemyShots >= GATE_MIN_SHOTS && enemyHits / enemyShots >= GATE_THRESHOLD

    /** Adopt [name]'s per-enemy danger model (call before any stat is used). */
    fun adoptEnemy(name: String) {
        danger = DangerBuffers.forEnemy(name)
    }

    /** Snapshot the learned surf-danger model + enemy hit-rate counts. */
    fun snapshot(): DoubleArray = danger.snapshot() + doubleArrayOf(enemyHits, enemyShots)

    fun restore(data: DoubleArray) {
        val dangerSize = danger.snapshot().size
        when (data.size) {
            dangerSize + 2 -> {
                // The static per-enemy registry outlives rounds; avoid clobbering
                // richer in-battle state if it is already warm.
                if (!danger.isWarm()) danger.restore(data.copyOfRange(0, dangerSize))
                enemyHits = data[dangerSize]
                enemyShots = data[dangerSize + 1]
            }
            dangerSize -> if (!danger.isWarm()) danger.restore(data)
        }
    }

    /** Learn a wave that fully swept past us as a visit — a shot that missed
     * (feeds the hit-rate gate's denominator). The wave carries the precise GF
     * interval our hull covered during the crossing; ([px], [py]) is only the
     * fallback footprint if a scan gap skipped the crossing ticks. */
    fun learnVisit(
        wave: EnemyWave,
        px: Double,
        py: Double,
    ) {
        if (wave.coveredLowGf.isNaN()) wave.cover(px, py)
        danger.registerVisit(wave.features, wave.coveredLowGf, wave.coveredHighGf, VISIT_WEIGHT)
        enemyShots += 1.0
    }

    /** Learn a wave that actually hit us — the bullet's position ([px], [py])
     * is exact, so it registers sharply, weighted heavier (and counts toward
     * the enemy's hit rate). */
    fun learnHit(
        wave: EnemyWave,
        px: Double,
        py: Double,
    ) {
        danger.registerHit(wave.features, wave.guessFactor(px, py), HIT_WEIGHT)
        enemyHits += 1.0
        enemyShots += 1.0
    }

    /** Hull-coverage danger read for standing at ([px], [py]) when [wave]
     * arrives: the mean danger over the GF window our hull would occupy. */
    private fun waveDanger(
        wave: EnemyWave,
        px: Double,
        py: Double,
    ): Double {
        val gf = wave.guessFactor(px, py)
        val half = wave.hullHalfGf(hypot(px - wave.sourceX, py - wave.sourceY))
        return wave.dangerWindow(gf - half, gf + half)
    }

    /** Fused danger profile for a new wave fired in [features]'s situation —
     * baked once at fire time, carried by the wave ([EnemyWave.dangerBins]). */
    fun bakeDanger(features: WaveFeatures): DoubleArray = danger.bake(features)

    /**
     * Position-risk cost of orbiting [dir] until [wave] arrives: wave danger plus
     * small wall and distance tie-breakers.
     */
    private fun cost(
        wave: EnemyWave,
        second: EnemyWave?,
        from: Kinematics.Pose,
        dir: Int,
        speed: Double,
        enemy: Snapshot?,
        now: Long,
        fieldWidth: Double,
        fieldHeight: Double,
    ): Double {
        val end = predictArrival(wave, from, dir, speed, now, fieldWidth, fieldHeight)
        var c = waveDanger(wave, end.x, end.y)
        // Anticipated second wave: where this same orbit would put us when it
        // arrives, discounted (MultiWave — don't dodge wave 1 into wave 2).
        if (second != null) {
            val end2 = predictArrival(second, from, dir, speed, now, fieldWidth, fieldHeight)
            c += profile.secondWaveDiscount * waveDanger(second, end2.x, end2.y)
        }
        c += profile.wallWeight * wallRisk(end.x, end.y, fieldWidth, fieldHeight)
        if (enemy != null && DISTANCE_WEIGHT != 0.0) {
            c += DISTANCE_WEIGHT * abs(hypot(end.x - enemy.x, end.y - enemy.y) - SWEET_DISTANCE) / SWEET_SCALE
        }
        return c
    }

    /** Wall/corner proximity risk: each nearby wall adds risk, so a corner (two
     * walls close) is penalized roughly twice — there our escape room is least. */
    private fun wallRisk(
        x: Double,
        y: Double,
        fieldWidth: Double,
        fieldHeight: Double,
    ): Double = nearWall(x) + nearWall(fieldWidth - x) + nearWall(y) + nearWall(fieldHeight - y)

    private fun nearWall(distanceToWall: Double): Double = (1.0 - distanceToWall / WALL_RANGE).coerceAtLeast(0.0)

    /** Roll our orbit ([dir]) forward, wall-smoothed, until [wave]'s front reaches
     * our predicted position; return that position. */
    private fun predictArrival(
        wave: EnemyWave,
        from: Kinematics.Pose,
        dir: Int,
        maxSpeed: Double,
        now: Long,
        fieldWidth: Double,
        fieldHeight: Double,
    ): Kinematics.Pose {
        var pose = from
        var ticks = 0
        while (ticks < MAX_TICKS) {
            val centerToUs = Angles.absoluteBearing(wave.sourceX, wave.sourceY, pose.x, pose.y)
            val range = hypot(pose.x - wave.sourceX, pose.y - wave.sourceY)
            val tilted = centerToUs + dir * (90.0 + Distancing.tilt(range))
            val desired = WallSmoothing.smoothedHeading(pose.x, pose.y, tilted, dir > 0, fieldWidth, fieldHeight)
            pose = Kinematics.step(pose, desired, maxSpeed)
            ticks++
            if (wave.radius(now + ticks) >= hypot(pose.x - wave.sourceX, pose.y - wave.sourceY)) break
        }
        return pose
    }

    private companion object {
        const val VISIT_WEIGHT = 0.5
        const val HIT_WEIGHT = 60.0
        const val MAX_TICKS = 200

        // Small tie-breakers; wave danger remains dominant.
        const val WALL_RANGE = 120.0
        const val DISTANCE_WEIGHT = 0.0 // distance term off: it pushed us to worse gun range
        const val SWEET_DISTANCE = 450.0
        const val SWEET_SCALE = 400.0

        const val STOP_BIAS = 0.02

        // Engage GoTo only after a meaningful enemy hit-rate sample.
        const val GATE_THRESHOLD = 0.15
        const val GATE_MIN_SHOTS = 25.0
    }
}
