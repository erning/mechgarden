package zen.mirage

import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AntiSurferEvidenceTest {
    @Test
    fun `resolved evidence survives a round-local virtual gun rebuild`() {
        val evidence = AntiSurferEvidence()
        resolveShot(VirtualGuns(evidence), evidence, mainAngle = 0.0, antiSurferAngle = 0.5)
        val attemptsAfterFirstRound = evidence.resolvedAttempts()
        assertTrue(evidence.mainHitRate() > evidence.antiSurferHitRate())

        resolveShot(VirtualGuns(evidence), evidence, mainAngle = 0.5, antiSurferAngle = 0.0)

        assertTrue(attemptsAfterFirstRound > 0.9)
        assertTrue(evidence.resolvedAttempts() > 1.9)
    }

    @Test
    fun `ineligible virtual waves do not enter shared evidence`() {
        val evidence = AntiSurferEvidence()
        val angles = DoubleArray(VirtualGuns.Aim.values().size)

        val virtualGuns = VirtualGuns(evidence)
        virtualGuns.onFire(0.0, 0.0, 0, 10.0, angles, asEvidenceEligible = false)
        virtualGuns.update(10, 0.0, 100.0)

        assertEquals(0.0, evidence.resolvedAttempts())
    }

    @Test
    fun `selector requires a material lead and exits with hysteresis`() {
        val evidence = AntiSurferEvidence()
        repeat(120) { evidence.record(mainHit = false, antiSurferHit = false) }
        repeat(4) { evidence.record(mainHit = false, antiSurferHit = true) }

        assertTrue(evidence.select(100.0, 0.20, 0.22, 0.03, 0.005))

        evidence.record(mainHit = true, antiSurferHit = false)
        assertTrue(evidence.select(100.0, 0.20, 0.22, 0.03, 0.005))

        repeat(4) { evidence.record(mainHit = true, antiSurferHit = false) }
        assertFalse(evidence.select(100.0, 0.20, 0.22, 0.03, 0.005))
    }

    private fun resolveShot(
        virtualGuns: VirtualGuns,
        evidence: AntiSurferEvidence,
        mainAngle: Double,
        antiSurferAngle: Double,
    ) {
        val angles = DoubleArray(VirtualGuns.Aim.values().size)
        angles[VirtualGuns.Aim.GF_DC.ordinal] = mainAngle
        angles[VirtualGuns.Aim.GF_DC_AS.ordinal] = antiSurferAngle
        virtualGuns.onFire(0.0, 0.0, 0, 10.0, angles, asEvidenceEligible = true)
        virtualGuns.update(10, sin(0.0) * 100.0, cos(0.0) * 100.0)
        assertTrue(evidence.mainHitRate().isFinite())
    }
}
