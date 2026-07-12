package zen.mirage

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RamThreatPolicyTest {
    @Test
    fun remembersConfirmedThreatAcrossRounds() {
        val policy = RamThreatPolicy()

        policy.recordRound(threatSeen = true)

        assertTrue(policy.aggressiveFireRecommended())
    }

    @Test
    fun forgetsThreatAfterConsecutiveQuietRounds() {
        val policy = RamThreatPolicy()
        policy.recordRound(threatSeen = true)

        repeat(2) { policy.recordRound(threatSeen = false) }
        assertTrue(policy.aggressiveFireRecommended())

        policy.recordRound(threatSeen = false)
        assertFalse(policy.aggressiveFireRecommended())
    }
}
