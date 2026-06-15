package zen.fencer

import robocode.AdvancedRobot
import robocode.Rules
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.hypot

/**
 * Fencer gun. Per scan:
 *
 * 1. **Select:** [VirtualGuns] picks the model that has landed the most so
 *    far (circular default).
 * 2. **Firepower:** expected-value choice over candidate powers.
 * 3. **ShotContext:** bundle the fire-time situation the models share.
 * 4. **Candidates:** Head-On, Linear, Circular (leads solved iteratively
 *    over [MotionModel]), and three statistical [GuessFactorGun]s — an all-time
 *    and a rolling gun over distance × lateral speed, plus a rolling gun over the
 *    complementary lateral speed × acceleration axis.
 * 5. **Turret + fire gate:** aim, then fire only when cool, aligned
 *    within a distance-aware tolerance, the fix is fresh, and the shot affords.
 */
class Gun(
    private val bot: AdvancedRobot,
) {
    private val vguns = VirtualGuns()
    private val gfGun = GuessFactorGun()
    private val gfRollingGun = GuessFactorGun(retain = ROLLING_RETAIN)

    /** A second rolling GF gun over a **complementary feature axis** (lateral
     * speed × acceleration, not distance × lateral speed): it catches surfers
     * whose guess-factor bias correlates with accel/brake/reverse rather than
     * range, the view the other two guns are blind to. */
    private val gfAccelGun = GuessFactorGun(retain = ROLLING_RETAIN)

    /** All-time gun over distance, lateral speed, and forward wall room. */
    private val gfWallGun = GuessFactorGun(segments = 27)
    private val turret = Turret(bot)

    /** Returns the real bullet fired this scan (for bullet-shadow casting), or
     * null when the gate held fire. */
    fun fireControl(
        tracker: EnemyTracker,
        fieldWidth: Double,
        fieldHeight: Double,
    ): robocode.Bullet? {
        val enemy = tracker.enemy ?: return null

        // Resolve past shots: score the virtual guns, let the GF guns learn.
        vguns.update(bot.time, enemy.x, enemy.y)
        gfGun.update(bot.time, enemy.x, enemy.y)
        gfRollingGun.update(bot.time, enemy.x, enemy.y)
        gfAccelGun.update(bot.time, enemy.x, enemy.y)
        gfWallGun.update(bot.time, enemy.x, enemy.y)

        val aim = vguns.best()
        val power = choosePower(bot.energy, enemy.energy, vguns.hitRate(aim), tracker.distance)
        val ctx = context(tracker, enemy, power)

        // Three feature views: distance × lateral speed (the two range-based GF
        // guns), lateral speed × acceleration (the complementary gun), and
        // distance × lateral speed × wall room (the wall-aware gun).
        val distLatSeg = distLatSegment(ctx.distance, abs(ctx.lateralSpeed))
        val accelSeg = velAccelSegment(abs(ctx.lateralSpeed), ctx.accel)
        val wallSeg = distLatSeg * 3 + wallSegment(tracker.forwardWallSpace, ctx.distance, ctx.bulletSpeed)

        // Bot-width in guess-factor units: the angular half-width a bullet can
        // be off and still hit, so the GF guns aim at mass windows, not bins.
        val halfBotGf = Math.toDegrees(atan(HALF_BOT / ctx.distance)) / ctx.maxEscapeDeg

        // Candidate angles (order matches VirtualGuns.Aim ordinals).
        val angles =
            doubleArrayOf(
                ctx.directAngleDeg, // HEAD_ON
                leadAim(ctx, MotionModel.Mode.LINEAR, fieldWidth, fieldHeight), // LINEAR
                leadAim(ctx, MotionModel.Mode.CIRCULAR, fieldWidth, fieldHeight), // CIRCULAR
                gfGun.aim(ctx.directAngleDeg, ctx.orbitSign, ctx.maxEscapeDeg, distLatSeg, halfBotGf), // GUESS_FACTOR
                gfRollingGun.aim(ctx.directAngleDeg, ctx.orbitSign, ctx.maxEscapeDeg, distLatSeg, halfBotGf), // GUESS_FACTOR_ROLLING
                gfAccelGun.aim(ctx.directAngleDeg, ctx.orbitSign, ctx.maxEscapeDeg, accelSeg, halfBotGf), // GUESS_FACTOR_ACCEL
                gfWallGun.aim(ctx.directAngleDeg, ctx.orbitSign, ctx.maxEscapeDeg, wallSeg, halfBotGf), // GUESS_FACTOR_WALL
            )

        // Point the gun, then use the remaining turn for the fire gate.
        val gunTurn = turret.aimAt(angles[aim.ordinal])

        val tolerance = Math.toDegrees(atan(HALF_BOT / ctx.distance))
        val aligned = abs(gunTurn) < tolerance
        val affordable = ctx.power <= bot.energy - ENERGY_RESERVE
        val fresh = !Uncertainty.tooStaleToFire(tracker.scanAge(bot.time))
        if (bot.gunHeat == 0.0 && aligned && affordable && fresh && ctx.power >= Rules.MIN_BULLET_POWER) {
            val bullet = turret.fire(ctx.power)
            if (bullet != null) {
                vguns.onFire(ctx.sourceX, ctx.sourceY, bot.time, ctx.bulletSpeed, angles)
                // Each GF gun learns from this shot in its own segment view.
                for ((gf, seg) in listOf(gfGun to distLatSeg, gfRollingGun to distLatSeg, gfAccelGun to accelSeg, gfWallGun to wallSeg)) {
                    gf.onFire(
                        ctx.sourceX,
                        ctx.sourceY,
                        bot.time,
                        ctx.bulletSpeed,
                        ctx.directAngleDeg,
                        ctx.orbitSign,
                        ctx.maxEscapeDeg,
                        seg,
                    )
                }
            }
            return bullet
        } else {
            // Tick wave: even without a real shot this scan, the enemy's eventual
            // position is honest training data against non-adaptive movers (they
            // can't see whether a bullet actually left). A fraction of a real
            // shot's weight, into the all-time gun only — the rolling anti-surfer
            // guns keep learning from real bullets alone, since surfers react to
            // those.
            for ((gf, seg) in listOf(gfGun to distLatSeg, gfWallGun to wallSeg)) {
                gf.onFire(
                    ctx.sourceX,
                    ctx.sourceY,
                    bot.time,
                    ctx.bulletSpeed,
                    ctx.directAngleDeg,
                    ctx.orbitSign,
                    ctx.maxEscapeDeg,
                    seg,
                    TICK_WAVE_WEIGHT,
                )
            }
        }
        return null
    }

    /** Persist the gun's learned stats (virtual-gun hits + all GF histograms). */
    fun snapshot(): DoubleArray =
        vguns.snapshot() + gfGun.snapshot() + gfRollingGun.snapshot() + gfAccelGun.snapshot() + gfWallGun.snapshot()

    fun restore(data: DoubleArray) {
        val v = vguns.snapshotSize()
        val g = gfGun.snapshotSize()
        val w = gfWallGun.snapshotSize()
        if (data.size != v + 3 * g + w) return
        vguns.restore(data.copyOfRange(0, v))
        gfGun.restore(data.copyOfRange(v, v + g))
        gfRollingGun.restore(data.copyOfRange(v + g, v + 2 * g))
        gfAccelGun.restore(data.copyOfRange(v + 2 * g, v + 3 * g))
        gfWallGun.restore(data.copyOfRange(v + 3 * g, v + 3 * g + w))
    }

    /** Bundle the current-tick shot situation. */
    private fun context(
        tracker: EnemyTracker,
        enemy: Snapshot,
        power: Double,
    ): ShotContext {
        val bulletSpeed = Rules.getBulletSpeed(power)
        return ShotContext(
            sourceX = bot.x,
            sourceY = bot.y,
            enemy = enemy,
            turnRateDeg = tracker.turnRateDegPerTick,
            distance = tracker.distance,
            directAngleDeg = Angles.absoluteBearing(bot.x, bot.y, enemy.x, enemy.y),
            lateralSpeed = tracker.lateralVelocity,
            accel = tracker.acceleration,
            orbitSign = if (tracker.lateralVelocity >= 0.0) 1 else -1,
            power = power,
            bulletSpeed = bulletSpeed,
            maxEscapeDeg = Math.toDegrees(asin(Kinematics.MAX_VELOCITY / bulletSpeed)),
        )
    }

    /** Flat segment index over distance × lateral speed (3×3) — the range-based
     * feature view shared by the two non-accel GF guns. */
    private fun distLatSegment(
        distance: Double,
        lateralSpeedAbs: Double,
    ): Int {
        val d = (distance / 200.0).toInt().coerceIn(0, 2)
        val l = (lateralSpeedAbs / Kinematics.MAX_VELOCITY * 3).toInt().coerceIn(0, 2)
        return d * 3 + l
    }

    /** Flat segment index over lateral speed × acceleration state (3×3): brake /
     * cruise / accelerate, the complementary axis. */
    private fun velAccelSegment(
        lateralSpeedAbs: Double,
        accel: Double,
    ): Int {
        val v = (lateralSpeedAbs / Kinematics.MAX_VELOCITY * 3).toInt().coerceIn(0, 2)
        val a =
            if (accel > ACCEL_EPS) {
                2
            } else if (accel < -ACCEL_EPS) {
                0
            } else {
                1
            }
        return v * 3 + a
    }

    /** Wall-room segment (0/1/2): ticks until the enemy's current travel
     * direction runs out of field, relative to the bullet's flight time —
     * below ~half the flight the wall truncates its escape, near/above it the
     * wall barely matters. */
    private fun wallSegment(
        forwardWallSpace: Double,
        distance: Double,
        bulletSpeed: Double,
    ): Int {
        val flightTicks = distance / bulletSpeed
        val wallTicks = forwardWallSpace / Kinematics.MAX_VELOCITY
        val ratio = wallTicks / flightTicks.coerceAtLeast(1.0)
        return when {
            ratio < 0.5 -> 0
            ratio < 1.0 -> 1
            else -> 2
        }
    }

    /** Iterative lead: project the enemy to the bullet's impact tick under [mode]. */
    private fun leadAim(
        ctx: ShotContext,
        mode: MotionModel.Mode,
        fieldWidth: Double,
        fieldHeight: Double,
    ): Double {
        val start = Kinematics.Pose(ctx.enemy.x, ctx.enemy.y, ctx.enemy.headingDeg, ctx.enemy.velocity)
        var ticks = 0
        var px = ctx.enemy.x
        var py = ctx.enemy.y
        for (i in 0 until MAX_ITERATIONS) {
            val flight = Math.round(hypot(px - ctx.sourceX, py - ctx.sourceY) / ctx.bulletSpeed).toInt()
            if (flight == ticks) break
            ticks = flight
            val predicted = MotionModel.predict(start, ctx.turnRateDeg, ticks, mode, fieldWidth, fieldHeight)
            px = predicted.x
            py = predicted.y
        }
        return Angles.absoluteBearing(ctx.sourceX, ctx.sourceY, px, py)
    }

    /** Expected-value firepower over candidate powers, capped by energy and overkill. */
    private fun choosePower(
        ourEnergy: Double,
        enemyEnergy: Double,
        baseHitRate: Double,
        distance: Double,
    ): Double {
        val refEscape = asin(Kinematics.MAX_VELOCITY / Rules.getBulletSpeed(REF_POWER))
        val distanceFactor = (atan(HALF_BOT / distance) / atan(HALF_BOT / REF_DISTANCE)).coerceIn(0.0, 2.0)
        var best = Rules.MIN_BULLET_POWER
        var bestValue = -Double.MAX_VALUE
        for (power in CANDIDATE_POWERS) {
            val escape = asin(Kinematics.MAX_VELOCITY / Rules.getBulletSpeed(power))
            val pHit = (baseHitRate * refEscape / escape * distanceFactor).coerceIn(0.0, 1.0)
            val value = (pHit * Rules.getBulletDamage(power) - (1.0 - pHit) * power) / (1.0 + power / 5.0)
            if (value > bestValue) {
                bestValue = value
                best = power
            }
        }
        best = minOf(best, ourEnergy / ENERGY_DIVISOR, ourEnergy - ENERGY_RESERVE)
        if (enemyEnergy in 0.0..MIN_KILL_ENERGY) {
            val kill = if (enemyEnergy <= 4.0) enemyEnergy / 4.0 else (enemyEnergy + 2.0) / 6.0
            best = minOf(best, kill)
        }
        return best.coerceIn(Rules.MIN_BULLET_POWER, Rules.MAX_BULLET_POWER)
    }

    private companion object {
        const val HALF_BOT = 18.0
        const val ENERGY_RESERVE = 0.1
        const val ENERGY_DIVISOR = 4.0
        const val MAX_ITERATIONS = 8
        const val REF_POWER = 2.0

        /** Reference engagement range the empirical hit rate is normalised to;
         * the distance factor is 1 here and tapers either side. */
        const val REF_DISTANCE = 500.0
        const val MIN_KILL_ENERGY = 16.0
        const val ROLLING_RETAIN = 0.9

        /** Weight of a per-scan virtual tick wave relative to a real shot's 1.0
         * in the all-time GF gun — tick waves multiply the data ~14× (the gun's
         * cooldown), so a fraction keeps real shots meaningful while the dense
         * stream does the early learning. */
        const val TICK_WAVE_WEIGHT = 0.5

        /** Speed change above this (px/tick²) counts as accelerating/braking. */
        const val ACCEL_EPS = 0.5
        val CANDIDATE_POWERS = doubleArrayOf(0.1, 0.5, 1.0, 1.5, 2.0, 3.0)
    }
}
