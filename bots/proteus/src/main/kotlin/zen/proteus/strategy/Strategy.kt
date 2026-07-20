package zen.proteus.strategy

import zen.proteus.core.Angles
import zen.proteus.core.Battlefield
import zen.proteus.state.BotState
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max

/**
 * Opponent classification: online, uncertainty-aware flags, no commands and no
 * names needed. Everything here recomputes per tick and downstream layers read
 * the flags:
 *
 *  - antiHot: assume a head-on gun until a bullet that was not aimed straight
 *    hits us (less tolerance early, when mistakes cost most); the estimator
 *    doubles the HOT model's weight while it holds.
 *  - antiRam: sustained closing speed while nearby, or an immediate
 *    closing-speed/distance tripwire; movement switches to fleeing instead of
 *    surfing (see Mover).
 *  - antiMirror: the enemy hovers near our center-mirror point; movement
 *    switches to the looser walking-stick smoothing so their prediction of us
 *    stays simple and dodgeable.
 */
internal class Strategy {
    var antiHot = true
        private set
    var antiRam = false
        private set
    var antiMirror = false
        private set
    var tryToDisable = false
        private set
    var ram = false
        private set

    private var nonHotHits = 0
    private var ramEma = 0.0
    private var mirrorEma = Double.POSITIVE_INFINITY

    fun onRoundStart(roundNum: Int) {
        if (roundNum == 0) {
            antiHot = true
            nonHotHits = 0
        }
        antiRam = false
        antiMirror = false
    }

    /** A bullet hit us at [guessFactor] on its wave; HOT guns aim at GF 0. */
    fun noteHitOnUs(
        guessFactor: Double,
        roundNum: Int,
    ) {
        if (abs(guessFactor) <= HOT_GF_TOLERANCE) return
        nonHotHits++
        if (nonHotHits > if (roundNum < EARLY_ROUNDS) 0 else 1) {
            antiHot = false
        }
    }

    fun strategize(
        self: BotState,
        enemy: BotState,
        field: Battlefield,
        noIncomingWaves: Boolean,
        ticksSinceEnemyShot: Long,
    ) {
        val distance = self.distanceTo(enemy)

        // Endgame harvest: the enemy is beaten but alive. While it cannot
        // hurt us, leave it ~0.011 energy (a ram kill pays better than a
        // bullet kill); once it is nearly drained, finish by ramming.
        tryToDisable =
            self.energy > DISABLE_MIN_SELF_ENERGY &&
            enemy.energy < DISABLE_MAX_ENEMY_ENERGY &&
            noIncomingWaves &&
            ticksSinceEnemyShot > DISABLE_IDLE_TICKS
        ram = enemy.energy < RAM_ENEMY_ENERGY

        // Ram: closing speed component toward us, sustained by EMA; the
        // tripwire catches rammers that are already on top of us.
        val bearingToUsRadians = Angles.absoluteBearingRadians(enemy.x, enemy.y, self.x, self.y)
        val closing = enemy.velocity * cos(enemy.headingRadians - bearingToUsRadians)
        val ramSignal = if (distance < RAM_SCAN_RANGE) max(0.0, closing) else 0.0
        ramEma = ramEma * RAM_EMA_DECAY + ramSignal * (1.0 - RAM_EMA_DECAY)
        antiRam =
            ramEma > RAM_EMA_THRESHOLD ||
            (ramEma > RAM_EMA_FLOOR && distance < (closing + 8.0) * RAM_TRIPWIRE_SCALE)

        // Mirror: distance between the enemy and our center-mirror point.
        val mirrorDistance = hypot(enemy.x - (field.width - self.x), enemy.y - (field.height - self.y))
        mirrorEma =
            if (mirrorEma == Double.POSITIVE_INFINITY) {
                mirrorDistance
            } else {
                mirrorEma * MIRROR_EMA_DECAY + mirrorDistance * (1.0 - MIRROR_EMA_DECAY)
            }
        antiMirror = mirrorEma < MIRROR_DISTANCE
    }

    private companion object {
        const val HOT_GF_TOLERANCE = 0.15
        const val EARLY_ROUNDS = 5

        const val RAM_SCAN_RANGE = 300.0
        const val RAM_EMA_DECAY = 0.998
        const val RAM_EMA_THRESHOLD = 5.0
        const val RAM_EMA_FLOOR = 2.0
        const val RAM_TRIPWIRE_SCALE = 15.0

        const val MIRROR_EMA_DECAY = 0.99
        const val MIRROR_DISTANCE = 90.0

        const val DISABLE_MIN_SELF_ENERGY = 3.0
        const val DISABLE_MAX_ENEMY_ENERGY = 95.0
        const val DISABLE_IDLE_TICKS = 60
        const val RAM_ENEMY_ENERGY = 0.03
    }
}
