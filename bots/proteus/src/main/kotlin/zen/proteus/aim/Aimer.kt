package zen.proteus.aim

import robocode.AdvancedRobot
import robocode.Rules
import zen.proteus.control.Controls
import zen.proteus.core.Angles
import zen.proteus.core.Battlefield
import zen.proteus.state.BotState
import zen.proteus.state.HitRate
import zen.proteus.wave.AimWaves
import zen.proteus.wave.Wave
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.sign
import kotlin.math.sin

/**
 * Targeting. M2: a guess-factor gun over our own wave machinery. Every tick
 * spawns a virtual aim wave (plus a real one when we fire); waves that pass the
 * enemy teach their movement profile ([GfProfile]), and we fire at its smoothed
 * density peak — the same profile a surfer dodges, so this keeps working where
 * linear prediction fails. Real hits add a point sample. The profile is kept
 * per enemy in a static registry so it survives round rebuilds.
 *
 * M4 replaces the bin lookup with KNN guns (features + neighbor density) and
 * adds the main/anti-surfer switch gated on [hitRate]; the wave plumbing stays.
 */
internal class Aimer(
    private val robot: AdvancedRobot,
) {
    /** Our realized hit rate against the current enemy; later drives gun switching. */
    val hitRate = HitRate()

    private val aimWaves = AimWaves()
    private var profile: GfProfile? = null
    private var enemyLateralDirection = 1.0

    fun onRoundStart() {
        aimWaves.clear()
        enemyLateralDirection = 1.0
        // profile intentionally survives: it belongs to the per-enemy registry.
    }

    fun onBulletHit(
        x: Double,
        y: Double,
        power: Double,
        time: Long,
    ) {
        hitRate.record(true)
        val wave = aimWaves.markHit(x, y, power, time) ?: return
        val hitAngleRadians = Angles.absoluteBearingRadians(wave.originX, wave.originY, x, y)
        profile?.recordPoint(wave.guessFactor(hitAngleRadians))
    }

    fun onBulletMissed() {
        // The wave flies on virtually and still samples the enemy's position.
        hitRate.record(false)
    }

    fun aim(
        self: BotState,
        enemy: BotState,
        enemyName: String?,
        field: Battlefield,
        controls: Controls,
    ) {
        if (profile == null && enemyName != null) {
            profile = PROFILE_REGISTRY.getOrPut(enemyName) { GfProfile() }
        }
        val currentProfile = profile
        val time = robot.time
        for (wave in aimWaves.update(enemy.x, enemy.y, time)) {
            currentProfile?.record(wave.visitGfLo, wave.visitGfHi)
        }

        val distance = self.distanceTo(enemy)
        val power = selectPower(self, distance, enemy)
        val directAngleRadians = Angles.absoluteBearingRadians(self.x, self.y, enemy.x, enemy.y)
        val lateralVelocity = enemy.velocity * sin(enemy.headingRadians - directAngleRadians)
        if (abs(lateralVelocity) > LATERAL_EPSILON) {
            enemyLateralDirection = sign(lateralVelocity)
        }
        val maxEscapeAngleRadians = asin(Rules.MAX_VELOCITY / Rules.getBulletSpeed(power))
        val aimGf = currentProfile?.bestGuessFactor() ?: 0.0
        val aimRadians = directAngleRadians + aimGf * maxEscapeAngleRadians * enemyLateralDirection

        val gunTurnRadians = Angles.normalizeRelative(aimRadians - robot.gunHeadingRadians)
        controls.gunTurnRadians = gunTurnRadians

        // Fire only when the gun is aligned well enough that the shot can connect:
        // the enemy robot square subtends atan(18 / distance) to each side.
        val aligned = abs(gunTurnRadians) <= atan2(Battlefield.ROBOT_HALF_SIZE, distance)
        val fired = robot.gunHeat == 0.0 && aligned && self.energy > power + ENERGY_RESERVE
        if (fired) {
            controls.firePower = power
        }
        // This tick's outgoing wave: real if we fired, virtual otherwise. The
        // bullet spawns next turn at our current position, hence fireTime + 1.
        aimWaves.add(
            Wave(
                self.x,
                self.y,
                power,
                time + 1,
                directAngleRadians,
                enemyLateralDirection,
                fired,
            ),
        )
    }

    private fun selectPower(
        self: BotState,
        distance: Double,
        enemy: BotState,
    ): Double {
        var power = (POWER_DISTANCE_SCALE / distance).coerceIn(Rules.MIN_BULLET_POWER, Rules.MAX_BULLET_POWER)
        if (self.energy < LOW_ENERGY) power = Rules.MIN_BULLET_POWER
        // Kill shot: no point spending more energy than the damage that ends it.
        if (enemy.energy <= Rules.getBulletDamage(power)) {
            power = (enemy.energy / 4.0).coerceIn(Rules.MIN_BULLET_POWER, Rules.MAX_BULLET_POWER)
        }
        return power
    }

    private companion object {
        const val POWER_DISTANCE_SCALE = 800.0
        const val LOW_ENERGY = 2.0
        const val ENERGY_RESERVE = 0.05
        const val LATERAL_EPSILON = 1e-3

        /** Per-enemy movement profiles; survive Robocode's per-round robot rebuild. */
        val PROFILE_REGISTRY = HashMap<String, GfProfile>()
    }
}
