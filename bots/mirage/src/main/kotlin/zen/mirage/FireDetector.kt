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
    private var prevOurEnergy = Double.NaN
    private var damageDealtPending = 0.0
    private var energyGainedPending = 0.0
    private var ourFirePending = 0.0
    private var nextFireAllowedTime = Long.MIN_VALUE

    fun ourBulletHitEnemy(power: Double) {
        damageDealtPending += Rules.getBulletDamage(power)
    }

    fun enemyBulletHitUs(power: Double) {
        energyGainedPending += Rules.getBulletHitBonus(power)
    }

    /** Energy we spent firing this tick (booked when the gun fires, consumed by
     *  the next detect). Lets detect() strip our own fire cost from our energy
     *  delta when looking for the inactivity-zap signature. */
    fun ourFire(power: Double) {
        ourFirePending += power
    }

    fun ticksUntilFireAllowed(time: Long): Long = (nextFireAllowedTime - time).coerceAtLeast(0L)

    /** Returns the inferred fire power this scan, or null if the energy drop is
     *  not a legal, cooldown-respecting shot. [ourEnergy] is our own energy at
     *  scan time, used to reject inactivity-zap drops (see [isZapSignature]). */
    fun detect(
        time: Long,
        enemyEnergy: Double,
        ourEnergy: Double,
    ): Double? {
        if (prevEnergy.isNaN()) {
            prevEnergy = enemyEnergy
            prevOurEnergy = ourEnergy
            return null
        }
        val netLoss = prevEnergy - enemyEnergy
        val firePower = netLoss - damageDealtPending + energyGainedPending
        // Our unexplained energy change this tick: total drop minus what we spent
        // firing. Bullet-hit gains/losses are not booked here: a bullet hit
        // cannot co-occur with a zap (any hit resets the inactivity timer), and
        // in normal play they leave this residual well outside the zap window.
        val ourResidual = prevOurEnergy - ourEnergy - ourFirePending
        damageDealtPending = 0.0
        energyGainedPending = 0.0
        ourFirePending = 0.0
        prevEnergy = enemyEnergy
        prevOurEnergy = ourEnergy

        val legal = firePower >= Rules.MIN_BULLET_POWER - SLACK && firePower <= Rules.MAX_BULLET_POWER + SLACK
        if (legal && time >= nextFireAllowedTime) {
            if (isZapSignature(firePower, ourResidual)) {
                return null
            }
            val power = firePower.coerceIn(Rules.MIN_BULLET_POWER, Rules.MAX_BULLET_POWER)
            val cooldown = ceil((1.0 + power / 5.0) / coolingRate).toLong()
            nextFireAllowedTime = time + cooldown
            return power
        }
        return null
    }

    /** Inactivity zap drains both robots by exactly 0.1/tick with no bullet hits
     *  on either side. A ~0.1 enemy drop that coincides with a ~0.1 unexplained
     *  drop in our own energy is therefore the engine's zap, not a real
     *  power-0.1 shot (which would not move our energy). Suppressing it stops the
     *  phantom waves that would otherwise block endgame close and pollute surf
     *  learning during stalemates. */
    private fun isZapSignature(
        firePower: Double,
        ourResidual: Double,
    ): Boolean =
        firePower >= ZAP_POWER_LO &&
            firePower <= ZAP_POWER_HI &&
            ourResidual >= ZAP_RESIDUAL_LO &&
            ourResidual <= ZAP_RESIDUAL_HI

    private companion object {
        const val SLACK = 0.01
        const val ZAP_POWER_LO = 0.09
        const val ZAP_POWER_HI = 0.11
        const val ZAP_RESIDUAL_LO = 0.06
        const val ZAP_RESIDUAL_HI = 0.14
    }
}
