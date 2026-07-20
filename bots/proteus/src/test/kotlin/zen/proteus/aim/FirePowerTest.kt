package zen.proteus.aim

import robocode.Rules
import zen.proteus.state.BotState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FirePowerTest {
    private val firePower = FirePower()

    private fun bot(energy: Double) = BotState(0, 100.0, 100.0, 0.0, 0.0, energy, 0.0)

    @Test
    fun `nearly dead enemy gets the cheapest kill power`() {
        // 10 energy dies to damage 10 = power 2.5 (4 * 2.5); nothing heavier needed.
        assertEquals(2.5, firePower.select(bot(80.0), bot(10.0))!!, 1e-9)
    }

    @Test
    fun `nearly drained self fires minimum power`() {
        assertEquals(Rules.MIN_BULLET_POWER, firePower.select(bot(0.5), bot(80.0)))
    }

    @Test
    fun `no opinion in a normal fight`() {
        assertNull(firePower.select(bot(80.0), bot(80.0)))
    }
}
