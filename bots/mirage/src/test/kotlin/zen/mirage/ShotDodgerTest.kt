package zen.mirage

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ShotDodgerTest {
    @Test
    fun waitsForEnoughPredictionEvidence() {
        val dodger = ShotDodger()
        val predictions = predictions()

        repeat(7) { dodger.recordHit(predictions, actualGuessFactor = 0.45, hullHalfGuessFactor = 0.04) }

        assertNull(dodger.decision(predictions))
    }

    @Test
    fun selectsAClearlyMoreAccurateExpertAndAddsDanger() {
        val dodger = ShotDodger()
        val predictions = predictions()
        repeat(8) { dodger.recordHit(predictions, actualGuessFactor = 0.45, hullHalfGuessFactor = 0.04) }

        val decision = assertNotNull(dodger.decision(predictions))
        assertEquals(SimulatedTargeting.Expert.LINEAR, decision.expert)
        assertTrue(decision.accuracy > 0.95)
        assertTrue(decision.lead > 0.12)

        val danger = dodger.augmentDanger(DoubleArray(GuessFactorDanger.BINS), predictions)
        val selectedBin = GuessFactorDanger.binIndex(0.45)
        val headOnBin = GuessFactorDanger.binIndex(0.0)
        assertTrue(danger[selectedBin] > danger[headOnBin])
    }

    @Test
    fun fallsBackAfterThreeConsecutiveContradictions() {
        val dodger = ShotDodger()
        val predictions = predictions()
        repeat(8) { dodger.recordHit(predictions, actualGuessFactor = 0.45, hullHalfGuessFactor = 0.04) }
        assertNotNull(dodger.decision(predictions))

        repeat(3) { dodger.recordPass(predictions, coveredLowGuessFactor = 0.42, coveredHighGuessFactor = 0.48) }

        assertNull(dodger.decision(predictions))
    }

    private fun predictions() =
        SimulatedTargeting.Predictions(
            doubleArrayOf(
                0.0,
                0.45,
                -0.45,
                0.22,
            ),
        )
}
