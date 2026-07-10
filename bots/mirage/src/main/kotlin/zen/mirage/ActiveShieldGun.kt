package zen.mirage

import robocode.AdvancedRobot
import robocode.Bullet
import robocode.Rules
import java.util.IdentityHashMap
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A narrow active bullet shield for opponents whose shots stay close to a
 * prior-tick head-on bearing. It predicts the nearest real enemy wave as a
 * moving point and fires the cheapest legal bullet at the analytic intercept.
 *
 * The normal gun still receives every scan with fire held, so its virtual guns
 * and learning models continue to advance while this controller owns the
 * turret. A wave gets one shield attempt; an unreliable intercept therefore
 * costs at most one 0.1-power bullet before control returns to the normal gun.
 */
class ActiveShieldGun(
    private val bot: AdvancedRobot,
) {
    data class Plan(
        val wave: EnemyWave,
        val aimAngleRadians: Double,
        val interceptX: Double,
        val interceptY: Double,
        val interceptTicks: Double,
    )

    private val attemptedWaves = identitySet<EnemyWave>()

    // Bullet.equals() is keyed by the engine's per-owner bullet id. Events carry
    // reconstructed Bullet instances, so ordinary equality is required here.
    private val shieldBullets = HashMap<Bullet, EnemyWave>()
    private var policy = ActiveShieldPolicy()
    private var planTicks = 0
    private var bulletsFired = 0
    private var bulletsIntercepted = 0
    private var bulletsMissed = 0

    fun adoptEnemy(name: String) {
        policy = ActiveShieldPolicy.forEnemy(name)
        policy.beginRound()
    }

    fun adaptiveReady(): Boolean = policy.activeForRound()

    fun recordRound(
        dealt: Double,
        taken: Double,
        survived: Boolean,
        usedActiveShield: Boolean,
    ) {
        policy.recordRound(dealt, taken, survived, usedActiveShield)
    }

    /** Return the earliest usable head-on-wave intercept, if one exists. */
    fun plan(
        now: Long,
        waves: List<EnemyWave>,
        fieldWidth: Double,
        fieldHeight: Double,
    ): Plan? {
        val plan =
            waves
                .asSequence()
                .filter { wave -> attemptedWaves.none { it === wave } }
                .filter { it.radius(now) >= 0.0 }
                .filter { it.sourceDistance(bot.x, bot.y) - it.radius(now) > -Kinematics.HALF_BOT }
                .filter { it.isLikelyThreat(bot.x, bot.y) }
                .sortedBy { it.sourceDistance(bot.x, bot.y) - it.radius(now) }
                .mapNotNull { wave -> interceptPlan(now, wave, fieldWidth, fieldHeight) }
                .firstOrNull()
        if (plan != null) planTicks++
        return plan
    }

    /** Aim at [plan] and fire once the turret is aligned. */
    fun execute(plan: Plan): Bullet? {
        val turnRadians = Angles.normalizeRelative(plan.aimAngleRadians - bot.gunHeadingRadians)
        bot.setTurnGunRightRadians(turnRadians)
        val interceptDistance = hypot(plan.interceptX - bot.x, plan.interceptY - bot.y)
        val toleranceRadians = atan(INTERCEPT_TOLERANCE / interceptDistance.coerceAtLeast(1.0))
        val affordable = bot.energy > SHIELD_POWER + ENERGY_RESERVE
        if (bot.gunHeat > GUN_HEAT_EPSILON || abs(turnRadians) > toleranceRadians || !affordable) return null

        val bullet = bot.setFireBullet(SHIELD_POWER) ?: return null
        attemptedWaves += plan.wave
        shieldBullets[bullet] = plan.wave
        bulletsFired++
        return bullet
    }

    /** Consume a shield bullet that hit the opponent instead of its target. */
    fun recordBulletHit(bullet: Bullet): Boolean = consumeNonIntercept(bullet)

    /** Consume a shield bullet that left the battlefield without intercepting. */
    fun recordBulletMiss(bullet: Bullet): Boolean = consumeNonIntercept(bullet)

    /** Consume and credit a successful enemy-bullet interception. */
    fun recordBulletHitBullet(bullet: Bullet): Boolean {
        if (shieldBullets.remove(bullet) == null) return false
        bulletsIntercepted++
        return true
    }

    fun debugSummary(): String =
        "ashield=plan:$planTicks/fire:$bulletsFired/intercept:$bulletsIntercepted/miss:$bulletsMissed " + policy.debugSummary()

    private fun consumeNonIntercept(bullet: Bullet): Boolean {
        if (shieldBullets.remove(bullet) == null) return false
        bulletsMissed++
        return true
    }

    private fun interceptPlan(
        now: Long,
        wave: EnemyWave,
        fieldWidth: Double,
        fieldHeight: Double,
    ): Plan? {
        val radius = wave.radius(now)
        val enemyVelocityX = sin(wave.shieldHeadingRadians) * wave.velocity
        val enemyVelocityY = cos(wave.shieldHeadingRadians) * wave.velocity
        val enemyBulletX = wave.sourceX + sin(wave.shieldHeadingRadians) * radius
        val enemyBulletY = wave.sourceY + cos(wave.shieldHeadingRadians) * radius
        val intercept =
            ActiveShieldGeometry.intercept(
                shooterX = bot.x,
                shooterY = bot.y,
                targetX = enemyBulletX,
                targetY = enemyBulletY,
                targetVelocityX = enemyVelocityX,
                targetVelocityY = enemyVelocityY,
                projectileSpeed = Rules.getBulletSpeed(SHIELD_POWER),
            ) ?: return null
        if (intercept.ticks > MAX_INTERCEPT_TICKS) return null
        if (intercept.x !in 0.0..fieldWidth || intercept.y !in 0.0..fieldHeight) return null
        return Plan(
            wave = wave,
            aimAngleRadians = Angles.absoluteBearing(bot.x, bot.y, intercept.x, intercept.y),
            interceptX = intercept.x,
            interceptY = intercept.y,
            interceptTicks = intercept.ticks,
        )
    }

    private fun EnemyWave.sourceDistance(
        x: Double,
        y: Double,
    ): Double = hypot(x - sourceX, y - sourceY)

    private fun EnemyWave.isLikelyThreat(
        x: Double,
        y: Double,
    ): Boolean {
        val distance = sourceDistance(x, y)
        if (distance <= Kinematics.HALF_BOT) return true
        val bearingRadians = Angles.absoluteBearing(sourceX, sourceY, x, y)
        val halfBodyRadians = atan(BODY_HALF_DIAGONAL / distance)
        return abs(Angles.normalizeRelative(shieldHeadingRadians - bearingRadians)) <= halfBodyRadians + THREAT_MARGIN_RADIANS
    }

    private companion object {
        const val SHIELD_POWER = Rules.MIN_BULLET_POWER
        const val ENERGY_RESERVE = 0.2
        const val INTERCEPT_TOLERANCE = 3.0
        const val MAX_INTERCEPT_TICKS = 50.0
        const val GUN_HEAT_EPSILON = 1e-9
        const val BODY_HALF_DIAGONAL = 25.6
        const val THREAT_MARGIN_RADIANS = 0.02

        fun <T> identitySet(): MutableSet<T> = java.util.Collections.newSetFromMap(IdentityHashMap())
    }
}

/** Analytic constant-velocity projectile interception. */
object ActiveShieldGeometry {
    data class Intercept(
        val x: Double,
        val y: Double,
        val ticks: Double,
    )

    @Suppress("LongParameterList")
    fun intercept(
        shooterX: Double,
        shooterY: Double,
        targetX: Double,
        targetY: Double,
        targetVelocityX: Double,
        targetVelocityY: Double,
        projectileSpeed: Double,
    ): Intercept? {
        if (projectileSpeed <= 0.0) return null
        val dx = targetX - shooterX
        val dy = targetY - shooterY
        val a = targetVelocityX * targetVelocityX + targetVelocityY * targetVelocityY - projectileSpeed * projectileSpeed
        val b = 2.0 * (dx * targetVelocityX + dy * targetVelocityY)
        val c = dx * dx + dy * dy
        val ticks = positiveInterceptTime(a, b, c) ?: return null
        val x = targetX + targetVelocityX * ticks
        val y = targetY + targetVelocityY * ticks
        if (!x.isFinite() || !y.isFinite()) return null
        return Intercept(x, y, ticks)
    }

    private fun positiveInterceptTime(
        a: Double,
        b: Double,
        c: Double,
    ): Double? {
        if (abs(a) < EPSILON) {
            if (abs(b) < EPSILON) return null
            return (-c / b).takeIf { it > MIN_INTERCEPT_TICKS }
        }
        val discriminant = b * b - 4.0 * a * c
        if (discriminant < 0.0) return null
        val root = sqrt(discriminant)
        return sequenceOf((-b - root) / (2.0 * a), (-b + root) / (2.0 * a))
            .filter { it > MIN_INTERCEPT_TICKS }
            .minOrNull()
    }

    private const val EPSILON = 1e-9
    private const val MIN_INTERCEPT_TICKS = 0.05
}
