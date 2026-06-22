package zen.mirage

import robocode.Bullet
import robocode.Rules

/**
 * Anti-shield edge-aim selector.
 *
 * Bullet-shielding opponents sit still and fire cheap bullets along the center
 * line to collide with ours (a [robocode.BulletHitBulletEvent]). Center aim then
 * becomes easy to intercept, so under a shield signal we aim at either body edge
 * instead: an edge shot's path misses the center-line intercept bullet, so it
 * reaches the robot's hull. The selector learns only from real edge shots and
 * stays per-enemy without hardcoding opponent names.
 *
 * Exploration: each edge profile fires a few shots before any is re-used, so both
 * edges get sampled. Exploitation: pick the edge with the better reward-per-shot.
 * CENTER is returned whenever the enemy does not look like a shielder, so this is
 * a no-op against normal movers and never regresses them.
 *
 * Reward model: a hit scores the damage dealt minus the power spent (net energy
 * gain for us); a miss or an intercept scores -power (the wasted shot); an
 * intercept is penalized extra ([HIT_BULLET_PENALTY]) since it is the direct
 * signal that we aimed into the shield. Lives in a per-enemy static registry so
 * learning accumulates across a battle's rounds.
 */
class ShieldAimSelector {
    /** Edge-aim profile: which side of the enemy hull to aim at (0 = center). */
    enum class Profile(
        val edgeSign: Int,
    ) {
        CENTER(0),
        EDGE_LEFT(-1),
        EDGE_RIGHT(+1),
    }

    private val rewards = PerShotRewards(PROFILES.size, PRIOR_WEIGHT, PRIOR_REWARD)
    private var shieldSelections = 0L

    fun beginRound() {
        rewards.beginRound()
    }

    /** Pick this shot's profile: CENTER unless the enemy looks like a shielder;
     *  otherwise explore an under-tried edge or exploit the better edge. */
    fun select(shieldLikely: Boolean): Profile {
        if (!shieldLikely) return Profile.CENTER
        return explorationProfile() ?: bestEdgeProfile()
    }

    fun onFire(
        profile: Profile,
        bullet: Bullet,
    ) {
        if (profile == Profile.CENTER) return
        rewards.onFire(profile.ordinal, bullet)
        shieldSelections++
    }

    fun recordHit(bullet: Bullet) {
        rewards.complete(bullet, Rules.getBulletDamage(bullet.power) - bullet.power)
    }

    fun recordMiss(bullet: Bullet) {
        rewards.complete(bullet, -bullet.power)
    }

    fun recordHitBullet(bullet: Bullet) {
        rewards.complete(bullet, -bullet.power * HIT_BULLET_PENALTY)
    }

    private fun explorationProfile(): Profile? {
        val start = (shieldSelections % EDGE_PROFILES.size).toInt()
        for (offset in EDGE_PROFILES.indices) {
            val profile = EDGE_PROFILES[(start + offset) % EDGE_PROFILES.size]
            if (rewards.shotCount(profile.ordinal) < EXPLORE_SHOTS) return profile
        }
        return null
    }

    private fun bestEdgeProfile(): Profile {
        var best = EDGE_PROFILES[0]
        var bestReward = rewards.rewardPerShot(best.ordinal)
        for (profile in EDGE_PROFILES.drop(1)) {
            val candidate = rewards.rewardPerShot(profile.ordinal)
            if (candidate > bestReward) {
                best = profile
                bestReward = candidate
            }
        }
        return best
    }

    companion object {
        const val EXPLORE_SHOTS = 3L
        private const val PRIOR_REWARD = 0.0
        private const val PRIOR_WEIGHT = 2.0
        private const val HIT_BULLET_PENALTY = 1.6

        /** Cached arrays — avoid per-call enum cloning. */
        private val EDGE_PROFILES = arrayOf(Profile.EDGE_LEFT, Profile.EDGE_RIGHT)
        private val PROFILES = Profile.values()

        private val perEnemy = HashMap<String, ShieldAimSelector>()

        fun forEnemy(name: String): ShieldAimSelector = perEnemy.getOrPut(name) { ShieldAimSelector() }
    }
}
