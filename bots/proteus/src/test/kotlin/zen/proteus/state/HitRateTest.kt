package zen.proteus.state

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HitRateTest {
    @Test
    fun `no data overlaps everything`() {
        val hitRate = HitRate()
        assertTrue(hitRate.overlaps(0.0, 0.01))
        assertTrue(hitRate.overlaps(0.99, 1.0))
    }

    @Test
    fun `rate tracks hits over shots`() {
        val hitRate = HitRate()
        repeat(3) { hitRate.record(true) }
        repeat(7) { hitRate.record(false) }
        assertEquals(10, hitRate.shots)
        assertEquals(3, hitRate.hits)
        assertEquals(0.3, hitRate.rate, 1e-12)
    }

    @Test
    fun `tight interval around a settled estimate excludes far ranges`() {
        val hitRate = HitRate()
        repeat(100) { hitRate.record(true) }
        assertTrue(hitRate.overlaps(0.9, 1.0))
        assertFalse(hitRate.overlaps(0.0, 0.5))
    }

    @Test
    fun `scarce samples keep wide ranges possible`() {
        val hitRate = HitRate()
        repeat(4) { hitRate.record(true) }
        // 4/4 hits: the point estimate is 1.0 but the interval still reaches
        // well below it, and far ranges are not excluded yet.
        assertTrue(hitRate.overlaps(0.6, 0.8))
        assertFalse(hitRate.overlaps(0.0, 0.3))
    }
}
