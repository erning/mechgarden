package zen.proteus.move.danger

import zen.proteus.state.BotState
import zen.proteus.wave.Wave

/**
 * An enemy wave plus everything the danger models need: the feature vector
 * captured at creation (our movement as the enemy saw it) and our state at fire
 * time, which the simulated guns lead from.
 */
internal class EnemyWave(
    val wave: Wave,
    val features: DoubleArray,
    val fireSelf: BotState,
    val fireSelfPrev: BotState?,
)
