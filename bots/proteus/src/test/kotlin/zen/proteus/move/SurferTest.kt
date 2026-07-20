package zen.proteus.move

import zen.proteus.aim.Features
import zen.proteus.core.Battlefield
import zen.proteus.move.danger.DangerEstimator
import zen.proteus.move.danger.EnemyWave
import zen.proteus.state.BotState
import zen.proteus.wave.Wave
import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class SurferTest {
    private val field = Battlefield(800.0, 600.0)
    private val surfer = Surfer(field)

    // Self at (400, 250) already orbiting (heading east); enemy due north at
    // (400, 550), 300px away so no distance bias applies.
    private val self =
        BotState(100, 400.0, 250.0, PI / 2.0, 8.0, 80.0, 0.0)
    private val enemy =
        BotState(100, 400.0, 550.0, PI, 8.0, 80.0, 0.0)

    private val entry =
        EnemyWave(
            Wave(400.0, 550.0, 3.0, 95, PI, 1.0),
            DoubleArray(Features.COUNT),
            self,
            null,
        )

    private fun estimatorHotAt(gf: Double): DangerEstimator {
        val estimator = DangerEstimator()
        repeat(8) { estimator.onWaveResolved(entry, gf, true) }
        return estimator
    }

    @Test
    fun `surfer moves away from the GF the enemy keeps hitting`() {
        // FORWARD continues east, which covers negative GF. When the enemy
        // keeps hitting +0.8 the surfer must not flip west into the heat.
        val choice = surfer.choose(self, enemy, listOf(entry), estimatorHotAt(0.8), emptyMap(), 1.0, 100)
        assertNotEquals(Surfer.Option.BACKWARD, choice.option)
    }

    @Test
    fun `surfer flips when the enemy hits the other side`() {
        val choice = surfer.choose(self, enemy, listOf(entry), estimatorHotAt(-0.8), emptyMap(), 1.0, 100)
        assertNotEquals(Surfer.Option.FORWARD, choice.option)
    }

    @Test
    fun `empty danger keeps the current direction`() {
        val choice = surfer.choose(self, enemy, listOf(entry), DangerEstimator(), emptyMap(), 1.0, 100)
        assertEquals(Surfer.Option.FORWARD, choice.option)
    }
}
