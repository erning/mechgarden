package zen.mirage

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OffenseStatsTest {
    @Test
    fun countsBulletCollisionsAsNonHits() {
        val stats = OffenseStats()

        repeat(2) { stats.recordHit() }
        stats.recordNonHit()
        stats.recordNonHit()

        assertEquals(4L, stats.resolvedShots())
        assertEquals(0.5, stats.hitRate())
    }

    @Test
    fun hasNoRateBeforeAResolvedShot() {
        assertTrue(OffenseStats().hitRate().isNaN())
    }
}
