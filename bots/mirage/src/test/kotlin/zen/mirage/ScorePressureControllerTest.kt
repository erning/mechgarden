package zen.mirage

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ScorePressureControllerTest {
    @Test
    fun enablesAdaptationAfterEightSafeCompletedRounds() {
        val controller = ScorePressureController()

        repeat(8) {
            assertFalse(controller.beginRound())
            controller.recordRound(survived = true, damageTaken = 20.0)
        }

        assertTrue(controller.beginRound())
    }

    @Test
    fun rejectsUnsafeSurvivalOrAverageDamage() {
        val death = ScorePressureController()
        repeat(7) {
            death.beginRound()
            death.recordRound(survived = true, damageTaken = 20.0)
        }
        death.beginRound()
        death.recordRound(survived = false, damageTaken = 20.0)
        assertFalse(death.beginRound())

        val damage = ScorePressureController()
        repeat(8) {
            damage.beginRound()
            damage.recordRound(survived = true, damageTaken = 36.0)
        }
        assertFalse(damage.beginRound())
    }

    @Test
    fun heavyDamageBacksOffForThreeRounds() {
        val controller = ScorePressureController()
        repeat(8) {
            controller.beginRound()
            controller.recordRound(survived = true, damageTaken = 20.0)
        }
        assertTrue(controller.beginRound())
        controller.recordRound(survived = true, damageTaken = 45.0)

        repeat(3) {
            assertFalse(controller.beginRound())
            controller.recordRound(survived = true, damageTaken = 20.0)
        }

        assertTrue(controller.beginRound())
    }
}
