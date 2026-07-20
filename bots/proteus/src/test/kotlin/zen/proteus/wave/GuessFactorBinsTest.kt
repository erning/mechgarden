package zen.proteus.wave

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GuessFactorBinsTest {
    @Test
    fun `index maps the gf range onto the bins`() {
        assertEquals(0, GuessFactorBins.index(-1.0))
        assertEquals((GuessFactorBins.COUNT - 1) / 2, GuessFactorBins.index(0.0))
        assertEquals(GuessFactorBins.COUNT - 1, GuessFactorBins.index(1.0))
        assertEquals(0, GuessFactorBins.index(-3.0))
        assertEquals(GuessFactorBins.COUNT - 1, GuessFactorBins.index(3.0))
    }

    @Test
    fun `mass concentrates where density was recorded`() {
        val bins = DoubleArray(GuessFactorBins.COUNT)
        bins[GuessFactorBins.index(0.5)] = 5.0
        val near = GuessFactorBins.mass(bins, 5.0, 0.45, 0.55)
        val far = GuessFactorBins.mass(bins, 5.0, -0.9, -0.8)
        assertTrue(near > 0.5)
        assertTrue(far < near * 0.01)
    }

    @Test
    fun `mass of empty bins is zero`() {
        val bins = DoubleArray(GuessFactorBins.COUNT)
        assertEquals(0.0, GuessFactorBins.mass(bins, 0.0, -1.0, 1.0), 1e-12)
    }
}
