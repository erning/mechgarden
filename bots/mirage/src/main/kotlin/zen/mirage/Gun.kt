package zen.mirage

import robocode.AdvancedRobot
import robocode.Rules
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Mirage's gun. Per scan:
 *
 * 1. Resolve past outgoing waves (virtual guns, GF guns, DC gun learn).
 * 2. **Select:** DC once it has enough observations, else the virtual-gun leader
 *    (circular default).
 * 3. **Firepower:** expected-value choice floored by the energy-tier economy gate.
 * 4. Build candidates: Head-On, Linear, Circular, four segmented GF guns, and DC.
 * 5. **Turret + fire gate:** aim, then fire only when cool, aligned, affordable,
 *    and fresh; otherwise emit cheap tick-wave training for the GF guns.
 *
 * All angles in radians.
 */
class Gun(
    private val bot: AdvancedRobot,
) {
    private var antiSurferEvidence = AntiSurferEvidence()
    private var vguns = VirtualGuns(antiSurferEvidence)
    private val gfGun = GfFireGun(9)
    private val gfRollGun = GfFireGun(9, retain = ROLLING_RETAIN)
    private val gfAccelGun = GfFireGun(9, retain = ROLLING_RETAIN)
    private val gfWallGun = GfFireGun(27)
    private var dcGun = DcGun()
    private var dcTheoryGun = DcGun()
    private var dcAsGun = DcGun()
    private var firePowerSelector = FirePowerSelector()
    private var shieldAimSelector = ShieldAimSelector()
    private var stopGoDetector = StopGoDetector()
    private var offenseStats = OffenseStats()
    private val turret = Turret(bot)

    /** Last aim used by [fireControl] (mirage.debug). */
    private var lastAim: VirtualGuns.Aim = VirtualGuns.Aim.CIRCULAR

    /** Last firepower profile selected (mirage.debug). */
    private var lastProfile: FirePowerSelector.Profile = FirePowerSelector.Profile.BALANCED
    private var lastTheoryMea = false
    private var antiSurferSelectedShots = 0
    private var defaultPowerProfile: FirePowerSelector.Profile = FirePowerSelector.Profile.BALANCED
    private var defaultPowerFloor: Double = DC_POWER_FLOOR_BASE
    private var adaptivePowerEnabled = false

    /** Smoothed enemy lateral speed — the surfer-detection signal. High (>5)
     *  means the enemy orbits consistently → likely a surfer that reacts to real
     *  bullets only → tick waves are misleading → reduce their weight. */
    private var smoothLat = 0.0

    /** Swap in [name]'s per-enemy DC gun (its observation set lives in a static
     *  registry that survives the per-round rebuild). */
    fun adoptEnemy(name: String) {
        antiSurferEvidence = AntiSurferEvidence.forEnemy(name)
        vguns = VirtualGuns(antiSurferEvidence)
        dcGun = DcGun.forEnemy(name)
        dcTheoryGun = DcGun.forEnemyTheory(name)
        dcAsGun = DcGun.forEnemyAs(name)
        if (System.getProperty("mirage.dcclear")?.trim()?.lowercase() != "off") {
            dcGun.beginRound()
            dcTheoryGun.beginRound()
            dcAsGun.beginRound()
        }
        firePowerSelector = FirePowerSelector.forEnemy(name)
        firePowerSelector.beginRound()
        shieldAimSelector = ShieldAimSelector.forEnemy(name)
        shieldAimSelector.beginRound()
        stopGoDetector = StopGoDetector.forEnemy(name)
        stopGoDetector.beginRound()
        offenseStats = OffenseStats.forEnemy(name)
    }

    fun setDefaultPowerProfile(profile: FirePowerSelector.Profile) {
        defaultPowerProfile = profile
    }

    fun setDefaultPowerFloor(power: Double) {
        defaultPowerFloor = power.coerceIn(Rules.MIN_BULLET_POWER, Rules.MAX_BULLET_POWER)
    }

    fun setAdaptivePower(enabled: Boolean) {
        adaptivePowerEnabled = enabled
    }

    /** Returns the real bullet fired this scan (for callers that track it), or
     *  null when the gate held fire. */
    fun fireControl(
        tracker: Tracker,
        frame: Tracker.Frame,
        fieldWidth: Double,
        fieldHeight: Double,
        holdFire: Boolean = false,
    ): robocode.Bullet? {
        val enemy = frame.enemy
        val derived = frame.derived

        vguns.update(bot.time, enemy.x, enemy.y)
        gfGun.update(bot.time, enemy.x, enemy.y)
        gfRollGun.update(bot.time, enemy.x, enemy.y)
        gfAccelGun.update(bot.time, enemy.x, enemy.y)
        gfWallGun.update(bot.time, enemy.x, enemy.y)
        dcGun.update(bot.time, enemy.x, enemy.y)
        dcTheoryGun.update(bot.time, enemy.x, enemy.y)
        dcAsGun.update(bot.time, enemy.x, enemy.y)
        stopGoDetector.observe(enemy.time, enemy.velocity)

        val lateralSpeed = derived?.lateralVelocity ?: 0.0
        val advancingSpeed = derived?.advancingVelocity ?: 0.0
        val accel = derived?.acceleration ?: 0.0
        val turnRate = derived?.turnRateRadians ?: 0.0
        val distance = enemy.distance
        val useTheoryMea = theoryMeaEnabled(distance)
        lastTheoryMea = useTheoryMea
        val activeDcGun = if (useTheoryMea) dcTheoryGun else dcGun
        val directAngleRadians = Angles.absoluteBearing(bot.x, bot.y, enemy.x, enemy.y)
        val orbitSign =
            if (System.getProperty("mirage.stickyorbit")?.trim()?.lowercase() == "off") {
                if (lateralSpeed >= 0.0) 1 else -1
            } else {
                OrbitDirection.sign(lateralSpeed, derived?.lateralDirection ?: 0)
            }

        // Aim selection. DC (KNN) is forced primary once it has learned enough — its
        // virtual-gun score under-reports the real hit rate (KNN peak-finding hits
        // sharper than the binary would-it-hit check), so trusting the virtual
        // scores and picking vguns.best() measurably regresses against
        // DC-vulnerable opponents. Below the threshold the virtual-gun scores pick
        // the best (circular default). The mirage.aim=best override (A/B tuning)
        // instead defers to the virtual scores, treating DC as just another
        // candidate — useful where a segmented GF gun genuinely out-predicts DC.
        val learnedAim =
            when (System.getProperty("mirage.aim") ?: "dc") {
                "best" -> vguns.best()
                else ->
                    when {
                        activeDcGun.size() < DC_PRIMARY_MIN -> vguns.best(includeAntiSurfer = false)
                        !useTheoryMea && asGunForced() && dcAsGun.size() >= DC_PRIMARY_MIN ->
                            VirtualGuns.Aim.GF_DC_AS
                        !useTheoryMea &&
                            asGunEnabled() &&
                            dcAsGun.size() >= DC_PRIMARY_MIN &&
                            antiSurferEvidence.select(
                                AS_MIN_VIRTUAL_ATTEMPTS,
                                AS_MAX_MAIN_HIT_RATE,
                                AS_MAX_MAIN_EXIT_HIT_RATE,
                                AS_MIN_HIT_RATE_LEAD,
                                AS_EXIT_HIT_RATE_LEAD,
                            ) ->
                            VirtualGuns.Aim.GF_DC_AS
                        else -> VirtualGuns.Aim.GF_DC
                    }
            }
        // Once the target has remained parked or only crept for several ticks,
        // its displacement during bullet flight is small enough that head-on is
        // the stable choice. A DC gun trained on earlier movement can otherwise
        // keep proposing alternating non-zero guess factors; the turret chases
        // those changing angles without entering the fire-alignment tolerance.
        // The sustained slow-speed gate avoids momentary direction changes by
        // normal movers while covering shield-style micro-movement.
        val aim =
            if (tracker.slowTicks >= SLOW_AIM_GATE) {
                VirtualGuns.Aim.HEAD_ON
            } else {
                learnedAim
            }
        lastAim = aim
        val evPower = choosePower(vguns.hitRate(aim), distance)
        val energyLead = bot.energy - enemy.energy
        val dcFloor = dcFloorOverride()
        val tieredFloor = if (energyLead > ENERGY_LEAD_THRESHOLD) maxOf(AGGRESSIVE_FLOOR, dcFloor) else dcFloor
        val baseFloor = if (aim == VirtualGuns.Aim.GF_DC || aim == VirtualGuns.Aim.GF_DC_AS) tieredFloor else Rules.MIN_BULLET_POWER
        val powerSelection = firePowerSelector.select(defaultPowerProfile, adaptivePowerEnabled)
        val powerProfile = powerSelection.profile
        lastProfile = powerProfile
        val power = capPower(firePowerSelector.apply(powerProfile, evPower, baseFloor), bot.energy, enemy.energy)

        val bulletSpeed = Rules.getBulletSpeed(power)
        // Gun uses precise MEA by default: it sharpens the GF scale where the
        // enemy can actually reach, letting the DC/KNN peak and the GF segmentations
        // resolve tighter against wall- or velocity-limited movers. The surfer keeps
        // theory MEA (its danger model was tuned under it and precise MEA made it
        // over-cautious); mirage.mea=theory reverts both to the textbook value.
        val theoryEscapeRadians = asin(Kinematics.MAX_VELOCITY / bulletSpeed)
        val preciseEscapeRadians =
            if (useTheoryMea) {
                Double.NaN
            } else {
                gunMea(bulletSpeed, enemy.x, enemy.y, enemy.headingRadians, enemy.velocity, distance)
            }
        val maxEscapeRadians = if (useTheoryMea) theoryEscapeRadians else preciseEscapeRadians
        val halfBotGf = atan(Kinematics.HALF_BOT / distance) / maxEscapeRadians

        val distLatSeg = Segments.dist3(distance) * 3 + Segments.lat3(abs(lateralSpeed))
        val accelSeg = Segments.lat3(abs(lateralSpeed)) * 3 + Segments.accel3(Segments.accelSign(accel))
        val wallSeg =
            distLatSeg * 3 +
                Segments.wall3((tracker.forwardWallSpace / Kinematics.MAX_VELOCITY) / (distance / bulletSpeed).coerceAtLeast(1.0))
        val dcFeat =
            activeDcGun.features(
                distance,
                abs(lateralSpeed),
                advancingSpeed,
                accel,
                (tracker.forwardWallSpace / Kinematics.MAX_VELOCITY) / (distance / bulletSpeed).coerceAtLeast(1.0),
                tracker.timeSinceDirectionChange(),
            )

        smoothLat = smoothLat * 0.95 + abs(lateralSpeed) * 0.05
        val tickWeight = if (smoothLat > SURFER_LAT_GATE) REDUCED_TICK_WEIGHT else TICK_WAVE_WEIGHT

        val ctx =
            ShotContext(
                sourceX = bot.x,
                sourceY = bot.y,
                enemy = enemy,
                turnRateRadians = turnRate,
                distance = distance,
                directAngleRadians = directAngleRadians,
                lateralSpeed = lateralSpeed,
                orbitSign = orbitSign,
                power = power,
                bulletSpeed = bulletSpeed,
                maxEscapeRadians = maxEscapeRadians,
                useTheoryMea = useTheoryMea,
            )

        val rawSelected = angleForAim(aim, ctx, dcFeat, distLatSeg, accelSeg, wallSeg, halfBotGf, fieldWidth, fieldHeight)

        // Anti-shield edge aim: when the enemy looks like a bullet-shielder
        // (stationary + recent low-power fire), nudge the aim off the center line
        // to one hull edge so our bullet's path misses their center-line intercept
        // bullet. The selector learns, per enemy, which edge pays off; CENTER when
        // not shielding (no-op against normal movers).
        val shieldAimProfile = shieldAimSelector.select(tracker.shieldLikely)
        val selectedAngle = applyShieldAimProfile(rawSelected, shieldAimProfile, ctx.distance)

        val gunTurn = turret.aimAtRadians(selectedAngle)
        val tolerance = atan(Kinematics.HALF_BOT / distance)
        val aligned = abs(gunTurn) < tolerance
        val affordable = ctx.power <= bot.energy - ENERGY_RESERVE
        val fresh = tracker.scanAge(bot.time) <= FIRE_STALE_TICKS
        if (!holdFire && bot.gunHeat == 0.0 && aligned && affordable && fresh && ctx.power >= Rules.MIN_BULLET_POWER) {
            val bullet = turret.fire(ctx.power)
            if (bullet != null) {
                val preciseTrainingEscapeRadians =
                    if (preciseEscapeRadians.isNaN()) {
                        gunMea(ctx.bulletSpeed, enemy.x, enemy.y, enemy.headingRadians, enemy.velocity, distance)
                    } else {
                        preciseEscapeRadians
                    }
                val virtualAngles =
                    anglesForVguns(aim, rawSelected, ctx, dcFeat, distLatSeg, accelSeg, wallSeg, halfBotGf, fieldWidth, fieldHeight)
                vguns.onFire(
                    ctx.sourceX,
                    ctx.sourceY,
                    bot.time,
                    ctx.bulletSpeed,
                    virtualAngles,
                    asEvidenceEligible =
                        !useTheoryMea &&
                            asGunEnabled() &&
                            dcGun.size() >= DC_PRIMARY_MIN &&
                            dcAsGun.size() >= DC_PRIMARY_MIN,
                )
                trainFiredGuns(ctx, distLatSeg, accelSeg, wallSeg)
                dcGun.onFire(
                    dcFeat,
                    ctx.sourceX,
                    ctx.sourceY,
                    bot.time,
                    ctx.bulletSpeed,
                    ctx.directAngleRadians,
                    ctx.orbitSign,
                    preciseTrainingEscapeRadians,
                )
                dcTheoryGun.onFire(
                    dcFeat,
                    ctx.sourceX,
                    ctx.sourceY,
                    bot.time,
                    ctx.bulletSpeed,
                    ctx.directAngleRadians,
                    ctx.orbitSign,
                    theoryEscapeRadians,
                )
                if (asGunEnabled()) {
                    dcAsGun.onFire(
                        dcFeat,
                        ctx.sourceX,
                        ctx.sourceY,
                        bot.time,
                        ctx.bulletSpeed,
                        ctx.directAngleRadians,
                        ctx.orbitSign,
                        preciseTrainingEscapeRadians,
                        bullet,
                    )
                }
                firePowerSelector.onFire(powerSelection, bullet)
                shieldAimSelector.onFire(shieldAimProfile, bullet)
                if (aim == VirtualGuns.Aim.GF_DC_AS) antiSurferSelectedShots++
                return bullet
            }
        }
        trainTickWave(ctx, distLatSeg, wallSeg, tickWeight)
        return null
    }

    /** Offset the aim angle toward one hull edge when an anti-shield profile is
     *  active. The offset is the angle subtended by [SHIELD_EDGE_OFFSET] px at the
     *  current distance, so the bullet strikes the robot's side, not its center. */
    private fun applyShieldAimProfile(
        baseAngleRadians: Double,
        profile: ShieldAimSelector.Profile,
        distance: Double,
    ): Double {
        if (profile == ShieldAimSelector.Profile.CENTER) return baseAngleRadians
        val offsetRadians = atan(SHIELD_EDGE_OFFSET / distance.coerceAtLeast(Kinematics.HALF_BOT))
        return Angles.normalizeAbsolute(baseAngleRadians + profile.edgeSign * offsetRadians)
    }

    /** DC gunfire power floor. The mirage.powerfloor override (A/B tuning) probes
     *  whether faster, lower-power bullets hit adaptive movers more often (smaller
     *  escape angle) — default keeps the tuned economy floor. */
    private fun dcFloorOverride(): Double =
        System.getProperty("mirage.powerfloor")?.toDoubleOrNull()?.coerceIn(Rules.MIN_BULLET_POWER, Rules.MAX_BULLET_POWER)
            ?: defaultPowerFloor

    /** True anti-surfer DC view: resolved firing waves add mass, while a real hit
     *  subtracts twice that mass because a learning surfer will avoid the GF it
     *  just learned was dangerous. `force` bypasses virtual-gun arbitration for
     *  an A/B probe; unset or `on` keeps the measured selector. */
    private fun asGunEnabled(): Boolean = System.getProperty("mirage.asgun")?.trim()?.lowercase() != "off"

    private fun asGunForced(): Boolean = System.getProperty("mirage.asgun")?.trim()?.lowercase() == "force"

    /** Stop-and-go movers produce a more stable DC scale under theory MEA than a
     *  fire-time precise MEA that changes with every braking phase. Explicit
     *  mirage.mea and mirage.stopgomea values remain available for A/B tests. */
    private fun theoryMeaEnabled(distance: Double): Boolean =
        StopGoMeaPolicy.useTheory(
            System.getProperty("mirage.stopgomea"),
            System.getProperty("mirage.mea"),
            stopGoDetector.likely,
            distance,
        )

    /** Precise MEA for a bullet *we* fire at the enemy: the largest bearing
     *  offset the enemy can reach from our gun before the bullet catches it.
     *  Clamped to [floor, theory]. Mirrors the surf-side computation so the gun
     *  and the surfer share one escape-angle definition. */
    private fun gunMea(
        bulletSpeed: Double,
        enemyX: Double,
        enemyY: Double,
        enemyHeadingRadians: Double,
        enemyVelocity: Double,
        distance: Double,
    ): Double {
        val theory = asin(Kinematics.MAX_VELOCITY / bulletSpeed)
        val target = Kinematics.Pose(enemyX, enemyY, enemyHeadingRadians, enemyVelocity)
        return PreciseMea
            .halfEscapeRadians(
                bot.x,
                bot.y,
                bot.time,
                bulletSpeed,
                target,
                bot.time,
                bot.battleFieldWidth,
                bot.battleFieldHeight,
            ).coerceIn(MIRAGE_MIN_ESCAPE_RADIANS, theory)
    }

    fun recordBulletHit(bullet: robocode.Bullet) {
        if (asGunEnabled()) dcAsGun.recordHit(bullet, bullet.x, bullet.y)
        offenseStats.recordHit()
        firePowerSelector.recordHit(bullet)
        shieldAimSelector.recordHit(bullet)
    }

    fun recordBulletMiss(bullet: robocode.Bullet) {
        offenseStats.recordNonHit()
        firePowerSelector.recordMiss(bullet)
        shieldAimSelector.recordMiss(bullet)
    }

    fun recordBulletHitBullet(bullet: robocode.Bullet) {
        offenseStats.recordNonHit()
        firePowerSelector.recordHitBullet(bullet)
        shieldAimSelector.recordHitBullet(bullet)
    }

    /** Compact per-round diagnostics (mirage.debug): active aim, DC observation
     *  count, DC prediction accuracy, firepower profile, and per-aim virtual-gun
     *  hit rates. */
    fun debugStats(): String {
        val rates = VirtualGuns.Aim.values().joinToString(",") { "${AIM_LABELS[it.ordinal]}=${"%.2f".format(vguns.hitRate(it))}" }
        val hitRate = offenseStats.hitRate()
        val hitRateText = if (hitRate.isNaN()) "n/a" else "%.3f".format(hitRate)
        val activeDcGun = if (lastTheoryMea) dcTheoryGun else dcGun
        return "aim=$lastAim mea=${if (lastTheoryMea) "theory" else "precise"} " +
            "dc=${activeDcGun.size()} dcAcc=${"%.2f".format(activeDcGun.activeProfileRate())} " +
            "as=${"%.2f".format(antiSurferEvidence.mainHitRate())}/${"%.2f".format(antiSurferEvidence.antiSurferHitRate())}" +
            "@${"%.1f".format(antiSurferEvidence.resolvedAttempts())}/${if (antiSurferEvidence.selected()) "on" else "off"}" +
            "/sel:$antiSurferSelectedShots " +
            "fp=$lastProfile ohr=$hitRateText@${offenseStats.resolvedShots()} [$rates] " +
            stopGoDetector.debugSummary()
    }

    private fun angleForAim(
        aim: VirtualGuns.Aim,
        ctx: ShotContext,
        dcFeat: DoubleArray,
        distLatSeg: Int,
        accelSeg: Int,
        wallSeg: Int,
        halfBotGf: Double,
        fieldWidth: Double,
        fieldHeight: Double,
    ): Double =
        when (aim) {
            VirtualGuns.Aim.HEAD_ON -> ctx.directAngleRadians
            VirtualGuns.Aim.LINEAR -> leadAim(ctx, circular = false, fieldWidth, fieldHeight)
            VirtualGuns.Aim.CIRCULAR -> leadAim(ctx, circular = true, fieldWidth, fieldHeight)
            VirtualGuns.Aim.GF_DISTLAT ->
                gfGun.aimRadians(
                    ctx.directAngleRadians,
                    ctx.orbitSign,
                    ctx.maxEscapeRadians,
                    distLatSeg,
                    halfBotGf,
                )
            VirtualGuns.Aim.GF_ROLL ->
                gfRollGun.aimRadians(
                    ctx.directAngleRadians,
                    ctx.orbitSign,
                    ctx.maxEscapeRadians,
                    distLatSeg,
                    halfBotGf,
                )
            VirtualGuns.Aim.GF_ACCEL ->
                gfAccelGun.aimRadians(
                    ctx.directAngleRadians,
                    ctx.orbitSign,
                    ctx.maxEscapeRadians,
                    accelSeg,
                    halfBotGf,
                )
            VirtualGuns.Aim.GF_WALL -> gfWallGun.aimRadians(ctx.directAngleRadians, ctx.orbitSign, ctx.maxEscapeRadians, wallSeg, halfBotGf)
            VirtualGuns.Aim.GF_DC ->
                (if (ctx.useTheoryMea) dcTheoryGun else dcGun)
                    .aimRadians(dcFeat, ctx.directAngleRadians, ctx.orbitSign, ctx.maxEscapeRadians, halfBotGf)
            VirtualGuns.Aim.GF_DC_AS ->
                if (!ctx.useTheoryMea && asGunEnabled()) {
                    dcAsGun.aimRadians(dcFeat, ctx.directAngleRadians, ctx.orbitSign, ctx.maxEscapeRadians, halfBotGf)
                } else {
                    (if (ctx.useTheoryMea) dcTheoryGun else dcGun)
                        .aimRadians(dcFeat, ctx.directAngleRadians, ctx.orbitSign, ctx.maxEscapeRadians, halfBotGf)
                }
        }

    private fun anglesForVguns(
        aim: VirtualGuns.Aim,
        rawSelected: Double,
        ctx: ShotContext,
        dcFeat: DoubleArray,
        distLatSeg: Int,
        accelSeg: Int,
        wallSeg: Int,
        halfBotGf: Double,
        fieldWidth: Double,
        fieldHeight: Double,
    ): DoubleArray {
        val out = DoubleArray(AIM.size)
        for (a in AIM) {
            out[a.ordinal] =
                if (a == aim) {
                    rawSelected
                } else {
                    angleForAim(a, ctx, dcFeat, distLatSeg, accelSeg, wallSeg, halfBotGf, fieldWidth, fieldHeight)
                }
        }
        return out
    }

    private fun trainFiredGuns(
        ctx: ShotContext,
        distLatSeg: Int,
        accelSeg: Int,
        wallSeg: Int,
    ) {
        gfGun.onFire(
            ctx.sourceX,
            ctx.sourceY,
            bot.time,
            ctx.bulletSpeed,
            ctx.directAngleRadians,
            ctx.orbitSign,
            ctx.maxEscapeRadians,
            distLatSeg,
        )
        gfRollGun.onFire(
            ctx.sourceX,
            ctx.sourceY,
            bot.time,
            ctx.bulletSpeed,
            ctx.directAngleRadians,
            ctx.orbitSign,
            ctx.maxEscapeRadians,
            distLatSeg,
        )
        gfAccelGun.onFire(
            ctx.sourceX,
            ctx.sourceY,
            bot.time,
            ctx.bulletSpeed,
            ctx.directAngleRadians,
            ctx.orbitSign,
            ctx.maxEscapeRadians,
            accelSeg,
        )
        gfWallGun.onFire(
            ctx.sourceX,
            ctx.sourceY,
            bot.time,
            ctx.bulletSpeed,
            ctx.directAngleRadians,
            ctx.orbitSign,
            ctx.maxEscapeRadians,
            wallSeg,
        )
    }

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
            ctx.directAngleRadians,
            ctx.orbitSign,
            ctx.maxEscapeRadians,
            distLatSeg,
            weight,
        )
        gfWallGun.onFire(
            ctx.sourceX,
            ctx.sourceY,
            bot.time,
            ctx.bulletSpeed,
            ctx.directAngleRadians,
            ctx.orbitSign,
            ctx.maxEscapeRadians,
            wallSeg,
            weight,
        )
    }

    /** Iterative lead: project the enemy to the bullet's impact tick. [circular]
     *  keeps its observed turn rate; otherwise linear (straight line). */
    private fun leadAim(
        ctx: ShotContext,
        circular: Boolean,
        fieldWidth: Double,
        fieldHeight: Double,
    ): Double {
        val omega = if (circular) ctx.turnRateRadians else 0.0
        var ticks = 0
        var px = ctx.enemy.x
        var py = ctx.enemy.y
        for (i in 0 until MAX_ITERATIONS) {
            val flight = Math.round(hypot(px - ctx.sourceX, py - ctx.sourceY) / ctx.bulletSpeed).toInt()
            if (flight == ticks) break
            ticks = flight
            var heading = ctx.enemy.headingRadians
            var x = ctx.enemy.x
            var y = ctx.enemy.y
            repeat(ticks) {
                heading = Angles.normalizeAbsolute(heading + omega)
                x = (x + sin(heading) * ctx.enemy.velocity).coerceIn(Kinematics.HALF_BOT, fieldWidth - Kinematics.HALF_BOT)
                y = (y + Math.cos(heading) * ctx.enemy.velocity).coerceIn(Kinematics.HALF_BOT, fieldHeight - Kinematics.HALF_BOT)
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
        val distanceFactor = (atan(Kinematics.HALF_BOT / distance) / REF_HALF_ANGLE).coerceIn(0.0, 2.0)
        var best = Rules.MIN_BULLET_POWER
        var bestValue = -Double.MAX_VALUE
        for (power in CANDIDATE_POWERS) {
            val escape = asin(Kinematics.MAX_VELOCITY / Rules.getBulletSpeed(power))
            val pHit = (baseHitRate * REF_ESCAPE / escape * distanceFactor).coerceIn(0.0, 1.0)
            val value = (pHit * Rules.getBulletDamage(power) - (1.0 - pHit) * power) / (1.0 + power / 5.0)
            if (value > bestValue) {
                bestValue = value
                best = power
            }
        }
        return best
    }

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

    private class ShotContext(
        val sourceX: Double,
        val sourceY: Double,
        val enemy: EnemyState,
        val turnRateRadians: Double,
        val distance: Double,
        val directAngleRadians: Double,
        val lateralSpeed: Double,
        val orbitSign: Int,
        val power: Double,
        val bulletSpeed: Double,
        val maxEscapeRadians: Double,
        val useTheoryMea: Boolean,
    )

    private companion object {
        const val ENERGY_RESERVE = 0.1
        const val ENERGY_DIVISOR = 4.0
        const val MAX_ITERATIONS = 8
        const val DC_PRIMARY_MIN = 45
        const val AS_MIN_VIRTUAL_ATTEMPTS = 100.0
        const val AS_MAX_MAIN_HIT_RATE = 0.20
        const val AS_MAX_MAIN_EXIT_HIT_RATE = 0.22
        const val AS_MIN_HIT_RATE_LEAD = 0.03
        const val AS_EXIT_HIT_RATE_LEAD = 0.005

        const val DC_POWER_FLOOR_BASE = 1.2
        const val ENERGY_LEAD_THRESHOLD = 20.0
        const val AGGRESSIVE_FLOOR = 2.0
        const val MIN_KILL_ENERGY = 16.0
        const val ROLLING_RETAIN = 0.9
        const val TICK_WAVE_WEIGHT = 0.5
        const val REDUCED_TICK_WEIGHT = 0.1
        const val SURFER_LAT_GATE = 5.0
        const val FIRE_STALE_TICKS = 6L
        const val SHIELD_EDGE_OFFSET = 12.0
        const val SLOW_AIM_GATE = 6L

        /** Precise-MEA floor shared with the surf side (Mirage.MIN_ESCAPE_RADIANS).
         *  Kept here too so the gun is self-contained if the floor is tuned. */
        const val MIRAGE_MIN_ESCAPE_RADIANS = 0.18
        val CANDIDATE_POWERS = doubleArrayOf(0.1, 0.5, 1.0, 1.5, 2.0, 3.0)

        private const val REF_POWER = 2.0
        private const val REF_DISTANCE = 500.0
        private val REF_ESCAPE = asin(Kinematics.MAX_VELOCITY / Rules.getBulletSpeed(REF_POWER))
        private val REF_HALF_ANGLE = atan(Kinematics.HALF_BOT / REF_DISTANCE)

        private val AIM = VirtualGuns.Aim.values()
        private val AIM_LABELS = arrayOf("HO", "LIN", "CIR", "GFD", "GFR", "GFA", "GFW", "DC", "AS")
    }
}
