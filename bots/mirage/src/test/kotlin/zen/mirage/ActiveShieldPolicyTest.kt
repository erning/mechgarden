package zen.mirage

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ActiveShieldPolicyTest {
    @Test
    fun schedulesATrialAfterThreeLosingNormalRounds() {
        val policy = ActiveShieldPolicy()

        repeat(3) {
            policy.beginRound()
            assertFalse(policy.activeForRound())
            policy.recordRound(dealt = 20.0, taken = 70.0, survived = false, usedActiveShield = false)
        }
        policy.beginRound()

        assertTrue(policy.activeForRound())
    }

    @Test
    fun latchesAfterShieldTrialsBeatNormalRounds() {
        val policy = ActiveShieldPolicy()

        repeat(3) {
            policy.beginRound()
            policy.recordRound(dealt = 20.0, taken = 70.0, survived = false, usedActiveShield = false)
        }
        policy.beginRound()
        policy.recordRound(dealt = 40.0, taken = 45.0, survived = true, usedActiveShield = true)
        policy.beginRound()
        policy.recordRound(dealt = 20.0, taken = 70.0, survived = false, usedActiveShield = false)
        policy.beginRound()
        policy.recordRound(dealt = 40.0, taken = 45.0, survived = true, usedActiveShield = true)
        policy.beginRound()

        assertTrue(policy.activeForRound())
    }

    @Test
    fun doesNotLatchWhenShieldTrialsUnderperformNormalPlay() {
        val policy = ActiveShieldPolicy()

        repeat(3) {
            policy.beginRound()
            policy.recordRound(dealt = 35.0, taken = 30.0, survived = true, usedActiveShield = false)
        }
        repeat(2) {
            policy.recordRound(dealt = 20.0, taken = 50.0, survived = true, usedActiveShield = true)
        }
        policy.beginRound()

        assertFalse(policy.activeForRound())
    }
}
