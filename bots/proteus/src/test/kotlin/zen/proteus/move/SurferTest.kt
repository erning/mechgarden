package zen.proteus.move

import zen.proteus.core.Battlefield
import zen.proteus.move.danger.EmpiricalDanger
import zen.proteus.state.BotState
import zen.proteus.wave.Wave
import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals

class SurferTest {
    private val field = Battlefield(800.0, 600.0)
    private val surfer = Surfer(field)

    // Self at (400, 250) already orbiting (heading east); enemy due north at
    // (400, 550), 300px away so no distance bias applies.
    private val self =
        BotState(
            time = 100,
            x = 400.0,
            y = 250.0,
            headingRadians = PI / 2.0,
            velocity = 8.0,
            energy = 80.0,
            gunHeat = 0.0,
        )
    private val enemy =
        BotState(time = 100, x = 400.0, y = 550.0, headingRadians = PI, velocity = 8.0, energy = 80.0, gunHeat = 0.0)

    private fun waveAt(
        lateralDirection: Double,
        fireTime: Long = 95,
    ) = Wave(
        originX = enemy.x,
        originY = enemy.y,
        power = 3.0,
        fireTime = fireTime,
        // bearing origin -> self is due south.
        directAngleRadians = PI,
        lateralDirection = lateralDirection,
    )

    @Test
    fun `surfer moves away from the GF the enemy keeps hitting`() {
        // FORWARD continues east, which covers negative GF. When the enemy
        // keeps hitting +0.8 the surfer keeps going east.
        val danger = EmpiricalDanger()
        repeat(8) { danger.recordHit(0.8) }
        val choice = surfer.choose(self, enemy, listOf(waveAt(1.0)), danger, emptyMap(), 1.0, 100)
        assertEquals(Surfer.Option.FORWARD, choice.option)
    }

    @Test
    fun `surfer flips when the enemy hits the other side`() {
        val danger = EmpiricalDanger()
        repeat(8) { danger.recordHit(-0.8) }
        val choice = surfer.choose(self, enemy, listOf(waveAt(1.0)), danger, emptyMap(), 1.0, 100)
        assertEquals(Surfer.Option.BACKWARD, choice.option)
    }

    @Test
    fun `empty danger keeps the current direction`() {
        val choice = surfer.choose(self, enemy, listOf(waveAt(1.0)), EmpiricalDanger(), emptyMap(), 1.0, 100)
        assertEquals(Surfer.Option.FORWARD, choice.option)
    }
}
