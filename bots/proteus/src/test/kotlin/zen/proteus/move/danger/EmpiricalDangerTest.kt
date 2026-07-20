package zen.proteus.move.danger

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EmpiricalDangerTest {
    @Test
    fun `danger concentrates where hits were recorded`() {
        val danger = EmpiricalDanger()
        repeat(5) { danger.recordHit(0.5) }
        assertTrue(danger.danger(0.4, 0.6) > danger.danger(-0.6, -0.4))
    }

    @Test
    fun `visits add danger where we have been`() {
        val danger = EmpiricalDanger()
        danger.recordVisit(-0.2, 0.2)
        assertTrue(danger.danger(-0.1, 0.1) > 0.0)
        assertEquals(0.0, danger.danger(0.8, 0.9), 1e-9)
    }

    @Test
    fun `hits outweigh visits`() {
        val danger = EmpiricalDanger()
        danger.recordHit(0.0)
        danger.recordVisit(-0.1, 0.1)
        val hitOnly = EmpiricalDanger()
        hitOnly.recordVisit(-0.1, 0.1)
        assertTrue(danger.danger(-0.05, 0.05) > hitOnly.danger(-0.05, 0.05))
    }
}
