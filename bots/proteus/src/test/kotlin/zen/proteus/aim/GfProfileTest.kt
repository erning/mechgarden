package zen.proteus.aim

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GfProfileTest {
    @Test
    fun `empty profile aims straight`() {
        assertEquals(0.0, GfProfile().bestGuessFactor(), 1e-12)
    }

    @Test
    fun `peak sits where samples were recorded`() {
        val profile = GfProfile()
        repeat(10) { profile.record(0.4, 0.6) }
        assertEquals(0.5, profile.bestGuessFactor(), 0.1)
    }

    @Test
    fun `recency favors recent samples`() {
        val profile = GfProfile()
        repeat(20) { profile.record(-0.9, -0.7) }
        repeat(30) { profile.record(0.6, 0.8) }
        assertTrue(profile.bestGuessFactor() > 0.4)
    }
}
