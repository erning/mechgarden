package zen.mirage

import robocode.Rules
import kotlin.math.ceil

/**
 * Infer enemy shots from energy. We can't see enemy bullets, but firing costs
 * energy. We keep an energy account isolating the fire-only drop:
 *   fire ≈ (prevEnergy − energy) − damageWeDealt + energyEnemyGained
 * A drop in the legal power range that respects the gun-heat cooldown is a shot.
 *
 *  - Our bullet hit the enemy → the enemy lost energy from being hit, not firing:
 *    subtract the damage we dealt.
 *  - The enemy's bullet hit us → the enemy gained [Rules.getBulletHitBonus]:
 *    add it back so it does not look like a smaller drop (a non-shot).
 * Ported to Mirage from the established workspace fire-detection pattern.
 */
class FireDetector(
    private val coolingRate: Double,
) {
    private var prevEnergy = Double.NaN
    private var damageDealtPending = 0.0
    private var energyGainedPending = 0.0
    private var nextFireAllowedTime = Long.MIN_VALUE

    fun ourBulletHitEnemy(power: Double) {
        damageDealtPending += Rules.getBulletDamage(power)
    }

    fun enemyBulletHitUs(power: Double) {
        energyGainedPending += Rules.getBulletHitBonus(power)
    }

    fun ticksUntilFireAllowed(time: Long): Long = (nextFireAllowedTime - time).coerceAtLeast(0L)

    /** Returns the inferred fire power this scan, or null if the energy drop is not
     *  a legal, cooldown-respecting shot. */
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
