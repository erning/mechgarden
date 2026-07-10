package zen.mirage

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EngagementStatsTest {
    @Test
    fun recordsCloseTicksAndExtremaAcrossScanGaps() {
        val stats = EngagementStats()

        stats.recordScan(time = 10, distance = 160.0, closingSpeed = 3.0, wallSpace = 50.0)
        stats.recordScan(time = 11, distance = 140.0, closingSpeed = 5.0, wallSpace = 40.0)
        stats.recordScan(time = 14, distance = 90.0, closingSpeed = 2.0, wallSpace = 60.0)

        assertEquals(90.0, stats.minDistance())
        assertEquals(40.0, stats.minWallSpace())
        assertEquals(3L, stats.closeTicks100())
        assertEquals(4L, stats.closeTicks150())
        assertEquals(5.0, stats.maxClosingSpeed())
    }

    @Test
    fun recordsCollisionResponsibilityAndResetsRound() {
        val stats = EngagementStats()
        stats.recordCollision(atFault = false)
        stats.recordCollision(atFault = true)

        assertEquals(2L, stats.collisions())
        assertEquals(1L, stats.atFaultCollisions())
        assertTrue(stats.debugSummary().contains("ram=2/1"))

        stats.reset()

        assertEquals(0L, stats.collisions())
        assertEquals(0L, stats.atFaultCollisions())
        assertTrue(stats.minDistance().isInfinite())
    }

    @Test
    fun recordsAverageEnemyFirePower() {
        val stats = EngagementStats()

        stats.recordEnemyFire(2.0)
        stats.recordEnemyFire(3.0)

        assertEquals(2.5, stats.averageEnemyFirePower())
        assertTrue(stats.debugSummary().contains("enemyPower=2.5@2"))
    }
}
