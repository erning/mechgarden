package zen.proteus.aim

import robocode.Rules
import zen.proteus.state.BotState

/**
 * Bullet-power guards around the default distance rule. Only the emergent
 * cases where the rule is clearly wrong get overridden:
 *
 *  - kill shot: the cheapest power that still ends a nearly-dead enemy;
 *  - disable guard: minimum power when we are nearly drained.
 *
 * Two bigger ideas were tried and dropped with measurements: a full
 * expected-score power model (lost offense to the plain distance rule) and a
 * low-energy conservation branch (unreachable once kill power preempts it).
 * See bots/proteus/docs/roadmap.md M6 notes.
 */
internal class FirePower {
    /**
     * The guarded power, [HOLD_FIRE] when holding is worth more than shooting,
     * or null when the default distance rule applies.
     */
    fun select(
        self: BotState,
        enemy: BotState,
    ): Double? {
        if (enemy.energy <= Rules.getBulletDamage(Rules.MAX_BULLET_POWER)) {
            return (enemy.energy / 4.0).coerceIn(Rules.MIN_BULLET_POWER, Rules.MAX_BULLET_POWER)
        }
        if (self.energy < DISABLE_GUARD) {
            return Rules.MIN_BULLET_POWER
        }
        return null
    }

    companion object {
        const val HOLD_FIRE = 0.0
        const val DISABLE_GUARD = 1.0
    }
}
