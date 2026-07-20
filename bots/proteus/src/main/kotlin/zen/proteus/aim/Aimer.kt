package zen.proteus.aim

import robocode.AdvancedRobot
import robocode.Rules
import zen.proteus.control.Controls
import zen.proteus.core.Angles
import zen.proteus.core.Battlefield
import zen.proteus.diag.Dataset
import zen.proteus.knn.KnnModel
import zen.proteus.move.Mover
import zen.proteus.state.BotState
import zen.proteus.state.HitRate
import zen.proteus.strategy.Strategy
import zen.proteus.wave.AimWaves
import zen.proteus.wave.Wave
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.min
import kotlin.math.sign
import kotlin.math.sin

/**
 * Targeting. M4: two KNN guns trained by every-tick virtual waves plus real
 * ones. Each completed wave trains both guns with its feature vector and the GF
 * interval the enemy covered; real hits add a point sample and get neighboring
 * waves marked didHit. The anti-surfer query excludes didHit samples — enemies
 * dodge away from where they were hit. Gun selection is a hard switch on our
 * hit-rate interval: if [hitRate] possibly falls in [0, 12%] the enemy is
 * dodging us and the anti-surfer gun takes over. Until a tree has enough
 * samples the GF-bin profile ([GfProfile]) covers the cold start.
 *
 * Aiming is statistical: angle = direct bearing + aimGF * MEA * lateral sign.
 */
internal class Aimer(
    private val robot: AdvancedRobot,
    mover: Mover,
) {
    /** Our realized hit rate against the current enemy; drives the gun switch. */
    val hitRate = HitRate()

    private val aimWaves = AimWaves()
    private val firePower = FirePower()
    private val shadowAim = ShadowAim(mover)
    private val mover = mover
    private var profile: GfProfile? = null
    private var ourAvgPower = 2.0
    private var currentEnemyName: String? = null
    private var models: GunModels? = null
    private var enemyLateralDirection = 1.0
    private var lastLatSign = 1.0
    private var dirChangeTicks = 0
    private var lastFireTime = Long.MIN_VALUE
    private val hitTimes = ArrayDeque<Long>()

    fun onRoundStart() {
        aimWaves.clear()
        enemyLateralDirection = 1.0
        lastLatSign = 1.0
        dirChangeTicks = 0
        // profile and models intentionally survive: per-enemy static registries.
    }

    fun onBulletHit(
        x: Double,
        y: Double,
        power: Double,
        time: Long,
    ) {
        hitRate.record(true)
        hitTimes.addLast(time)
        while (hitTimes.size > HIT_TIME_WINDOW) hitTimes.removeFirst()
        val entry = aimWaves.markHit(x, y, power, time) ?: return
        val wave = entry.wave
        val hitAngleRadians = Angles.absoluteBearingRadians(wave.originX, wave.originY, x, y)
        val hitGf = wave.guessFactor(hitAngleRadians)
        profile?.recordPoint(hitGf)
        currentEnemyName?.let { name ->
            Dataset.write(name, entry.features, hitGf - POINT_HALF_WIDTH, hitGf + POINT_HALF_WIDTH)
        }
        val didHit = wasRecentHit(time)
        val pointEntry = KnnModel.Entry(entry.features, hitGf - POINT_HALF_WIDTH, hitGf + POINT_HALF_WIDTH, didHit)
        models?.main?.add(pointEntry)
        models?.antiSurfer?.add(pointEntry)
    }

    fun onBulletMissed() {
        // The wave flies on virtually and still samples the enemy's position.
        hitRate.record(false)
    }

    fun aim(
        self: BotState,
        enemy: BotState,
        enemyPrev: BotState?,
        enemyName: String?,
        strategy: Strategy,
        enemyHitRate: HitRate?,
        enemyAvgPower: Double,
        field: Battlefield,
        controls: Controls,
    ) {
        if (profile == null && enemyName != null) {
            profile = PROFILE_REGISTRY.getOrPut(enemyName) { GfProfile() }
            models =
                MODEL_REGISTRY.getOrPut(enemyName) {
                    GunModels(KnnModel(TREE_CAPACITY, Features.WEIGHTS), KnnModel(TREE_CAPACITY, Features.WEIGHTS))
                }
        }
        val currentProfile = profile
        val currentModels = models
        val time = robot.time

        for (entry in aimWaves.update(enemy.x, enemy.y, time)) {
            val wave = entry.wave
            currentProfile?.record(wave.visitGfLo, wave.visitGfHi)
            if (enemyName != null) {
                Dataset.write(enemyName, entry.features, wave.visitGfLo, wave.visitGfHi)
                currentEnemyName = enemyName
            }
            val didHit = wasRecentHit(time)
            val sample = KnnModel.Entry(entry.features, wave.visitGfLo, wave.visitGfHi, didHit)
            currentModels?.main?.add(sample)
            currentModels?.antiSurfer?.add(sample)
        }

        val distance = self.distanceTo(enemy)
        val guarded = firePower.select(self, enemy)
        val power =
            when {
                strategy.tryToDisable && enemy.energy <= Rules.getBulletDamage(Rules.MAX_BULLET_POWER) ->
                    ((enemy.energy - DISABLE_REMAINDER) / 4.0).coerceIn(Rules.MIN_BULLET_POWER, Rules.MAX_BULLET_POWER)
                guarded == FirePower.HOLD_FIRE -> Rules.MIN_BULLET_POWER
                guarded != null -> guarded
                else -> (POWER_DISTANCE_SCALE / distance).coerceIn(Rules.MIN_BULLET_POWER, Rules.MAX_BULLET_POWER)
            }
        // Ramming pays better than a bullet kill; stop shooting entirely.
        val holdFire =
            strategy.ram ||
                guarded == FirePower.HOLD_FIRE ||
                (strategy.tryToDisable && enemy.energy < DISABLE_SHOT_MIN_ENERGY)
        val directAngleRadians = Angles.absoluteBearingRadians(self.x, self.y, enemy.x, enemy.y)
        val lateralVelocity = enemy.velocity * sin(enemy.headingRadians - directAngleRadians)
        if (abs(lateralVelocity) > LATERAL_EPSILON) {
            enemyLateralDirection = sign(lateralVelocity)
            val latSign = sign(lateralVelocity)
            if (latSign != lastLatSign) {
                lastLatSign = latSign
                dirChangeTicks = 0
            }
        }
        dirChangeTicks++

        val pick = pickGun(self, enemy, enemyPrev, power, directAngleRadians, field, time)
        val maxEscapeAngleRadians = asin(Rules.MAX_VELOCITY / Rules.getBulletSpeed(power))
        var aimGf = pick.gf
        if (pick.model != null && robot.gunHeat <= PRE_FIRE_TICKS * robot.gunCoolingRate) {
            aimGf =
                shadowAim.pickGuessFactor(
                    self,
                    power,
                    pick.gf,
                    directAngleRadians,
                    maxEscapeAngleRadians,
                    enemyLateralDirection,
                    pick.neighbors,
                    pick.model,
                    mover.surfWaves(self, time),
                    ourHitEstimate(),
                    theirHitEstimate(enemyHitRate),
                    ourAvgPower,
                    enemyAvgPower,
                    time,
                )
        }
        val aimRadians = directAngleRadians + aimGf * maxEscapeAngleRadians * enemyLateralDirection
        val gunTurnRadians = Angles.normalizeRelative(aimRadians - robot.gunHeadingRadians)
        controls.gunTurnRadians = gunTurnRadians

        // Fire only when the gun is aligned well enough that the shot can connect:
        // the enemy robot square subtends atan(18 / distance) to each side.
        val aligned = abs(gunTurnRadians) <= atan2(Battlefield.ROBOT_HALF_SIZE, distance)
        val fired = robot.gunHeat == 0.0 && aligned && !holdFire && self.energy > power + ENERGY_RESERVE
        if (fired) {
            controls.firePower = power
            lastFireTime = time
            ourAvgPower = ourAvgPower * OUR_POWER_DECAY + power * (1.0 - OUR_POWER_DECAY)
        }

        // This tick's outgoing wave: real if we fired, virtual otherwise. The
        // bullet spawns next turn at our current position, hence fireTime + 1.
        val wave = Wave(self.x, self.y, power, time + 1, directAngleRadians, enemyLateralDirection, fired)
        val features =
            Features.compute(self, enemy, enemyPrev, wave, field, virtuality(fired, time), dirChangeTicks)
        aimWaves.add(AimWaves.Entry(wave, features))
    }

    private class GunPick(
        val gf: Double,
        val neighbors: List<KnnModel.Neighbor>,
        val model: KnnModel?,
    )

    private fun pickGun(
        self: BotState,
        enemy: BotState,
        enemyPrev: BotState?,
        power: Double,
        directAngleRadians: Double,
        field: Battlefield,
        time: Long,
    ): GunPick {
        val currentModels = models ?: return GunPick(profile?.bestGuessFactor() ?: 0.0, emptyList(), null)
        val queryWave = Wave(self.x, self.y, power, time + 1, directAngleRadians, enemyLateralDirection, false)
        val queryFeatures =
            Features.compute(self, enemy, enemyPrev, queryWave, field, virtuality(false, time), dirChangeTicks)
        val antiSurfer = hitRate.overlaps(0.0, ANTI_SURFER_MAX_HIT_RATE)
        val model = if (antiSurfer) currentModels.antiSurfer else currentModels.main
        return if (model.size >= MIN_KNN_DATA) {
            val neighbors = model.nearest(queryFeatures, includeDidHit = !antiSurfer)
            GunPick(model.peakOf(neighbors), neighbors, model)
        } else {
            GunPick(profile?.bestGuessFactor() ?: 0.0, emptyList(), null)
        }
    }

    private fun ourHitEstimate(): Double = (hitRate.hits + 1.0) / (hitRate.shots + 12.0)

    private fun theirHitEstimate(enemyHitRate: HitRate?): Double {
        if (enemyHitRate == null || enemyHitRate.shots == 0) return 0.0
        return kotlin.math.min(0.15, (enemyHitRate.hits + 1.0) / (enemyHitRate.shots + 2.0))
    }

    private fun virtuality(
        fired: Boolean,
        time: Long,
    ): Int {
        if (fired) return 0
        val sinceFire = if (lastFireTime < 0) Int.MAX_VALUE else (time - lastFireTime).toInt()
        val heatTicks = (robot.gunHeat / robot.gunCoolingRate).toInt()
        return min(sinceFire, heatTicks)
    }

    private fun wasRecentHit(time: Long): Boolean = hitTimes.any { abs(it - time) <= DID_HIT_WINDOW }

    private class GunModels(
        val main: KnnModel,
        val antiSurfer: KnnModel,
    )

    private companion object {
        const val POWER_DISTANCE_SCALE = 800.0
        const val ENERGY_RESERVE = 0.05
        const val LATERAL_EPSILON = 1e-3
        const val ANTI_SURFER_MAX_HIT_RATE = 0.12
        const val MIN_KNN_DATA = 30
        const val TREE_CAPACITY = 15000
        const val POINT_HALF_WIDTH = 0.05
        const val DID_HIT_WINDOW = 3
        const val HIT_TIME_WINDOW = 50
        const val PRE_FIRE_TICKS = 2.0
        const val OUR_POWER_DECAY = 0.9
        const val DISABLE_REMAINDER = 0.011
        const val DISABLE_SHOT_MIN_ENERGY = 0.5

        /** Per-enemy movement profiles and KNN trees; survive round rebuilds. */
        val PROFILE_REGISTRY = HashMap<String, GfProfile>()
        val MODEL_REGISTRY = HashMap<String, GunModels>()
    }
}
