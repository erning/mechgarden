package zen.mirage

import robocode.AdvancedRobot
import robocode.BulletHitBulletEvent
import robocode.BulletHitEvent
import robocode.BulletMissedEvent
import robocode.HitByBulletEvent
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
    private val fireDetector by lazy { FireDetector(gunCoolingRate) }
    private val waves = EnemyWaveTracker()
    private val shadows = BulletShadows()

    private var self: RobotState? = null
    private var loaded = false
    private var movementSelector = MovementProfileSelector()
    private var movementProfile = MovementProfileSelector.Profile.PURE_SURF
    private var survivalPolicySelector = SurvivalPolicySelector()
    private var survivalPolicy = SurvivalPolicySelector.DEFAULT

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
    private var bulletsFired = 0
    private var bulletsHit = 0

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
        val firePower = fireDetector.detect(time, e.energy)
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
                    dangerBins =
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
                        ),
                    maxEscapeRadians = maxEscapeRadians,
                    maxEscapePositive = meaPos,
                    maxEscapeNegative = meaNeg,
                )
            waves.add(wave)
            shadows.onWave(wave, time)
        }
        if (firePower != null) {
            lastEnemyFirePower = firePower
            tracker.recordEnemyFire(firePower)
            survivalPolicySelector.recordEnemyFire(firePower)
        }

        val frame = tracker.onScan(e, self, battleFieldWidth, battleFieldHeight)

        // Passage bookkeeping: learn fully-passed waves as precise-interval visits.
        waves.sweep(time, x, y) { surfer.learnVisit(it, x, y) }

        // Gun: adaptive lead + fire gate. A real bullet casts shadows on waves in flight.
        // Hold fire against a bullet-shielding opponent to stop burning our own
        // energy on shots that get intercepted.
        val holdFire = shieldDetector.holdFire
        val fired = gun.fireControl(tracker, frame, battleFieldWidth, battleFieldHeight, holdFire)
        if (fired != null) {
            shadows.onFire(fired, time, waves.active)
            shieldDetector.onOurFire()
            bulletsFired++
        }

        // Dynamic engagement range: charger → keep distance, kiter → close in.
        // The mirage.range override (A/B tuning) forces a fixed range instead.
        smoothAdvancing = smoothAdvancing * 0.9 + (frame.derived?.advancingVelocity ?: 0.0) * 0.1
        val rangeProp = System.getProperty("mirage.range")?.toDoubleOrNull()
        val policyRange = survivalPolicy.targetRangeOverride
        targetRange =
            if (rangeProp != null) {
                rangeProp
            } else if (policyRange != null) {
                policyRange
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
            survivalPolicy.stopAllowed,
            virtualWave,
        )

        speedTwoAgo = speedOneAgo
        speedOneAgo = abs(velocity)
    }

    override fun onBulletHit(event: BulletHitEvent) {
        fireDetector.ourBulletHitEnemy(event.bullet.power)
        shadows.onBulletDead(event.bullet, time)
        gun.recordBulletHit(event.bullet)
        dealtThisRound += Rules.getBulletDamage(event.bullet.power)
        bulletsHit++
    }

    override fun onHitByBullet(event: HitByBulletEvent) {
        val hitWave = waves.matchBullet(time, event.bullet.x, event.bullet.y, event.bullet.velocity)
        if (hitWave != null) {
            surfer.learnHit(hitWave, event.bullet.x, event.bullet.y)
            if (System.getProperty("mirage.debug") != null) {
                val gf = hitWave.guessFactor(event.bullet.x, event.bullet.y)
                val bin = ((gf + 1.0) / 2.0 * (hitGfBins.size - 1)).roundToInt().coerceIn(0, hitGfBins.size - 1)
                hitGfBins[bin]++
            }
        }
        fireDetector.enemyBulletHitUs(event.power)
        damageThisRound += Rules.getBulletDamage(event.power)
    }

    override fun onBulletMissed(event: BulletMissedEvent) {
        shadows.onBulletGone(event.bullet)
        gun.recordBulletMiss(event.bullet)
    }

    override fun onBulletHitBullet(event: BulletHitBulletEvent) {
        // Our bullet destroyed an enemy bullet: that wave can't hit us — drop it so
        // it isn't mislearned as a passing visit.
        waves.matchBullet(time, event.hitBullet.x, event.hitBullet.y, event.hitBullet.velocity)
        shadows.onBulletDead(event.bullet, time)
        shieldDetector.onIntercepted()
        gun.recordBulletHitBullet(event.bullet)
    }

    override fun onRoundEnded(event: RoundEndedEvent) {
        movementSelector.recordDamage(damageThisRound)
        survivalPolicySelector.recordRound(
            survivalPolicy,
            survived = energy > 0.0,
            damageTaken = damageThisRound,
            damageDealt = dealtThisRound,
        )
        if (System.getProperty("mirage.debug") != null) {
            out.println(
                "MDBG r=${event.round + 1} dealt=${"%.1f".format(dealtThisRound)} " +
                    "taken=${"%.1f".format(damageThisRound)} fired=$bulletsFired hit=$bulletsHit " +
                    gun.debugStats() + " prof=$movementProfile policy=${survivalPolicy.kind} range=${"%.0f".format(targetRange)} " +
                    "hitGF=${hitGfBins.toList()}",
            )
        }
        dealtThisRound = 0.0
        damageThisRound = 0.0
        bulletsFired = 0
        bulletsHit = 0
        for (i in hitGfBins.indices) hitGfBins[i] = 0
    }

    private fun adoptEnemy(name: String) {
        survivalPolicySelector = SurvivalPolicySelector.forEnemy(name)
        survivalPolicy = survivalPolicySelector.policyForRound()
        surfer.adoptEnemy(name)
        movementSelector = MovementProfileSelector.forEnemy(name)
        movementProfile = selectedMovementProfile()
        gun.adoptEnemy(name)
        gun.setDefaultPowerProfile(survivalPolicy.powerProfile)
        gun.setDefaultPowerFloor(survivalPolicy.powerFloor)
    }

    private fun selectedMovementProfile(): MovementProfileSelector.Profile {
        val selected = movementSelector.profileForRound()
        return if (System.getProperty("mirage.profile") == null) {
            survivalPolicy.movementProfile ?: selected
        } else {
            selected
        }
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

    private companion object {
        const val DEFAULT_ENEMY_FIRE_POWER = 1.8
        const val RANGE_GAIN = 8.0
        const val RANGE_LO = 380.0
        const val RANGE_HI = 540.0
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
