package zen.ronin

import robocode.AdvancedRobot
import robocode.BulletHitBulletEvent
import robocode.BulletHitEvent
import robocode.HitByBulletEvent
import robocode.RoundEndedEvent
import robocode.Rules
import robocode.ScannedRobotEvent
import java.awt.Color
import kotlin.math.abs
import kotlin.math.sin

/**
 * Ronin — a flattener wave-surfer for 1v1. Radar locking feeds a scan→track→fire
 * pipeline; enemy shots are detected from energy and modelled as expanding waves;
 * the gun fires a virtual-gun array whose best model is selected by recency-decayed
 * virtual hits; movement surfs the incoming wave while a flattener (scaled by the
 * enemy's hit rate) spreads our visits so an adaptive GF gun can't lock on.
 *
 * Per-opponent static registries for the danger/flattener ensembles and DC gun
 * let slow learners retain state across Robocode's per-round rebuilds.
 */
abstract class Ronin : AdvancedRobot() {
    private val radar = Radar(this)
    private val tracker = EnemyTracker()
    private val motion = MotionController(this)
    private val gun = Gun(this)
    private val surfer = Surfer()
    private val fireDetector by lazy { FireDetector(gunCoolingRate) }
    private val waves = EnemyWaveTracker()
    private val shadows = BulletShadows()

    private var loaded = false
    private var enemyName = ""
    private var movementSelector = MovementProfileSelector()
    private var damageThisRound = 0.0

    /** Last clear orbit sense of our motion relative to the enemy's gun — held
     * when our lateral speed is too small to give a reliable sign. */
    private var lastOrbitDirection = 1

    /** Our speed history for a wave's accel feature: a wave fired at scan t−1
     * needs |v(t−2)| (the speed before its fire-time snapshot). */
    private var speedOneAgo = 0.0
    private var speedTwoAgo = 0.0
    private var velocityOneAgo = 0.0
    private var smoothAdvancing = 0.0

    override fun run() {
        setBodyColor(Color(0x33, 0x2A, 0x1B))
        setGunColor(Color(0x8C, 0x6A, 0x3F))
        setRadarColor(Color(0xC8, 0xB5, 0x8A))
        isAdjustGunForRobotTurn = true
        isAdjustRadarForGunTurn = true

        radar.search()
        while (true) {
            if (tracker.scanAge(time) > REACQUIRE_TICKS) radar.search()
            execute()
        }
    }

    override fun onScannedRobot(e: ScannedRobotEvent) {
        if (!loaded) {
            adoptEnemy(e.name)
            loaded = true
        }

        val absBearing = Angles.normalizeAbsolute(heading + e.bearing)
        radar.lock(absBearing)

        // Enemy fire detection from the *previous* paired snapshot (the shot was
        // fired last scan, from the enemy's then-position toward our then-position).
        val shooter = tracker.enemy
        val shotUs = tracker.self
        val firePower = fireDetector.detect(time, e.energy)
        if (firePower != null && shooter != null && shotUs != null) {
            val sourceToUs = Angles.absoluteBearing(shooter.x, shooter.y, shotUs.x, shotUs.y)
            val lateral = shotUs.velocity * sin(Math.toRadians(shotUs.headingDeg - sourceToUs))
            if (abs(lateral) > 0.5) lastOrbitDirection = if (lateral >= 0.0) 1 else -1
            val bulletSpeed = Rules.getBulletSpeed(firePower)
            val features = WaveFeatures.at(shotUs, shooter.x, shooter.y, bulletSpeed, speedTwoAgo, battleFieldWidth, battleFieldHeight)
            val wave =
                EnemyWave(
                    sourceX = shooter.x,
                    sourceY = shooter.y,
                    fireTime = tracker.lastScanTime,
                    power = firePower,
                    velocity = bulletSpeed,
                    directAngleDeg = sourceToUs,
                    orbitDirection = lastOrbitDirection,
                    features = features,
                    dangerBins = surfer.bakeDanger(features),
                )
            waves.add(wave)
            shadows.onWave(wave, time)
        }

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
        if (firePower != null) tracker.recordEnemyFire(firePower)

        // Passage bookkeeping: learn fully-passed waves as precise-interval visits.
        waves.sweep(time, x, y).forEach { surfer.learnVisit(it, x, y) }

        // Gun: adaptive lead + fire gate. A real bullet casts shadows on waves in flight.
        val fired = gun.fireControl(tracker, battleFieldWidth, battleFieldHeight)
        if (fired != null) shadows.onFire(fired, time, waves.active)

        // Dynamic adaptation: engagement range responds to the enemy's radial
        // (advancing) velocity — charger → keep distance, kiter → close in.
        // The firepower floor is NOT adapted (it caused damage regressions vs
        // weak-gun chargers like RaikoNano/BlestPain that approach but shouldn't
        // be met with weaker bullets).
        smoothAdvancing = smoothAdvancing * 0.9 + tracker.advancingVelocity * 0.1
        Distancing.targetRange = (Distancing.BASE_TARGET + RANGE_GAIN * smoothAdvancing).coerceIn(RANGE_LO, RANGE_HI)

        val selfPose = Kinematics.Pose(x, y, heading, velocity)
        surfer.surf(time, selfPose, tracker.enemy, waves.active, motion, battleFieldWidth, battleFieldHeight)

        // Speed history for the next wave's accel feature.
        speedTwoAgo = speedOneAgo
        speedOneAgo = abs(velocity)
        velocityOneAgo = velocity
    }

    override fun onBulletHit(event: BulletHitEvent) {
        fireDetector.ourBulletHitEnemy(event.bullet.power)
        gun.recordBulletHit(event.bullet.power)
        shadows.onBulletDead(event.bullet, time)
    }

    override fun onHitByBullet(event: HitByBulletEvent) {
        val hitWave = waves.matchBullet(time, event.bullet.x, event.bullet.y, event.bullet.velocity)
        if (hitWave != null) surfer.learnHit(hitWave, event.bullet.x, event.bullet.y)
        damageThisRound += Rules.getBulletDamage(event.bullet.power)
        fireDetector.enemyBulletHitUs(event.power)
    }

    override fun onBulletMissed(event: robocode.BulletMissedEvent) {
        gun.recordBulletMiss(event.bullet.power)
        shadows.onBulletGone(event.bullet)
    }

    override fun onBulletHitBullet(event: BulletHitBulletEvent) {
        // Our bullet destroyed an enemy bullet: that wave can't hit us — drop it
        // so it isn't mislearned as a passing visit. Our bullet died too.
        waves.matchBullet(time, event.hitBullet.x, event.hitBullet.y, event.hitBullet.velocity)
        gun.recordBulletHitBullet(event.bullet.power)
        shadows.onBulletDead(event.bullet, time)
    }

    override fun onRoundEnded(event: RoundEndedEvent) {
        movementSelector.recordDamage(damageThisRound)
        if (enemyName.isNotEmpty()) {
            perEnemyParams.getOrPut(enemyName) { doubleArrayOf(Distancing.BASE_TARGET, DC_POWER_FLOOR_BASE) }.also {
                it[0] = Distancing.targetRange
                it[1] = gun.dcPowerFloor
            }
        }
    }

    private fun adoptEnemy(name: String) {
        enemyName = name
        surfer.adoptEnemy(name)
        movementSelector = MovementProfileSelector.forEnemy(name)
        surfer.profile = movementSelector.profileForRound()
        gun.adoptEnemy(name)
        val params = perEnemyParams.getOrPut(name) { doubleArrayOf(Distancing.BASE_TARGET, DC_POWER_FLOOR_BASE) }
        Distancing.targetRange = params[0]
        gun.dcPowerFloor = params[1]
    }

    private companion object {
        const val REACQUIRE_TICKS = 3L
        const val RANGE_GAIN = 8.0
        const val RANGE_LO = 380.0
        const val RANGE_HI = 540.0
        const val DC_POWER_FLOOR_BASE = 1.2

        /** Per-opponent engagement params [targetRange, dcPowerFloor]. Survives the
         *  per-round robot rebuild (statics), so subsequent rounds in the same
         *  battle can reuse the current values directly. */
        private val perEnemyParams = HashMap<String, DoubleArray>()
    }
}
