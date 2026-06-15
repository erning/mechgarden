package zen.ronin

import robocode.AdvancedRobot
import robocode.Rules
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Ronin's gun. Per scan:
 *
 * 1. **Select:** [VirtualGuns] picks the aim that has landed the most so far
 *    (circular default).
 * 2. **Firepower:** expected-value choice over candidate powers.
 * 3. Build the shared [ShotContext].
 * 4. **Candidates:** Head-On, Linear, Circular (iterative leads), and four
 *    statistical [GfGun]s — distance×lateral (all-time + rolling), lateral×
 *    acceleration (rolling), and distance×lateral×wall-room (all-time).
 * 5. **Turret + fire gate:** aim, then fire only when cool, aligned within a
 *    distance-aware tolerance, the fix is fresh, and the shot affords.
 */
class Gun(
    private val bot: AdvancedRobot,
) {
    private val vguns = VirtualGuns()
    private val gfGun = GfGun(9)
    private val gfRollGun = GfGun(9, retain = ROLLING_RETAIN)
    private val gfAccelGun = GfGun(9, retain = ROLLING_RETAIN)
    private val gfWallGun = GfGun(27)
    private var dcGun = DcGun()
    private var firePowerSelector = FirePowerSelector()
    private var shieldAimSelector = ShieldAimSelector()
    private val turret = Turret(bot)

    /** Minimum DC-gun firepower — adapted per-opponent by the main controller: a
     * charging opponent lowers this (more shadows / economy to survive), a kiting
     * opponent raises it (more damage). Duelists leave it at the base 1.2. */
    var dcPowerFloor = DC_POWER_FLOOR_BASE

    /** Smoothed enemy lateral speed — the surfer-detection signal. High (>5)
     * means the enemy orbits consistently → likely a surfer that reacts to real
     * bullets only → tick waves are misleading → reduce their weight. */
    private var smoothLat = 0.0

    /** Swap in [name]'s per-enemy dynamic-clustering gun (its observation set lives
     * in a static registry that survives the per-round rebuild). */
    fun adoptEnemy(name: String) {
        dcGun = DcGun.forEnemy(name)
        firePowerSelector = FirePowerSelector.forEnemy(name)
        firePowerSelector.beginRound()
        shieldAimSelector = ShieldAimSelector.forEnemy(name)
        shieldAimSelector.beginRound()
    }

    /** Returns the real bullet fired this scan (for callers that track it), or
     * null when the gate held fire. */
    fun fireControl(
        tracker: EnemyTracker,
        fieldWidth: Double,
        fieldHeight: Double,
    ): robocode.Bullet? {
        val enemy = tracker.enemy ?: return null

        vguns.update(bot.time, enemy.x, enemy.y)
        gfGun.update(bot.time, enemy.x, enemy.y)
        gfRollGun.update(bot.time, enemy.x, enemy.y)
        gfAccelGun.update(bot.time, enemy.x, enemy.y)
        gfWallGun.update(bot.time, enemy.x, enemy.y)
        dcGun.update(bot.time, enemy.x, enemy.y)

        val aim = if (dcGun.size() >= DC_PRIMARY_MIN) VirtualGuns.Aim.GF_DC else vguns.best()
        // Energy-tier firepower: when Ronin has a clear energy lead (survival
        // buffer), fire aggressively to close out faster. When even or behind,
        // stay at the economy floor (cheap bullets + shadows). This leverages
        // Ronin's survival advantage — it often has a lead but wastes it firing
        // weak bullets that can't finish.
        val evPower = choosePower(vguns.hitRate(aim), tracker.distance)
        val energyLead = bot.energy - enemy.energy
        val tieredFloor = if (energyLead > ENERGY_LEAD_THRESHOLD) AGGRESSIVE_FLOOR else dcPowerFloor
        val baseFloor = if (aim == VirtualGuns.Aim.GF_DC) tieredFloor else Rules.MIN_BULLET_POWER
        val powerProfile = firePowerSelector.selectProfile()
        val power = capPower(firePowerSelector.apply(powerProfile, evPower, baseFloor), bot.energy, enemy.energy)
        val ctx = context(tracker, enemy, power)

        val distLatSeg = distLatSegment(ctx.distance, abs(ctx.lateralSpeed))
        val accelSeg = velAccelSegment(abs(ctx.lateralSpeed), ctx.accel)
        val wallSeg = distLatSeg * 3 + wallSegment(tracker.forwardWallSpace, ctx.distance, ctx.bulletSpeed)
        val halfBotGf = Math.toDegrees(atan(Kinematics.HALF_BOT / ctx.distance)) / ctx.maxEscapeDeg

        // Adapt tick-wave weight: surfers (high smooth lateral) get reduced weight
        // (they only react to real bullets); non-adaptive movers keep full weight.
        smoothLat = smoothLat * 0.95 + abs(ctx.lateralSpeed) * 0.05
        val tickWeight = if (smoothLat > SURFER_LAT_GATE) REDUCED_TICK_WEIGHT else TICK_WAVE_WEIGHT
        val dcFeat =
            dcGun.features(
                ctx.distance,
                abs(ctx.lateralSpeed),
                abs(ctx.advancingSpeed),
                ctx.accel,
                (tracker.forwardWallSpace / Kinematics.MAX_VELOCITY) / (ctx.distance / ctx.bulletSpeed).coerceAtLeast(1.0),
                tracker.timeSinceDirectionChange,
            )

        val angles =
            doubleArrayOf(
                ctx.directAngleDeg, // HEAD_ON
                leadAim(ctx, false, fieldWidth, fieldHeight), // LINEAR
                leadAim(ctx, true, fieldWidth, fieldHeight), // CIRCULAR
                gfGun.aim(ctx.directAngleDeg, ctx.orbitSign, ctx.maxEscapeDeg, distLatSeg, halfBotGf), // GF_DISTLAT
                gfRollGun.aim(ctx.directAngleDeg, ctx.orbitSign, ctx.maxEscapeDeg, distLatSeg, halfBotGf), // GF_ROLL
                gfAccelGun.aim(ctx.directAngleDeg, ctx.orbitSign, ctx.maxEscapeDeg, accelSeg, halfBotGf), // GF_ACCEL
                gfWallGun.aim(ctx.directAngleDeg, ctx.orbitSign, ctx.maxEscapeDeg, wallSeg, halfBotGf), // GF_WALL
                dcGun.aim(dcFeat, ctx.directAngleDeg, ctx.orbitSign, ctx.maxEscapeDeg, halfBotGf), // GF_DC
            )

        val shieldAimProfile = shieldAimSelector.select(tracker.shieldLikely)
        val selectedAngle = applyShieldAimProfile(angles[aim.ordinal], shieldAimProfile, ctx.distance)

        val gunTurn = turret.aimAt(selectedAngle)
        val tolerance = Math.toDegrees(atan(Kinematics.HALF_BOT / ctx.distance))
        val aligned = abs(gunTurn) < tolerance
        val affordable = ctx.power <= bot.energy - ENERGY_RESERVE
        val fresh = tracker.scanAge(bot.time) <= FIRE_STALE_TICKS
        if (bot.gunHeat == 0.0 && aligned && affordable && fresh && ctx.power >= Rules.MIN_BULLET_POWER) {
            val bullet = turret.fire(ctx.power)
            if (bullet != null) {
                vguns.onFire(ctx.sourceX, ctx.sourceY, bot.time, ctx.bulletSpeed, angles)
                trainFiredGuns(ctx, distLatSeg, accelSeg, wallSeg)
                dcGun.onFire(
                    dcFeat,
                    ctx.sourceX,
                    ctx.sourceY,
                    bot.time,
                    ctx.bulletSpeed,
                    ctx.directAngleDeg,
                    ctx.orbitSign,
                    ctx.maxEscapeDeg,
                )
                firePowerSelector.onFire(powerProfile, bullet)
                shieldAimSelector.onFire(shieldAimProfile, bullet)
            }
            return bullet
        }
        trainTickWave(ctx, distLatSeg, wallSeg, tickWeight)
        return null
    }

    /** Learn a real shot into every statistical gun at its own segment. */
    private fun trainFiredGuns(
        ctx: ShotContext,
        distLatSeg: Int,
        accelSeg: Int,
        wallSeg: Int,
    ) {
        gfGun.onFire(ctx.sourceX, ctx.sourceY, bot.time, ctx.bulletSpeed, ctx.directAngleDeg, ctx.orbitSign, ctx.maxEscapeDeg, distLatSeg)
        gfRollGun.onFire(
            ctx.sourceX,
            ctx.sourceY,
            bot.time,
            ctx.bulletSpeed,
            ctx.directAngleDeg,
            ctx.orbitSign,
            ctx.maxEscapeDeg,
            distLatSeg,
        )
        gfAccelGun.onFire(
            ctx.sourceX,
            ctx.sourceY,
            bot.time,
            ctx.bulletSpeed,
            ctx.directAngleDeg,
            ctx.orbitSign,
            ctx.maxEscapeDeg,
            accelSeg,
        )
        gfWallGun.onFire(ctx.sourceX, ctx.sourceY, bot.time, ctx.bulletSpeed, ctx.directAngleDeg, ctx.orbitSign, ctx.maxEscapeDeg, wallSeg)
    }

    /** Tick wave: dense training data against non-adaptive movers (they can't see
     * whether a bullet left). A fraction of a real shot's weight, into the all-time
     * guns only — the rolling gun keeps learning from real bullets alone, since
     * surfers react to those. */
    private fun trainTickWave(
        ctx: ShotContext,
        distLatSeg: Int,
        wallSeg: Int,
        weight: Double,
    ) {
        gfGun.onFire(
            ctx.sourceX,
            ctx.sourceY,
            bot.time,
            ctx.bulletSpeed,
            ctx.directAngleDeg,
            ctx.orbitSign,
            ctx.maxEscapeDeg,
            distLatSeg,
            weight,
        )
        gfWallGun.onFire(
            ctx.sourceX,
            ctx.sourceY,
            bot.time,
            ctx.bulletSpeed,
            ctx.directAngleDeg,
            ctx.orbitSign,
            ctx.maxEscapeDeg,
            wallSeg,
            weight,
        )
    }

    fun recordBulletHit(bullet: robocode.Bullet) {
        firePowerSelector.recordHit(bullet)
        shieldAimSelector.recordHit(bullet)
    }

    fun recordBulletMiss(bullet: robocode.Bullet) {
        firePowerSelector.recordMiss(bullet)
        shieldAimSelector.recordMiss(bullet)
    }

    fun recordBulletHitBullet(bullet: robocode.Bullet) {
        firePowerSelector.recordHitBullet(bullet)
        shieldAimSelector.recordHitBullet(bullet)
    }

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
            advancingSpeed = tracker.advancingVelocity,
            accel = tracker.acceleration,
            orbitSign = if (tracker.lateralVelocity >= 0.0) 1 else -1,
            power = power,
            bulletSpeed = bulletSpeed,
            maxEscapeDeg = Math.toDegrees(asin(Kinematics.MAX_VELOCITY / bulletSpeed)),
        )
    }

    private fun distLatSegment(
        distance: Double,
        lateralSpeedAbs: Double,
    ): Int = Segments.dist3(distance) * 3 + Segments.lat3(lateralSpeedAbs)

    private fun velAccelSegment(
        lateralSpeedAbs: Double,
        accel: Double,
    ): Int = Segments.lat3(lateralSpeedAbs) * 3 + Segments.accel3(Segments.accelSign(accel))

    private fun wallSegment(
        forwardWallSpace: Double,
        distance: Double,
        bulletSpeed: Double,
    ): Int {
        val flightTicks = distance / bulletSpeed
        val wallTicks = forwardWallSpace / Kinematics.MAX_VELOCITY
        return Segments.wall3(wallTicks / flightTicks.coerceAtLeast(1.0))
    }

    /** Iterative lead: project the enemy to the bullet's impact tick. [circular]
     * keeps its observed turn rate; otherwise linear (straight line). */
    private fun leadAim(
        ctx: ShotContext,
        circular: Boolean,
        fieldWidth: Double,
        fieldHeight: Double,
    ): Double {
        val omega = if (circular) ctx.turnRateDeg else 0.0
        var ticks = 0
        var px = ctx.enemy.x
        var py = ctx.enemy.y
        for (i in 0 until MAX_ITERATIONS) {
            val flight = Math.round(hypot(px - ctx.sourceX, py - ctx.sourceY) / ctx.bulletSpeed).toInt()
            if (flight == ticks) break
            ticks = flight
            var heading = ctx.enemy.headingDeg
            var x = ctx.enemy.x
            var y = ctx.enemy.y
            repeat(ticks) {
                heading = Angles.normalizeAbsolute(heading + omega)
                val rad = Math.toRadians(heading)
                x = (x + sin(rad) * ctx.enemy.velocity).coerceIn(Kinematics.HALF_BOT, fieldWidth - Kinematics.HALF_BOT)
                y = (y + Math.cos(rad) * ctx.enemy.velocity).coerceIn(Kinematics.HALF_BOT, fieldHeight - Kinematics.HALF_BOT)
            }
            px = x
            py = y
        }
        return Angles.absoluteBearing(ctx.sourceX, ctx.sourceY, px, py)
    }

    /** Expected-value firepower over candidate powers, capped by energy and overkill. */
    private fun choosePower(
        baseHitRate: Double,
        distance: Double,
    ): Double {
        val refEscape = asin(Kinematics.MAX_VELOCITY / Rules.getBulletSpeed(REF_POWER))
        val distanceFactor = (atan(Kinematics.HALF_BOT / distance) / atan(Kinematics.HALF_BOT / REF_DISTANCE)).coerceIn(0.0, 2.0)
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
        return best
    }

    /** Apply the energy-budget and overkill caps (shared by the EV choice and any
     * fixed-power path) and clamp into the legal range. */
    private fun capPower(
        base: Double,
        ourEnergy: Double,
        enemyEnergy: Double,
    ): Double {
        var p = minOf(base, ourEnergy / ENERGY_DIVISOR, ourEnergy - ENERGY_RESERVE)
        if (enemyEnergy in 0.0..MIN_KILL_ENERGY) {
            val kill = if (enemyEnergy <= 4.0) enemyEnergy / 4.0 else (enemyEnergy + 2.0) / 6.0
            p = minOf(p, kill)
        }
        return p.coerceIn(Rules.MIN_BULLET_POWER, Rules.MAX_BULLET_POWER)
    }

    private fun applyShieldAimProfile(
        baseAngleDeg: Double,
        profile: ShieldAimSelector.Profile,
        distance: Double,
    ): Double {
        if (profile == ShieldAimSelector.Profile.CENTER) return baseAngleDeg
        val offsetDeg = Math.toDegrees(atan(SHIELD_EDGE_OFFSET / distance.coerceAtLeast(Kinematics.HALF_BOT)))
        return Angles.normalizeAbsolute(baseAngleDeg + profile.edgeSign * offsetDeg)
    }

    private companion object {
        const val ENERGY_RESERVE = 0.1
        const val ENERGY_DIVISOR = 4.0
        const val MAX_ITERATIONS = 8
        const val REF_POWER = 2.0
        const val DC_PRIMARY_MIN = 45
        const val DC_POWER_FLOOR_BASE = 1.2
        const val ENERGY_LEAD_THRESHOLD = 20.0
        const val AGGRESSIVE_FLOOR = 2.0
        const val REF_DISTANCE = 500.0
        const val MIN_KILL_ENERGY = 16.0
        const val ROLLING_RETAIN = 0.9
        const val TICK_WAVE_WEIGHT = 0.5
        const val REDUCED_TICK_WEIGHT = 0.1
        const val SURFER_LAT_GATE = 5.0
        const val FIRE_STALE_TICKS = 6L
        const val SHIELD_EDGE_OFFSET = 12.0
        val CANDIDATE_POWERS = doubleArrayOf(0.1, 0.5, 1.0, 1.5, 2.0, 3.0)
    }
}

/** The fire-time shot situation shared by the aim models, segmentation, and
 * firepower. With fire-when-cool + per-tick re-aim, the current tick *is* the
 * fire-tick situation, so no explicit latency compensation is needed. */
private class ShotContext(
    val sourceX: Double,
    val sourceY: Double,
    val enemy: Snapshot,
    val turnRateDeg: Double,
    val distance: Double,
    val directAngleDeg: Double,
    val lateralSpeed: Double,
    val advancingSpeed: Double,
    val accel: Double,
    val orbitSign: Int,
    val power: Double,
    val bulletSpeed: Double,
    val maxEscapeDeg: Double,
)
