package zen.mirage

import kotlin.math.hypot

/**
 * Wave surfer. Each tick it predicts the orbit path for each direction, reads the
 * (shadow-aware) enemy danger at **every point along the predicted path the
 * incoming wave reaches us**, and drives the cheaper orbit.
 *
 * Multi-point path danger is the key difference from a single-arrival-point
 * surfer: a bullet does not just hit us at the wave's terminal tick — it can hit
 * at *any* tick the wave front overlaps our hull, and our GF at those earlier
 * ticks is what a strong gun actually fires at. Sampling danger across the whole
 * crossing window (not just the endpoint) is what keeps a top gun like DrussGT
 * from walking us into an earlier, still-dangerous GF. Full speed in both
 * directions stays the hardest-to-hit base, and [BulletShadows] physically
 * protect the GFs our own bullets cross.
 *
 * Angles in radians. Cost-term weights are fixed. Flattening is available per
 * profile but off by default; against most guns physical bullet shadows (which
 * the gun cannot predict around) reduce incoming damage more reliably than a
 * flattener that shoves us off genuinely-safe GFs.
 */
class Surfer {
    enum class DangerMode {
        MAX,
        WEIGHTED,
        ARRIVAL,
    }

    private var danger: DangerModel = DangerModel()
    private var flattener: VisitFlattener = VisitFlattener()
    private var lastDir = 1
    private val valleyGfs = DoubleArray(MAX_SEARCH_GFS)
    private val valleyCosts = DoubleArray(MAX_SEARCH_GFS)
    private val primaryPath = Path(MAX_TICKS)
    private val secondaryPath = Path(MAX_TICKS)

    /** Choose and drive this tick's dodge. */
    fun surf(
        now: Long,
        self: Kinematics.Pose,
        enemy: Tracker.Frame?,
        waves: List<EnemyWave>,
        motion: MotionController,
        fieldWidth: Double,
        fieldHeight: Double,
        profile: MovementProfileSelector.Profile,
        targetRange: Double,
        dangerMode: DangerMode,
        stopAllowed: Boolean? = null,
        virtualWave: EnemyWave? = null,
    ) {
        // Nearest two not-yet-arrived waves.
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
        if (virtualWave != null && !virtualWave.hasPassed(now, self.x, self.y)) {
            val key = hypot(self.x - virtualWave.sourceX, self.y - virtualWave.sourceY) - virtualWave.radius(now)
            if (key < incomingKey) {
                second = incoming
                secondKey = incomingKey
                incoming = virtualWave
                incomingKey = key
            } else if (key < secondKey) {
                second = virtualWave
                secondKey = key
            }
        }
        if (incoming == null) {
            if (enemy != null) motion.orbitRadians(enemy.enemy.x, enemy.enemy.y, lastDir, targetRange)
            return
        }

        // Multi-path search (BeepBoop-style, simplified): evaluate orbit in both
        // directions at several target speeds, pick the lowest-total-danger path.
        // Different speeds reach different GFs by the time the wave arrives, and a
        // slower path can thread a low-danger GF a full-speed orbit overshoots —
        // this is the core lever a single-direction surfer lacks.
        var bestDir = lastDir
        var bestSpeed = Kinematics.MAX_VELOCITY
        var bestCost = Double.MAX_VALUE
        var bestGoHeadingRadians = Double.NaN
        var bestTargetRange = targetRange
        val speedMode = System.getProperty("mirage.speeds") ?: "full"
        val speeds =
            when (speedMode) {
                "multi" -> MULTI_SPEEDS
                else -> FULL_SPEEDS
            }
        val survivalSearch = profile == MovementProfileSelector.Profile.SURVIVAL_SEARCH
        val structuredSearch = survivalSearch && dangerStructure(incoming) >= MIN_SEARCH_STRUCTURE
        val rangeOffsets = if (structuredSearch) SEARCH_ORBIT_RANGE_OFFSETS else BASE_RANGE_OFFSETS
        for (rangeOffset in rangeOffsets) {
            val pathTargetRange = (targetRange + rangeOffset).coerceIn(MIN_SEARCH_RADIUS, MAX_SEARCH_RADIUS)
            for (dir in DIRECTIONS) {
                for (maxSpeed in speeds) {
                    val path = predictPath(incoming, self, dir, now, fieldWidth, fieldHeight, pathTargetRange, maxSpeed, primaryPath)
                    var c = pathDanger(incoming, path, profile.flattenerWeight, dangerMode)
                    if (second != null) {
                        val path2 = predictPath(second, self, dir, now, fieldWidth, fieldHeight, pathTargetRange, maxSpeed, secondaryPath)
                        c += profile.secondWaveDiscount * pathDanger(second, path2, profile.flattenerWeight, dangerMode)
                    }
                    c += profile.wallWeight * wallRisk(path.x, path.y, fieldWidth, fieldHeight)
                    if (structuredSearch) {
                        c += SEARCH_RANGE_WEIGHT * rangeRisk(path.x, path.y, incoming.sourceX, incoming.sourceY, targetRange)
                    }
                    if (dir == lastDir && maxSpeed >= Kinematics.MAX_VELOCITY - 0.01) c -= inertiaBonus()
                    c += Math.random() * profile.tieNoise
                    if (c < bestCost) {
                        bestCost = c
                        bestDir = dir
                        bestSpeed = maxSpeed
                        bestTargetRange = pathTargetRange
                        bestGoHeadingRadians = Double.NaN
                    }
                }
            }
        }

        if (structuredSearch) {
            val sourceX = incoming.sourceX
            val sourceY = incoming.sourceY
            val candidateCount = collectValleyGuessFactors(incoming)
            for (candidate in 0 until candidateCount) {
                val bearing = bearingForGuessFactor(incoming, valleyGfs[candidate])
                for (offset in SEARCH_RANGE_OFFSETS) {
                    val radius = (targetRange + offset).coerceIn(MIN_SEARCH_RADIUS, MAX_SEARCH_RADIUS)
                    val targetX = (sourceX + kotlin.math.sin(bearing) * radius).coerceIn(SEARCH_MARGIN, fieldWidth - SEARCH_MARGIN)
                    val targetY = (sourceY + kotlin.math.cos(bearing) * radius).coerceIn(SEARCH_MARGIN, fieldHeight - SEARCH_MARGIN)
                    if (hypot(targetX - self.x, targetY - self.y) < MIN_TARGET_DISTANCE) continue
                    val path =
                        predictGotoPath(
                            incoming,
                            self,
                            targetX,
                            targetY,
                            now,
                            targetRange,
                            primaryPath,
                        )
                    var c = pathDanger(incoming, path, profile.flattenerWeight, dangerMode)
                    if (second != null) {
                        val path2 = predictGotoPath(second, self, targetX, targetY, now, targetRange, secondaryPath)
                        c += profile.secondWaveDiscount * pathDanger(second, path2, profile.flattenerWeight, dangerMode)
                    }
                    c += profile.wallWeight * wallRisk(path.x, path.y, fieldWidth, fieldHeight)
                    c += SEARCH_RANGE_WEIGHT * rangeRisk(path.x, path.y, incoming.sourceX, incoming.sourceY, targetRange)
                    c += Math.random() * profile.tieNoise
                    if (c < bestCost) {
                        bestCost = c
                        bestDir = path.firstOrbitDir.ifZero(lastDir)
                        bestSpeed = Kinematics.MAX_VELOCITY
                        bestTargetRange = targetRange
                        bestGoHeadingRadians = path.firstHeadingRadians
                    }
                }
            }
        }

        // Stop is a first-class True-Surfing option: evaluate it every tick (the
        // profile's stopPenalty keeps it from winning unless it is clearly safer).
        // A well-timed stop can dodge a wave at a GF the enemy can't reach because
        // we are not moving. mirage.stop=off disables it; mirage.stop=force always
        // stops (A/B).
        val stopMode = System.getProperty("mirage.stop")?.trim()?.lowercase()
        val canStop =
            when (stopMode) {
                "off", "false", "no" -> false
                "on", "true", "yes", "force" -> true
                else -> stopAllowed ?: true
            }
        if (canStop) {
            val stop = predictArrivalStop(incoming, self, now)
            var stopCost = waveDanger(incoming, stop.x, stop.y, profile.flattenerWeight)
            if (second != null) {
                val stop2 = predictArrivalStop(second, self, now)
                stopCost += profile.secondWaveDiscount * waveDanger(second, stop2.x, stop2.y, profile.flattenerWeight)
            }
            stopCost += profile.wallWeight * wallRisk(stop.x, stop.y, fieldWidth, fieldHeight)
            stopCost += profile.stopPenalty
            stopCost += Math.random() * profile.tieNoise
            if (stopMode == "force" || stopCost < bestCost) {
                motion.driveAlongRadians(self.headingRadians, STOP_SPEED)
                return
            }
        }

        lastDir = bestDir
        if (bestGoHeadingRadians.isNaN()) {
            motion.orbitRadians(incoming.sourceX, incoming.sourceY, bestDir, bestTargetRange, maxSpeed = bestSpeed)
        } else {
            motion.driveAlongRadians(bestGoHeadingRadians, maxSpeed = bestSpeed)
        }
    }

    fun adoptEnemy(name: String) {
        danger = DangerModel.forEnemy(name)
        flattener = VisitFlattener.forEnemy(name)
    }

    fun bakeDanger(features: WaveFeatures): DoubleArray = danger.bake(features)

    /** Bake the empirical danger and blend in the simulated-targeting prior. The
     *  prior gives wave-1 coverage the empirical model lacks (it has no data then);
     *  as real visits accumulate the empirical bins dominate. mirage.simweight scales
     *  the prior (0 disables; default a moderate value). */
    fun bakeDangerWithPrior(
        features: WaveFeatures,
        sourceX: Double,
        sourceY: Double,
        usX: Double,
        usY: Double,
        usHeading: Double,
        usVelocity: Double,
        directAngle: Double,
        orbitDirection: Int,
        maxEscapeRadians: Double,
        bulletSpeed: Double,
        fieldWidth: Double,
        fieldHeight: Double,
        priorWeight: Double,
    ): DoubleArray {
        val empirical = danger.bake(features)
        val w = System.getProperty("mirage.simweight")?.toDoubleOrNull() ?: priorWeight
        if (w <= 0.0) return empirical
        val prior =
            SimulatedTargeting.dangerGf(
                sourceX,
                sourceY,
                usX,
                usY,
                usHeading,
                usVelocity,
                directAngle,
                orbitDirection,
                maxEscapeRadians,
                bulletSpeed,
                fieldWidth,
                fieldHeight,
            )
        val out = DoubleArray(empirical.size)
        var eSum = 0.0
        for (v in empirical) eSum += v
        // Empirical shares sum to ~1+; treat as weight proportional to data.
        val eWeight = eSum / (1.0 + eSum) * (1.0 + w)
        val pWeight = w
        for (i in empirical.indices) out[i] = eWeight * empirical[i] + pWeight * prior[i]
        return out
    }

    fun learnVisit(
        wave: EnemyWave,
        px: Double,
        py: Double,
    ) {
        if (wave.coveredLowGf.isNaN()) wave.cover(px, py)
        danger.registerVisit(wave.features, wave.coveredLowGf, wave.coveredHighGf, visitWeight())
        flattener.register(wave.coveredLowGf, wave.coveredHighGf)
    }

    fun learnHit(
        wave: EnemyWave,
        px: Double,
        py: Double,
    ) {
        danger.registerHit(wave.features, wave.guessFactor(px, py), hitWeight())
    }

    /**
     * Danger a wave poses along a predicted [path]: the worst-case GF danger over
     * the ticks the wave front crosses our hull. A strong gun fires at the GF we
     * occupy as the wave reaches us, which can be any tick during the crossing —
     * not just the final one — so we take the max over the sampled crossing points
     * rather than the endpoint alone. (A weighted-sum variant was tried and was
     * slightly noisier; max is more conservative and survives a hair better.)
     */
    private fun pathDanger(
        wave: EnemyWave,
        path: Path,
        flattenerWeight: Double,
        dangerMode: DangerMode,
    ): Double {
        val fw = flattenWeight(flattenerWeight)
        if (path.count == 0) return waveDanger(wave, path.x, path.y, fw)
        // mirage.danger: max (worst crossing tick), weighted (sum, arrival-weighted),
        // or arrival (endpoint only). Default max — most conservative.
        when (dangerModeOverride() ?: dangerMode) {
            DangerMode.ARRIVAL -> return waveDanger(wave, path.x, path.y, fw)
            DangerMode.WEIGHTED -> {
                val n = path.count
                var sum = 0.0
                var total = 0.0
                for (i in 0 until n) {
                    val w = 1.0 + i.toDouble() / n.toDouble()
                    sum += w * waveDanger(wave, path.xs[i], path.ys[i], fw)
                    total += w
                }
                return sum / total
            }
            DangerMode.MAX -> Unit
        }
        var worst = 0.0
        var i = 0
        while (i < path.count) {
            val d = waveDanger(wave, path.xs[i], path.ys[i], fw)
            if (d > worst) worst = d
            i++
        }
        return worst
    }

    private fun waveDanger(
        wave: EnemyWave,
        px: Double,
        py: Double,
        flattenerWeight: Double,
    ): Double {
        val gf = wave.guessFactor(px, py)
        val half = wave.hullHalfGf(hypot(px - wave.sourceX, py - wave.sourceY))
        val low = gf - half
        val high = gf + half
        val waveDanger = wave.dangerWindow(low, high)
        if (flattenerWeight == 0.0) return waveDanger
        return waveDanger + flattenerWeight * flattener.danger(low, high)
    }

    private fun wallRisk(
        x: Double,
        y: Double,
        fieldWidth: Double,
        fieldHeight: Double,
    ): Double = nearWall(x) + nearWall(fieldWidth - x) + nearWall(y) + nearWall(fieldHeight - y)

    private fun nearWall(distanceToWall: Double): Double = (1.0 - distanceToWall / WALL_RANGE).coerceAtLeast(0.0)

    private fun rangeRisk(
        x: Double,
        y: Double,
        sourceX: Double,
        sourceY: Double,
        targetRange: Double,
    ): Double = kotlin.math.abs(hypot(x - sourceX, y - sourceY) - targetRange) / targetRange.coerceAtLeast(1.0)

    private fun dangerStructure(wave: EnemyWave): Double {
        val bins = wave.dangerBins
        var sum = 0.0
        var peak = 0.0
        var valley = Double.MAX_VALUE
        var i = 0
        while (i < bins.size) {
            val value = bins[i]
            sum += value
            if (value > peak) peak = value
            if (value < valley) valley = value
            i++
        }
        val mean = sum / bins.size.toDouble()
        if (mean <= 0.0) return 0.0
        return (peak - valley) / mean
    }

    private fun collectValleyGuessFactors(wave: EnemyWave): Int {
        java.util.Arrays.fill(valleyCosts, Double.MAX_VALUE)
        val bins = wave.dangerBins
        val mid = bins.size / 2
        var i = 1
        while (i < bins.size - 1) {
            val gf = (i - mid).toDouble() / mid.toDouble()
            if (kotlin.math.abs(gf) <= MAX_SEARCH_GF) {
                val cost = bins[i - 1] + bins[i] + bins[i + 1]
                insertValley(gf, cost)
            }
            i++
        }
        var count = 0
        while (count < valleyCosts.size && valleyCosts[count] < Double.MAX_VALUE) count++
        return count
    }

    private fun insertValley(
        guessFactor: Double,
        cost: Double,
    ) {
        var slot = 0
        while (slot < valleyCosts.size && cost >= valleyCosts[slot]) slot++
        if (slot >= valleyCosts.size) return
        var i = valleyCosts.size - 1
        while (i > slot) {
            valleyCosts[i] = valleyCosts[i - 1]
            valleyGfs[i] = valleyGfs[i - 1]
            i--
        }
        valleyCosts[slot] = cost
        valleyGfs[slot] = guessFactor
    }

    private fun bearingForGuessFactor(
        wave: EnemyWave,
        guessFactor: Double,
    ): Double {
        val escape = if (guessFactor >= 0.0) wave.maxEscapePositive else wave.maxEscapeNegative
        return Angles.normalizeAbsolute(wave.directAngleRadians + guessFactor * escape * wave.orbitDirection)
    }

    private fun Int.ifZero(fallback: Int): Int = if (this == 0) fallback else this

    /** Inertia bonus for keeping the current orbit direction. Default is the
     *  profile's; mirage.inertia (A/B) overrides it to probe whether a stickier
     *  direction reduces the stop-and-go moments a strong gun picks off. */
    private fun inertiaBonus(): Double = System.getProperty("mirage.inertia")?.toDoubleOrNull() ?: 0.004

    /** Flattener weight. Default is the profile's; mirage.flatten (A/B) overrides
     *  it to probe whether a heavier flattener breaks a learning gun's lock. */
    private fun flattenWeight(profileWeight: Double): Double = System.getProperty("mirage.flatten")?.toDoubleOrNull() ?: profileWeight

    private fun dangerModeOverride(): DangerMode? =
        when (System.getProperty("mirage.danger")?.trim()?.lowercase()) {
            "arrival" -> DangerMode.ARRIVAL
            "weighted" -> DangerMode.WEIGHTED
            "max" -> DangerMode.MAX
            else -> null
        }

    private fun visitWeight(): Double = System.getProperty("mirage.visitweight")?.toDoubleOrNull() ?: VISIT_WEIGHT

    private fun hitWeight(): Double = System.getProperty("mirage.hitweight")?.toDoubleOrNull() ?: HIT_WEIGHT

    /**
     * Predicted orbit path: the positions we pass each tick while [wave]'s front
     * crosses our hull. Records the points sampled while the wave front is within
     * one hull-half of us (the window where a bullet could connect), so
     * [pathDanger] can take the worst GF over that whole crossing. [x]/[y] hold
     * the terminal position for the wall-risk term.
     */
    private class Path(
        capacity: Int,
    ) {
        val xs = DoubleArray(capacity)
        val ys = DoubleArray(capacity)
        var count = 0
        var x = 0.0
        var y = 0.0
        var firstHeadingRadians = Double.NaN
        var firstOrbitDir = 0

        fun reset() {
            count = 0
            firstHeadingRadians = Double.NaN
            firstOrbitDir = 0
        }

        fun add(
            px: Double,
            py: Double,
        ) {
            xs[count] = px
            ys[count] = py
            count++
        }
    }

    /** Roll our orbit ([dir]) forward, wall-smoothed, until [wave] has fully
     *  passed us. Records the hull-crossing points for multi-point danger. */
    private fun predictPath(
        wave: EnemyWave,
        from: Kinematics.Pose,
        dir: Int,
        now: Long,
        fieldWidth: Double,
        fieldHeight: Double,
        targetRange: Double,
        maxSpeed: Double = Kinematics.MAX_VELOCITY,
        path: Path,
    ): Path {
        var x = from.x
        var y = from.y
        var heading = from.headingRadians
        var velocity = from.velocity
        var ticks = 0
        val sourceX = wave.sourceX
        val sourceY = wave.sourceY
        path.reset()
        while (ticks < MAX_TICKS) {
            val centerToUs = Angles.absoluteBearing(sourceX, sourceY, x, y)
            val range = hypot(x - sourceX, y - sourceY)
            val tilted = centerToUs + dir * (Angles.HALF_PI + Distancing.tilt(range, targetRange))
            val desired = WallSmoothing.smoothedHeadingRadians(x, y, tilted, dir > 0, fieldWidth, fieldHeight)
            var angle = Angles.normalizeRelative(desired - heading)
            var driveSign = 1
            if (kotlin.math.abs(angle) > Angles.HALF_PI) {
                angle = Angles.normalizeRelative(angle + Angles.PI)
                driveSign = -1
            }
            val maxTurn = Kinematics.maxTurnRateRadians(velocity)
            heading = Angles.normalizeAbsolute(heading + angle.coerceIn(-maxTurn, maxTurn))
            velocity = Kinematics.nextVelocity(velocity, driveSign, maxSpeed)
            val h = heading
            x += kotlin.math.sin(h) * velocity
            y += kotlin.math.cos(h) * velocity
            ticks++
            // The wave front reaches us when its radius equals our distance from
            // the source. A bullet can connect with our hull across a ±HALF_BOT
            // band around that moment, so record our position each tick the front
            // sits in that band (the crossing window), then stop once it is past.
            val reach = wave.velocity * (now + ticks - wave.fireTime)
            val dist = kotlin.math.sqrt((x - sourceX) * (x - sourceX) + (y - sourceY) * (y - sourceY))
            val gap = reach - dist
            if (gap >= -Kinematics.HALF_BOT) {
                path.add(x, y)
            }
            if (gap >= Kinematics.HALF_BOT) break
        }
        path.x = x
        path.y = y
        return path
    }

    private fun predictGotoPath(
        wave: EnemyWave,
        from: Kinematics.Pose,
        targetX: Double,
        targetY: Double,
        now: Long,
        targetRange: Double,
        path: Path,
    ): Path {
        var x = from.x
        var y = from.y
        var heading = from.headingRadians
        var velocity = from.velocity
        var ticks = 0
        val sourceX = wave.sourceX
        val sourceY = wave.sourceY
        path.reset()
        while (ticks < MAX_TICKS) {
            var desired = Angles.absoluteBearing(x, y, targetX, targetY)
            if (hypot(targetX - x, targetY - y) < MIN_TARGET_DISTANCE) {
                val centerToUs = Angles.absoluteBearing(sourceX, sourceY, x, y)
                val range = hypot(x - sourceX, y - sourceY)
                val dir = if (path.firstOrbitDir == 0) lastDir else path.firstOrbitDir
                desired = centerToUs + dir * (Angles.HALF_PI + Distancing.tilt(range, targetRange))
            }
            if (ticks == 0) {
                path.firstHeadingRadians = desired
                val centerToUs = Angles.absoluteBearing(sourceX, sourceY, x, y)
                val lateral = kotlin.math.sin(desired - centerToUs)
                if (kotlin.math.abs(lateral) > 1e-6) path.firstOrbitDir = if (lateral >= 0.0) 1 else -1
            }
            var angle = Angles.normalizeRelative(desired - heading)
            var driveSign = 1
            if (kotlin.math.abs(angle) > Angles.HALF_PI) {
                angle = Angles.normalizeRelative(angle + Angles.PI)
                driveSign = -1
            }
            val maxTurn = Kinematics.maxTurnRateRadians(velocity)
            heading = Angles.normalizeAbsolute(heading + angle.coerceIn(-maxTurn, maxTurn))
            velocity = Kinematics.nextVelocity(velocity, driveSign, Kinematics.MAX_VELOCITY)
            x += kotlin.math.sin(heading) * velocity
            y += kotlin.math.cos(heading) * velocity
            ticks++
            val reach = wave.velocity * (now + ticks - wave.fireTime)
            val dist = kotlin.math.sqrt((x - sourceX) * (x - sourceX) + (y - sourceY) * (y - sourceY))
            val gap = reach - dist
            if (gap >= -Kinematics.HALF_BOT) {
                path.add(x, y)
            }
            if (gap >= Kinematics.HALF_BOT) break
        }
        path.x = x
        path.y = y
        return path
    }

    private fun predictArrivalStop(
        wave: EnemyWave,
        from: Kinematics.Pose,
        now: Long,
    ): Kinematics.Pose {
        var x = from.x
        var y = from.y
        val heading = from.headingRadians
        var velocity = from.velocity
        var ticks = 0
        val sourceX = wave.sourceX
        val sourceY = wave.sourceY
        while (ticks < MAX_TICKS) {
            velocity = brakeVelocity(velocity)
            x += kotlin.math.sin(heading) * velocity
            y += kotlin.math.cos(heading) * velocity
            ticks++
            val reach = wave.velocity * (now + ticks - wave.fireTime)
            if (reach * reach >= (x - sourceX) * (x - sourceX) + (y - sourceY) * (y - sourceY)) break
        }
        return Kinematics.Pose(x, y, heading, velocity)
    }

    private fun brakeVelocity(velocity: Double): Double =
        when {
            velocity > 0.0 -> maxOf(0.0, velocity - Kinematics.DECELERATION)
            velocity < 0.0 -> minOf(0.0, velocity + Kinematics.DECELERATION)
            else -> 0.0
        }

    private companion object {
        const val VISIT_WEIGHT = 0.5
        const val HIT_WEIGHT = 60.0
        const val MAX_TICKS = 100
        const val STOP_SPEED = 0.0
        const val WALL_RANGE = 120.0
        const val SEARCH_MARGIN = Kinematics.HALF_BOT + 28.0
        const val MIN_SEARCH_RADIUS = 180.0
        const val MAX_SEARCH_RADIUS = 680.0
        const val MIN_TARGET_DISTANCE = 42.0
        const val SEARCH_RANGE_WEIGHT = 0.025
        const val MIN_SEARCH_STRUCTURE = 1.25
        const val MAX_SEARCH_GF = 0.82
        const val MAX_SEARCH_GFS = 5
        val DIRECTIONS = intArrayOf(1, -1)
        val BASE_RANGE_OFFSETS = doubleArrayOf(0.0)
        val FULL_SPEEDS = doubleArrayOf(Kinematics.MAX_VELOCITY)
        val MULTI_SPEEDS = doubleArrayOf(Kinematics.MAX_VELOCITY, 5.0, 2.0)
        val SEARCH_ORBIT_RANGE_OFFSETS = doubleArrayOf(-80.0, 0.0, 80.0)
        val SEARCH_RANGE_OFFSETS = doubleArrayOf(-90.0, 0.0, 90.0)
    }
}

private class VisitFlattener {
    private val visits = GuessFactorDanger()

    fun register(
        lowGf: Double,
        highGf: Double,
    ) {
        visits.fade(RETAIN)
        visits.registerInterval(lowGf, highGf, 1.0)
    }

    fun danger(
        lowGf: Double,
        highGf: Double,
    ): Double = visits.windowShare(lowGf, highGf)

    companion object {
        const val RETAIN = 0.985
        private val perEnemy = HashMap<String, VisitFlattener>()

        fun forEnemy(name: String): VisitFlattener = perEnemy.getOrPut(name) { VisitFlattener() }
    }
}
