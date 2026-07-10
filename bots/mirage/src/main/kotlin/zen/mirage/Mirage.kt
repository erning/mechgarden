package zen.mirage

import robocode.AdvancedRobot
import robocode.BulletHitBulletEvent
import robocode.BulletHitEvent
import robocode.BulletMissedEvent
import robocode.HitByBulletEvent
import robocode.HitRobotEvent
import robocode.RoundEndedEvent
import robocode.Rules
import robocode.ScannedRobotEvent
import robocode.StatusEvent
import java.awt.Color
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Mirage — a movement-first 1v1 wave-surfer built on Mirage's radar/tracker
 * foundations. Radar locking feeds a scan → track → fire pipeline; enemy shots are
 * detected from energy and modelled as expanding waves; the body surfs the nearest
 * wave toward the lowest-danger guess factor, protected by bullet shadows cast by
 * our own outgoing bullets; the gun fires a virtual-gun array whose DC (KNN) model
 * becomes the primary aim once it has learned enough — the right tool for hitting
 * adaptive surfers. All angles in radians.
 *
 * Movement is pure wave-surfing: full-speed orbit in both directions, lowest-
 * danger guess factor, protected by bullet shadows. Movement flattening was tried
 * and rejected — it pushed us off safe GFs onto still-dangerous ones, cutting
 * survival against strong guns. Physical shadows are what actually reduce
 * incoming damage.
 *
 * Per-opponent static registries (danger model, DC gun) survive Robocode's
 * per-round robot rebuild so learning accumulates across a battle's rounds.
 */
abstract class Mirage : AdvancedRobot() {
    private val radar = Radar(this)
    private val tracker = Tracker()
    private val motion = MotionController(this)
    private val gun = Gun(this)
    private val surfer = Surfer()
    private val shotDodger = ShotDodger()
    private val fireDetector by lazy { FireDetector(gunCoolingRate) }
    private val waves = EnemyWaveTracker()
    private val shadows = BulletShadows()

    private var self: RobotState? = null
    private var loaded = false
    private var movementSelector = MovementProfileSelector()
    private var movementProfile = MovementProfileSelector.Profile.PURE_SURF
    private var survivalPolicySelector = SurvivalPolicySelector()
    private var survivalPolicy = SurvivalPolicySelector.DEFAULT

    /** Per-enemy realized hit-rate against us (dodged vs connected waves). See
     *  [ThreatStats]; accumulates across rounds via its static registry. */
    private var threatStats = ThreatStats()

    /** Per-enemy threat-tier distance ladder (Plan A, mirage.harvest). See
     *  [HarvestController]; default on, `off` restores the old range behavior. */
    private var harvest = HarvestController()

    /** Live engagement range — adapted per scan from the enemy's advancing velocity
     *  (charger → keep distance, kiter → close in) and threaded into the surfer. */
    private var targetRange = Distancing.BASE_TARGET
    private var smoothAdvancing = 0.0

    /** Last clear orbit sense of our motion relative to the enemy's gun — held when
     *  our lateral speed is too small to give a reliable sign. */
    private var lastOrbitDirection = 1

    /** Our speed history for a wave's accel feature: a wave fired at scan t−1 needs
     *  |v(t−2)| (the speed before its fire-time snapshot). */
    private var speedOneAgo = 0.0
    private var speedTwoAgo = 0.0

    /** Bullet-shielding detector. Bullet-shielding opponents fire cheap bullets
     *  along our predicted aim line to collide with ours; when most of our shots
     *  die to BulletHitBullet we only burn our own energy and get out-waited. The
     *  detector holds fire once the recent intercept rate is high, stopping the
     *  self-inflicted energy drain. */
    private val shieldDetector = ShieldDetector()

    /** Bullet damage dealt/taken this round. */
    private var dealtThisRound = 0.0
    private var damageThisRound = 0.0
    private var lastEnemyFirePower = DEFAULT_ENEMY_FIRE_POWER

    /** Diagnostics counters (mirage.debug round summary). */
    private val engagementStats = EngagementStats()
    private val ramThreatDetector = RamThreatDetector()
    private val antiRamPlanner = AntiRamPlanner()
    private val activeShieldGun = ActiveShieldGun(this)
    private var bulletsFired = 0
    private var bulletsHit = 0
    private var bulletPowerFired = 0.0
    private var activeShieldUsedThisRound = false
    private var ramThreatSeenThisRound = false

    /** GF histogram of where enemy bullets hit us (mirage.debug), in 11 bins
     *  from GF -1 to +1. Reveals whether a gun has locked onto a movement
     *  pattern (a sharp peak) or hits everywhere (flat). */
    private val hitGfBins = IntArray(11)

    override fun run() {
        setBodyColor(Color(0xC0, 0x49, 0x22))
        setGunColor(Color(0xE8, 0xD8, 0xB0))
        setRadarColor(Color(0x2B, 0x20, 0x20))
        radar.beginRound()
        while (true) {
            radar.update()
            execute()
        }
    }

    override fun onStatus(e: StatusEvent) {
        self = RobotState.from(e.status)
    }

    override fun onScannedRobot(e: ScannedRobotEvent) {
        if (!loaded) {
            adoptEnemy(e.name)
            loaded = true
        }

        radar.onScan(e)
        // The radar steers the body and gun on its cold-start recovery tick; leave
        // the gun and movement to it until the lock settles.
        if (radar.state == Radar.State.ACQUIRING) return
        val self = self ?: return
        shieldDetector.tick()

        // Enemy fire detection from the *previous* paired snapshot (the shot was
        // fired last scan, from the enemy's then-position toward our then-position).
        val shooterFrame = tracker.current
        val firePower = fireDetector.detect(time, e.energy, energy)
        if (firePower != null && shooterFrame != null) {
            val shooter = shooterFrame.enemy
            val shotUs = shooterFrame.self
            val sourceToUs = Angles.absoluteBearing(shooter.x, shooter.y, shotUs.x, shotUs.y)
            val lateral = shotUs.velocity * sin(shotUs.headingRadians - sourceToUs)
            if (abs(lateral) > 0.5) lastOrbitDirection = if (lateral >= 0.0) 1 else -1
            val bulletSpeed = Rules.getBulletSpeed(firePower)
            val features = WaveFeatures.at(shotUs, shooter.x, shooter.y, bulletSpeed, speedTwoAgo, battleFieldWidth, battleFieldHeight)
            val theoryMea = StrictMath.asin(Kinematics.MAX_VELOCITY / bulletSpeed)
            // Surf-side MEA. Default keeps the theory MEA the danger model was tuned
            // under: precise MEA here can reshape the GF geometry enough to hurt
            // movement profiles that were tuned around the textbook envelope.
            // mirage.mea=precise opts the surf into precise MEA (symmetric max);
            // mirage.dirmea=on uses direction-aware precise MEA (each GF side scaled
            // by its own wall-reachable bound) — counters the wall-driven GF bias;
            // mirage.mea=theory reverts both gun and surf to the textbook value.
            val dirMea = System.getProperty("mirage.dirmea") == "on"
            val precise = dirMea || System.getProperty("mirage.mea") == "precise"
            val maxEscapeRadians: Double
            val meaPos: Double
            val meaNeg: Double
            if (precise) {
                val targetPose = Kinematics.Pose(shotUs.x, shotUs.y, shotUs.headingRadians, shotUs.velocity)
                if (dirMea) {
                    val pair =
                        PreciseMea.directionalEscapeRadians(
                            shooter.x,
                            shooter.y,
                            shooter.time,
                            bulletSpeed,
                            targetPose,
                            shooter.time,
                            battleFieldWidth,
                            battleFieldHeight,
                        )
                    meaPos = pair[0].coerceIn(MIN_ESCAPE_RADIANS, theoryMea)
                    meaNeg = pair[1].coerceIn(MIN_ESCAPE_RADIANS, theoryMea)
                    maxEscapeRadians = maxOf(meaPos, meaNeg)
                } else {
                    maxEscapeRadians =
                        PreciseMea
                            .halfEscapeRadians(
                                shooter.x,
                                shooter.y,
                                shooter.time,
                                bulletSpeed,
                                targetPose,
                                shooter.time,
                                battleFieldWidth,
                                battleFieldHeight,
                            ).coerceIn(MIN_ESCAPE_RADIANS, theoryMea)
                    meaPos = maxEscapeRadians
                    meaNeg = maxEscapeRadians
                }
            } else {
                maxEscapeRadians = theoryMea
                meaPos = theoryMea
                meaNeg = theoryMea
            }
            val simpleTargetPredictions =
                SimulatedTargeting.predictions(
                    shooter.x,
                    shooter.y,
                    shotUs.x,
                    shotUs.y,
                    shotUs.headingRadians,
                    shotUs.velocity,
                    observedTurnRateRadians(shotUs, tracker.previous?.self),
                    sourceToUs,
                    lastOrbitDirection,
                    maxEscapeRadians,
                    bulletSpeed,
                    battleFieldWidth,
                    battleFieldHeight,
                )
            val baseDanger =
                surfer.bakeDangerWithPrior(
                    features,
                    shooter.x,
                    shooter.y,
                    shotUs.x,
                    shotUs.y,
                    shotUs.headingRadians,
                    shotUs.velocity,
                    sourceToUs,
                    lastOrbitDirection,
                    maxEscapeRadians,
                    bulletSpeed,
                    battleFieldWidth,
                    battleFieldHeight,
                    survivalPolicy.simulatedPriorWeight,
                )
            val wave =
                EnemyWave(
                    sourceX = shooter.x,
                    sourceY = shooter.y,
                    fireTime = shooter.time,
                    power = firePower,
                    velocity = bulletSpeed,
                    directAngleRadians = sourceToUs,
                    orbitDirection = lastOrbitDirection,
                    features = features,
                    dangerBins = shotDodger.augmentDanger(baseDanger, simpleTargetPredictions),
                    maxEscapeRadians = maxEscapeRadians,
                    maxEscapePositive = meaPos,
                    maxEscapeNegative = meaNeg,
                    // This paired frame is the prior scan used by classic
                    // one-tick-late gun commands (Saguaro shield option 0).
                    shieldHeadingRadians = sourceToUs,
                    simpleTargetPredictions = simpleTargetPredictions,
                )
            waves.add(wave)
            shadows.onWave(wave, time)
        }
        if (firePower != null) {
            engagementStats.recordEnemyFire(firePower)
            lastEnemyFirePower = firePower
            tracker.recordEnemyFire(firePower)
            survivalPolicySelector.recordEnemyFire(firePower)
        }

        val frame = tracker.onScan(e, self, battleFieldWidth, battleFieldHeight)
        ramThreatDetector.observe(frame)
        val ramThreat = ramThreatDetector.snapshot()
        if (ramThreat.active) ramThreatSeenThisRound = true
        val antiRamPlan =
            if (ramEscapeEnabled()) {
                antiRamPlanner.plan(frame, ramThreat, battleFieldWidth, battleFieldHeight)
            } else {
                null
            }
        val wallSpace =
            minOf(
                frame.self.x - Kinematics.HALF_BOT,
                battleFieldWidth - Kinematics.HALF_BOT - frame.self.x,
                frame.self.y - Kinematics.HALF_BOT,
                battleFieldHeight - Kinematics.HALF_BOT - frame.self.y,
            )
        engagementStats.recordScan(
            time = time,
            distance = frame.enemy.distance,
            closingSpeed = -(frame.derived?.distanceRate ?: 0.0),
            wallSpace = wallSpace,
        )

        // Passage bookkeeping: learn fully-passed waves as precise-interval visits.
        waves.sweep(time, x, y) {
            threatStats.recordWavePassed()
            shotDodger.recordPass(it)
            surfer.learnVisit(it, x, y)
        }

        // Plan C.1 (mirage.harvestpower): against a low-threat enemy we hit
        // reliably, let the gun pick BALANCED (maxPower 3.0) instead of the
        // survival policy's ECONOMY cap, so EV can choose high power and finish
        // faster. ECONOMY otherwise — dropping the cap broadly self-disables us
        // against strong guns (validated: BALANCED-everywhere costs ~8 survival).
        val harvestTier = harvest.tier(threatStats.enemyHitRate(), threatStats.wavesObserved())
        // Reset to the round policy before applying per-scan tactical overrides.
        // Otherwise one expired ram-threat latch would leave AGGRESSIVE selected
        // for the rest of the round.
        gun.setDefaultPowerProfile(survivalPolicy.powerProfile)
        gun.setDefaultPowerFloor(survivalPolicy.powerFloor)
        if (antiRamEnabled() && ramThreatDetector.active()) {
            gun.setDefaultPowerProfile(FirePowerSelector.Profile.AGGRESSIVE)
            gun.setDefaultPowerFloor(HARVEST_POWER_FLOOR)
        } else if (harvestPowerEnabled()) {
            if (harvestTier == HarvestController.Tier.LOW) {
                gun.setDefaultPowerProfile(FirePowerSelector.Profile.BALANCED)
                gun.setDefaultPowerFloor(HARVEST_POWER_FLOOR)
            } else {
                gun.setDefaultPowerProfile(survivalPolicy.powerProfile)
                gun.setDefaultPowerFloor(survivalPolicy.powerFloor)
            }
        }

        // Gun: adaptive lead + fire gate. A real bullet casts shadows on waves in flight.
        // Hold fire against a bullet-shielding opponent to stop burning our own
        // energy on shots that get intercepted.
        val holdFire = shieldDetector.holdFire
        val activeShieldMode =
            activeShieldEnabled() &&
                (activeShieldForced() || !activeShieldGun.adaptiveTrial() || !ramThreat.active)
        // A latched shield policy has already paid its exploration cost and
        // demonstrated higher score utility, so it keeps priority over anti-ram.
        // An unvalidated trial yields as soon as ram behavior is detected.
        val movementAntiRamPlan = if (activeShieldMode) null else antiRamPlan
        if (activeShieldMode) activeShieldUsedThisRound = true
        val activeShieldPlan =
            if (activeShieldMode) {
                activeShieldGun.plan(time, waves.active, battleFieldWidth, battleFieldHeight)
            } else {
                null
            }
        val fired =
            if (activeShieldPlan != null) {
                // Keep all normal gun models current while the shield owns the turret.
                gun.fireControl(tracker, frame, battleFieldWidth, battleFieldHeight, holdFire = true)
                activeShieldGun.execute(activeShieldPlan)
            } else {
                gun.fireControl(tracker, frame, battleFieldWidth, battleFieldHeight, holdFire)
            }
        if (fired != null) {
            shadows.onFire(fired, time, waves.active)
            if (activeShieldPlan == null) shieldDetector.onOurFire()
            fireDetector.ourFire(fired.power)
            bulletsFired++
            bulletPowerFired += fired.power
        }

        // Dynamic engagement range: charger → keep distance, kiter → close in.
        // The mirage.range override (A/B tuning) forces a fixed range instead.
        smoothAdvancing = smoothAdvancing * 0.9 + (frame.derived?.advancingVelocity ?: 0.0) * 0.1
        val rangeProp = System.getProperty("mirage.range")?.toDoubleOrNull()
        val policyRange = survivalPolicy.targetRangeOverride
        val endgameRange = endgameCloseRange(e.energy)
        val harvestRangeVal = harvestRange(harvestTier)
        // Priority: mirage.range debug override > endgame finish (Phase 1) >
        // anti-ram escape > survival-policy override > threat-tier harvest
        // (Phase 2) > advancing-velocity formula.
        targetRange =
            if (rangeProp != null) {
                rangeProp
            } else if (endgameRange != null) {
                endgameRange
            } else if (movementAntiRamPlan != null) {
                movementAntiRamPlan.targetRange
            } else if (policyRange != null) {
                policyRange
            } else if (harvestRangeVal != null) {
                harvestRangeVal
            } else {
                (Distancing.BASE_TARGET + RANGE_GAIN * smoothAdvancing).coerceIn(RANGE_LO, RANGE_HI)
            }

        val selfPose = Kinematics.Pose(x, y, headingRadians, velocity)
        val shieldStyle =
            shieldDetector.holdFire ||
                tracker.shieldLikely ||
                tracker.ticksSinceLowPowerFire <= LOW_POWER_PROFILE_TICKS ||
                tracker.stationaryTicks >= SHIELD_STATIONARY_PROFILE_TICKS
        if (shieldStyle) movementSelector.markShieldStyle()
        val activeMovementProfile =
            if (shieldStyle) {
                MovementProfileSelector.Profile.PURE_SURF
            } else {
                movementProfile
            }
        val virtualWave = virtualWave(frame, selfPose)
        if (endgameRange != null && endgameRamEnabled() && energy > RAM_MIN_ENERGY) {
            // B.3 (mirage.endgame=ram): drive into the disabled enemy to finish
            // via contact. The point-blank capPower bullet usually lands first and
            // keeps the 20% bullet-kill bonus; ramming is a backup, gated on
            // enough energy to absorb the 0.6 self-damage per contact.
            motion.driveAlongRadians(Angles.absoluteBearing(x, y, frame.enemy.x, frame.enemy.y))
        } else if (movementAntiRamPlan?.escapeHeadingRadians != null) {
            motion.driveAlongRadians(movementAntiRamPlan.escapeHeadingRadians)
        } else {
            surfer.surf(
                time,
                selfPose,
                frame,
                waves.active,
                motion,
                battleFieldWidth,
                battleFieldHeight,
                activeMovementProfile,
                targetRange,
                survivalPolicy.dangerMode,
                if (movementAntiRamPlan != null) false else survivalPolicy.stopAllowed,
                virtualWave,
                movementAntiRamPlan?.preferredDirection,
                movementAntiRamPlan?.directionPenalty ?: 0.0,
            )
        }

        speedTwoAgo = speedOneAgo
        speedOneAgo = abs(velocity)
    }

    override fun onBulletHit(event: BulletHitEvent) {
        fireDetector.ourBulletHitEnemy(event.bullet.power)
        shadows.onBulletDead(event.bullet, time)
        if (!activeShieldGun.recordBulletHit(event.bullet)) gun.recordBulletHit(event.bullet)
        dealtThisRound += Rules.getBulletDamage(event.bullet.power)
        bulletsHit++
    }

    override fun onHitByBullet(event: HitByBulletEvent) {
        val hitWave = waves.matchBullet(time, event.bullet.x, event.bullet.y, event.bullet.velocity)
        if (hitWave != null) {
            shotDodger.recordHit(hitWave, event.bullet.x, event.bullet.y)
            surfer.learnHit(hitWave, event.bullet.x, event.bullet.y)
            if (System.getProperty("mirage.debug") != null) {
                val gf = hitWave.guessFactor(event.bullet.x, event.bullet.y)
                val bin = ((gf + 1.0) / 2.0 * (hitGfBins.size - 1)).roundToInt().coerceIn(0, hitGfBins.size - 1)
                hitGfBins[bin]++
            }
        }
        threatStats.recordHit()
        fireDetector.enemyBulletHitUs(event.power)
        damageThisRound += Rules.getBulletDamage(event.power)
    }

    override fun onHitRobot(event: HitRobotEvent) {
        engagementStats.recordCollision(event.isMyFault)
        ramThreatDetector.recordCollision()
        antiRamPlanner.recordCollision()
        ramThreatSeenThisRound = true
    }

    override fun onBulletMissed(event: BulletMissedEvent) {
        shadows.onBulletGone(event.bullet)
        if (!activeShieldGun.recordBulletMiss(event.bullet)) gun.recordBulletMiss(event.bullet)
    }

    override fun onBulletHitBullet(event: BulletHitBulletEvent) {
        // Our bullet destroyed an enemy bullet: that wave can't hit us — drop it so
        // it isn't mislearned as a passing visit.
        waves.matchBullet(time, event.hitBullet.x, event.hitBullet.y, event.hitBullet.velocity)
        shadows.onBulletDead(event.bullet, time)
        if (!activeShieldGun.recordBulletHitBullet(event.bullet)) {
            shieldDetector.onIntercepted()
            gun.recordBulletHitBullet(event.bullet)
        }
    }

    override fun onRoundEnded(event: RoundEndedEvent) {
        activeShieldGun.recordRound(
            dealtThisRound,
            damageThisRound,
            survived = energy > 0.0,
            usedActiveShield = activeShieldUsedThisRound,
            ramThreat = ramThreatSeenThisRound,
        )
        movementSelector.recordDamage(damageThisRound)
        harvest.recordRound(threatStats.enemyHitRate(), threatStats.wavesObserved(), damageThisRound)
        survivalPolicySelector.recordRound(
            survivalPolicy,
            survived = energy > 0.0,
            damageTaken = damageThisRound,
            damageDealt = dealtThisRound,
        )
        if (System.getProperty("mirage.debug") != null) {
            val enemyHitRate = threatStats.enemyHitRate()
            val enemyHitRateText =
                if (enemyHitRate.isNaN()) {
                    "n/a"
                } else {
                    "%.3f@%d".format(enemyHitRate, threatStats.wavesObserved())
                }
            out.println(
                "MDBG r=${event.round + 1} dealt=${"%.1f".format(dealtThisRound)} " +
                    "taken=${"%.1f".format(damageThisRound)} fired=$bulletsFired hit=$bulletsHit " +
                    "avgPower=${"%.2f".format(if (bulletsFired == 0) 0.0 else bulletPowerFired / bulletsFired)} " +
                    "ticks=$time " +
                    engagementStats.debugSummary() + " " +
                    ramThreatDetector.debugSummary() + " " +
                    antiRamPlanner.debugSummary() + " " +
                    shotDodger.debugSummary() + " " +
                    activeShieldGun.debugSummary() + " " +
                    gun.debugStats() + " prof=$movementProfile policy=${survivalPolicy.kind} range=${"%.0f".format(targetRange)} " +
                    "hitGF=${hitGfBins.toList()} ehr=$enemyHitRateText",
            )
        }
        dealtThisRound = 0.0
        damageThisRound = 0.0
        bulletsFired = 0
        bulletsHit = 0
        bulletPowerFired = 0.0
        activeShieldUsedThisRound = false
        ramThreatSeenThisRound = false
        engagementStats.reset()
        for (i in hitGfBins.indices) hitGfBins[i] = 0
    }

    private fun adoptEnemy(name: String) {
        survivalPolicySelector = SurvivalPolicySelector.forEnemy(name)
        survivalPolicy = survivalPolicySelector.policyForRound()
        surfer.adoptEnemy(name)
        shotDodger.adoptEnemy(name)
        movementSelector = MovementProfileSelector.forEnemy(name)
        movementProfile = selectedMovementProfile()
        gun.adoptEnemy(name)
        activeShieldGun.adoptEnemy(name)
        gun.setDefaultPowerProfile(survivalPolicy.powerProfile)
        gun.setDefaultPowerFloor(survivalPolicy.powerFloor)
        threatStats = ThreatStats.forEnemy(name)
        harvest = HarvestController.forEnemy(name)
    }

    private fun selectedMovementProfile(): MovementProfileSelector.Profile {
        // profileForRound() has side effects (rounds counter, chosen profile, auto
        // exploration) and feeds the damage latch, so always invoke it.
        val selected = movementSelector.profileForRound()
        val forced = System.getProperty("mirage.profile")
        if (forced != null) return selected
        // A survival policy that mandates a movement profile (NOISY_ORBIT,
        // SURVIVAL_SEARCH) wins outright.
        survivalPolicy.movementProfile?.let { return it }
        // Otherwise the movement layer owns the PURE_SURF vs FLAT_SURF choice. The
        //  flattener is latched on by recent damage taken (see
        //  MovementProfileSelector.flattenRecommended): on against sustained-accurate
        //  guns where flattening disrupts their lock and no safe GFs are lost, off
        //  against weak guns we already dodge (where flattening would cost safe GFs).
        return if (movementSelector.flattenRecommended()) {
            MovementProfileSelector.Profile.FLAT_SURF
        } else {
            MovementProfileSelector.Profile.PURE_SURF
        }
    }

    private fun observedTurnRateRadians(
        state: RobotState,
        previous: RobotState?,
    ): Double {
        if (previous == null) return 0.0
        val elapsedTicks = state.time - previous.time
        if (elapsedTicks !in 1L..8L) return 0.0
        return Angles.normalizeRelative(state.headingRadians - previous.headingRadians) / elapsedTicks.toDouble()
    }

    private fun virtualWave(
        frame: Tracker.Frame,
        selfPose: Kinematics.Pose,
    ): EnemyWave? {
        if (!virtualWavesEnabled()) return null
        val ticksUntilFire = fireDetector.ticksUntilFireAllowed(time)
        val maxLead = System.getProperty("mirage.virtualLead")?.toLongOrNull() ?: survivalPolicy.virtualLeadTicks
        if (ticksUntilFire > maxLead) return null
        val maxPower = minOf(Rules.MAX_BULLET_POWER, frame.enemy.energy)
        if (maxPower < Rules.MIN_BULLET_POWER) return null

        val fireTime = time + ticksUntilFire.coerceAtLeast(1L)
        val predictionTicks = (fireTime - frame.enemy.time).coerceIn(0L, MAX_VIRTUAL_PREDICTION_TICKS)
        val sourceX =
            (frame.enemy.x + sin(frame.enemy.headingRadians) * frame.enemy.velocity * predictionTicks)
                .coerceIn(Kinematics.HALF_BOT, battleFieldWidth - Kinematics.HALF_BOT)
        val sourceY =
            (frame.enemy.y + cos(frame.enemy.headingRadians) * frame.enemy.velocity * predictionTicks)
                .coerceIn(Kinematics.HALF_BOT, battleFieldHeight - Kinematics.HALF_BOT)
        val power =
            (System.getProperty("mirage.virtualPower")?.toDoubleOrNull() ?: lastEnemyFirePower)
                .coerceIn(Rules.MIN_BULLET_POWER, maxPower)
        val bulletSpeed = Rules.getBulletSpeed(power)
        val directAngle = Angles.absoluteBearing(sourceX, sourceY, selfPose.x, selfPose.y)
        val lateral = selfPose.velocity * sin(selfPose.headingRadians - directAngle)
        val orbitDirection =
            when {
                abs(lateral) > 0.5 -> if (lateral >= 0.0) 1 else -1
                else -> lastOrbitDirection
            }
        val features =
            WaveFeatures.at(
                frame.self,
                sourceX,
                sourceY,
                bulletSpeed,
                speedOneAgo,
                battleFieldWidth,
                battleFieldHeight,
            )
        val maxEscapeRadians = StrictMath.asin(Kinematics.MAX_VELOCITY / bulletSpeed)
        val priorWeight =
            System.getProperty("mirage.virtualPrior")?.toDoubleOrNull() ?: survivalPolicy.virtualPriorWeight
        return EnemyWave(
            sourceX = sourceX,
            sourceY = sourceY,
            fireTime = fireTime,
            power = power,
            velocity = bulletSpeed,
            directAngleRadians = directAngle,
            orbitDirection = orbitDirection,
            features = features,
            dangerBins =
                surfer.bakeDangerWithPrior(
                    features,
                    sourceX,
                    sourceY,
                    selfPose.x,
                    selfPose.y,
                    selfPose.headingRadians,
                    selfPose.velocity,
                    directAngle,
                    orbitDirection,
                    maxEscapeRadians,
                    bulletSpeed,
                    battleFieldWidth,
                    battleFieldHeight,
                    priorWeight,
                ),
            maxEscapeRadians = maxEscapeRadians,
        )
    }

    private fun virtualWavesEnabled(): Boolean =
        when (System.getProperty("mirage.virtual")?.trim()?.lowercase()) {
            "on", "true", "yes", "force" -> true
            "off", "false", "no" -> false
            else -> survivalPolicy.virtualWaves
        }

    /** Endgame finish (Phase 1, mirage.endgame): against a disabled enemy (energy
     *  drained to ~0 by self-fire or inactivity, alive but unable to act) with no
     *  waves in flight, collapse to point-blank so Gun.capPower's lethal shot
     *  lands immediately and the kill is credited to us before the engine's
     *  inactivity zap takes it. Returns the override range, or null when inactive.
     *  Disabled-but-alive is a real state: a bullet kill (BulletPeer) fires at
     *  energy <= 0, but self-drain only zeroes and parks the robot. */
    private fun endgameCloseRange(enemyEnergy: Double): Double? {
        if (!endgameEnabled()) return null
        if (enemyEnergy > ENDGAME_DISABLED_ENERGY) return null
        if (waves.active.isNotEmpty()) return null
        return ENDGAME_CLOSE_RANGE
    }

    private fun endgameEnabled(): Boolean {
        val key = System.getProperty("mirage.endgame")?.trim()?.lowercase()
        return key != "off"
    }

    private fun endgameRamEnabled(): Boolean = System.getProperty("mirage.endgame")?.trim()?.lowercase() == "ram"

    /** Plan A threat-tier distance (`mirage.harvest`): against a low-threat
     *  enemy, close down a ladder (350->300->260) to finish faster. `off` keeps
     *  the old range; unset is the enabled default; a number forces a fixed range
     *  for A/B. Returns null when no override applies. */
    private fun harvestRange(tier: HarvestController.Tier): Double? {
        val key = System.getProperty("mirage.harvest")?.trim()?.lowercase()
        if (key == "off") return null
        key?.toDoubleOrNull()?.let { return it }
        return if (tier == HarvestController.Tier.LOW) harvest.lowRange() else null
    }

    private fun harvestPowerEnabled(): Boolean = System.getProperty("mirage.harvestpower")?.trim()?.lowercase() == "on"

    private fun antiRamEnabled(): Boolean =
        when (System.getProperty("mirage.antiram")?.trim()?.lowercase()) {
            "off", "false", "no" -> false
            else -> true
        }

    /** A/B override for the movement half of anti-ram. `mirage.antiram=off`
     *  remains the master switch and also disables the established close-range
     *  firepower response; `mirage.ramescape=off` leaves that gun behavior intact
     *  while removing only [AntiRamPlanner]. */
    private fun ramEscapeEnabled(): Boolean {
        if (!antiRamEnabled()) return false
        return when (System.getProperty("mirage.ramescape")?.trim()?.lowercase()) {
            "off", "false", "no" -> false
            else -> true
        }
    }

    private fun activeShieldEnabled(): Boolean =
        when (System.getProperty("mirage.activeshield")?.trim()?.lowercase()) {
            "on", "true", "yes", "force" -> true
            "off", "false", "no" -> false
            else -> activeShieldGun.adaptiveReady()
        }

    private fun activeShieldForced(): Boolean =
        when (System.getProperty("mirage.activeshield")?.trim()?.lowercase()) {
            "on", "true", "yes", "force" -> true
            else -> false
        }

    private companion object {
        const val DEFAULT_ENEMY_FIRE_POWER = 1.8
        const val RANGE_GAIN = 8.0
        const val RANGE_LO = 380.0
        const val RANGE_HI = 540.0
        const val ENDGAME_DISABLED_ENERGY = 0.001
        const val ENDGAME_CLOSE_RANGE = 90.0
        const val RAM_MIN_ENERGY = 20.0
        const val HARVEST_POWER_FLOOR = 1.2
        const val LOW_POWER_PROFILE_TICKS = 30L
        const val SHIELD_STATIONARY_PROFILE_TICKS = 12L
        const val MAX_VIRTUAL_PREDICTION_TICKS = 8L

        /** Precise-MEA floor: never let the escape envelope collapse below this,
         *  so a degenerate wall-pinned reading can't shrink the GF scale enough to
         *  smear all danger into one bin. Well below the smallest realistic
         *  precise MEA in open space. */
        const val MIN_ESCAPE_RADIANS = 0.18
    }
}
