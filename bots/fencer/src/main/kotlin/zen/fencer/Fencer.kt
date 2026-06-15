package zen.fencer

import robocode.AdvancedRobot
import robocode.BulletHitBulletEvent
import robocode.BulletHitEvent
import robocode.BulletMissedEvent
import robocode.HitByBulletEvent
import robocode.Rules
import robocode.ScannedRobotEvent
import java.awt.Color
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Fencer — layered 1v1 robot with radar locking, scan tracking, enemy-wave
 * detection, virtual guns, bullet shadows, and wave surfing.
 */
abstract class Fencer : AdvancedRobot() {
    private val radar = Radar(this)
    private val tracker = EnemyTracker()
    private val motion = MotionController(this)
    private val gun = Gun(this)
    private val surfer = Surfer(ACTIVE_PROFILE)
    private val waves = EnemyWaveTracker()
    private val shadows = BulletShadows()
    private val fireDetector by lazy { FireDetector(gunCoolingRate) }

    /** Per-opponent setup: bind static learned surf state on first sight. */
    private var loaded = false

    /** Last clear orbit sense of our motion relative to the enemy's gun — used
     * for waves fired while our lateral speed is too small to give one. */
    private var lastOrbitDirection = 1

    /** Our speed history for the wave features: a wave fired at scan t−1 needs
     * |v(t−2)| (the speed before its fire-time snapshot) for the accel sign,
     * and the tick our speed last dropped / direction flipped. */
    private var speedOneAgo = 0.0
    private var speedTwoAgo = 0.0
    private var velocityOneAgo = 0.0
    private var lastDecelTime = 0L

    override fun run() {
        setBodyColor(Color(0x2E, 0x4A, 0x62))
        setGunColor(Color(0x9B, 0xC1, 0xBC))
        setRadarColor(Color(0xE8, 0xA8, 0x7C))
        isAdjustGunForRobotTurn = true
        isAdjustRadarForGunTurn = true

        radar.search()
        while (true) {
            // Re-acquire when the fix is too stale to trust (uncertainty layer).
            if (Uncertainty.shouldReacquire(tracker.scanAge(time))) radar.search()
            execute()
        }
    }

    override fun onScannedRobot(e: ScannedRobotEvent) {
        // First sight this round: bind the per-enemy static surf model.
        if (!loaded) {
            surfer.adoptEnemy(e.name)
            loaded = true
        }

        val absBearing = Angles.normalizeAbsolute(heading + e.bearing)

        // Radar layer: keep the enemy locked first, so observation stays fresh.
        radar.lock(absBearing)

        // Enemy fire detection (before the tracker updates, so the previous
        // snapshot is the fire source): infer a shot from the energy account and
        // model it as an expanding wave.
        //
        // Time consistency: the shot was fired at the *previous*
        // scan, from the enemy's then-position toward *our* then-position. Build
        // the wave entirely from that one paired snapshot — both [tracker.enemy]
        // and [tracker.self] predate this tick's onScan, and [tracker.lastScanTime]
        // is its tick — rather than mixing the last enemy fix with this tick's pose.
        val shooter = tracker.enemy
        val shotUs = tracker.self
        val firePower = fireDetector.detect(time, e.energy)
        if (firePower != null && shooter != null && shotUs != null) {
            val sourceToUs = Angles.absoluteBearing(shooter.x, shooter.y, shotUs.x, shotUs.y)
            val lateral = shotUs.velocity * sin(Math.toRadians(shotUs.headingDeg - sourceToUs))
            val srcDist = hypot(shotUs.x - shooter.x, shotUs.y - shooter.y)
            // Near-zero lateral speed makes the sign a coin flip that would
            // mirror the wave's GF axis at random — hold the last clear sense.
            if (abs(lateral) > 0.5) lastOrbitDirection = if (lateral >= 0.0) 1 else -1
            val features =
                WaveFeatures.at(
                    us = shotUs,
                    sourceX = shooter.x,
                    sourceY = shooter.y,
                    bulletSpeed = Rules.getBulletSpeed(firePower),
                    prevSpeedAbs = speedTwoAgo,
                    ticksSinceDecel = (tracker.lastScanTime - lastDecelTime).coerceAtLeast(0L),
                    fieldWidth = battleFieldWidth,
                    fieldHeight = battleFieldHeight,
                )
            val wave =
                EnemyWave(
                    sourceX = shooter.x,
                    sourceY = shooter.y,
                    fireTime = tracker.lastScanTime,
                    power = firePower,
                    velocity = Rules.getBulletSpeed(firePower),
                    directAngleDeg = sourceToUs,
                    orbitDirection = lastOrbitDirection,
                    features = features,
                    dangerBins = surfer.bakeDanger(features),
                )
            waves.add(wave)
            shadows.onWave(wave, time)
        }

        // Observation pipeline (scan → record): feed the tracker our state + the
        // scanned enemy, so all derived facts come from one consistent time slice.
        tracker.onScan(
            time = time,
            selfX = x,
            selfY = y,
            selfHeadingDeg = heading,
            selfVelocity = velocity,
            selfEnergy = energy,
            absoluteBearingDeg = absBearing,
            enemyDistance = e.distance,
            enemyHeadingDeg = e.heading,
            enemyVelocity = e.velocity,
            enemyEnergy = e.energy,
            fieldWidth = battleFieldWidth,
            fieldHeight = battleFieldHeight,
        )

        // Passage bookkeeping: widen crossing waves' covered-GF intervals, learn
        // fully-passed waves as precise-interval visits, and drop them.
        waves.sweep(time, x, y).forEach { surfer.learnVisit(it, x, y) }

        // Gun: adaptive lead plus fire gate. A real bullet casts shadows on waves in flight.
        val fired = gun.fireControl(tracker, battleFieldWidth, battleFieldHeight)
        if (fired != null) shadows.onFire(fired, time, waves.active)

        // Movement: surf the incoming wave.
        val selfPose = Kinematics.Pose(x, y, heading, velocity)
        surfer.surf(time, selfPose, tracker.enemy, waves.active, motion, battleFieldWidth, battleFieldHeight)

        // Speed history for the next wave's features (after all uses above).
        if (abs(velocity) < speedOneAgo - WaveFeatures.ACCEL_EPS || velocity * velocityOneAgo < 0.0) {
            lastDecelTime = time
        }
        speedTwoAgo = speedOneAgo
        speedOneAgo = abs(velocity)
        velocityOneAgo = velocity
    }

    /** Our bullet hit the enemy — feed the energy account so it isn't mistaken
     * for the enemy firing, and retract the shadows it hadn't flown yet. */
    override fun onBulletHit(event: BulletHitEvent) {
        fireDetector.ourBulletHitEnemy(event.bullet.power)
        shadows.onBulletDead(event.bullet, time)
    }

    /** Our bullet flew to a wall: its full path happened, every shadow stands. */
    override fun onBulletMissed(event: BulletMissedEvent) {
        shadows.onBulletGone(event.bullet)
    }

    /** The enemy's bullet hit us — learn the wave (weighted), and recover the
     * enemy's energy gain in the fire account. */
    override fun onHitByBullet(event: HitByBulletEvent) {
        val hitWave = waves.matchBullet(time, event.bullet.x, event.bullet.y, event.bullet.velocity)
        if (hitWave != null) surfer.learnHit(hitWave, event.bullet.x, event.bullet.y)
        fireDetector.enemyBulletHitUs(event.power)
    }

    /** One of our bullets destroyed an enemy bullet: that wave is gone and
     * can't hit us — drop it so it isn't mislearned as a passing visit. Our
     * bullet died too, so its still-pending shadows are retracted. */
    override fun onBulletHitBullet(event: BulletHitBulletEvent) {
        waves.matchBullet(time, event.hitBullet.x, event.hitBullet.y, event.hitBullet.velocity)
        shadows.onBulletDead(event.bullet, time)
    }

    private companion object {
        /** Active movement profile for this robot. */
        val ACTIVE_PROFILE = SurfProfile.MULTI_WAVE
    }
}
