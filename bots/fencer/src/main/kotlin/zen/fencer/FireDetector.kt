package zen.fencer

import robocode.Rules
import kotlin.math.ceil

/**
 * Infers enemy shots from energy — we can't see
 * enemy bullets, but firing costs energy. A naive "energy dropped → it fired"
 * is wrong: the enemy also loses energy to our hits and gains it from hitting
 * us. So we keep an **energy account** and isolate the fire-only drop:
 *
 *   fire ≈ (prevEnergy − energy) − damageWeDealt + energyEnemyGained
 *
 * (wall/ram losses aren't observable for the enemy, so they're assumed 0 — a
 * small residual the power-range filter and the gun-heat constraint absorb.)
 *
 * A drop in the legal power range that also respects the **gun-heat cooldown**
 * (`heat = 1 + power/5`, cooling at the battle's rate) is reported as a shot.
 */
class FireDetector(
    private val coolingRate: Double,
) {
    private var prevEnergy = Double.NaN
    private var damageDealtPending = 0.0
    private var energyGainedPending = 0.0
    private var nextFireAllowedTime = Long.MIN_VALUE

    /** Our bullet hit the enemy: it lost this much (don't blame it on a shot). */
    fun ourBulletHitEnemy(power: Double) {
        damageDealtPending += Rules.getBulletDamage(power)
    }

    /** The enemy's bullet hit us: it gained 3×power (recover the masked drop). */
    fun enemyBulletHitUs(power: Double) {
        energyGainedPending += Rules.getBulletHitBonus(power)
    }

    /**
     * Feed this scan's enemy energy. Returns the inferred fire power, or null if
     * this scan's energy change isn't a shot. Resets the per-interval account.
     */
    fun detect(
        time: Long,
        enemyEnergy: Double,
    ): Double? {
        if (prevEnergy.isNaN()) {
            prevEnergy = enemyEnergy
            return null
        }
        val netLoss = prevEnergy - enemyEnergy
        val firePower = netLoss - damageDealtPending + energyGainedPending
        damageDealtPending = 0.0
        energyGainedPending = 0.0
        prevEnergy = enemyEnergy

        val legal = firePower >= Rules.MIN_BULLET_POWER - SLACK && firePower <= Rules.MAX_BULLET_POWER + SLACK
        if (legal && time >= nextFireAllowedTime) {
            val power = firePower.coerceIn(Rules.MIN_BULLET_POWER, Rules.MAX_BULLET_POWER)
            val cooldown = ceil((1.0 + power / 5.0) / coolingRate).toLong()
            nextFireAllowedTime = time + cooldown
            return power
        }
        return null
    }

    private companion object {
        const val SLACK = 0.01
    }
}
