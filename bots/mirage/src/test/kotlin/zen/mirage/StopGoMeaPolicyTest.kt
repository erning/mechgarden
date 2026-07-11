package zen.mirage

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StopGoMeaPolicyTest {
    @Test
    fun autoRequiresBothClassificationAndLongRange() {
        assertFalse(StopGoMeaPolicy.useTheory("auto", null, stopGoLikely = false, distance = 500.0))
        assertFalse(StopGoMeaPolicy.useTheory("auto", null, stopGoLikely = true, distance = 249.9))
        assertTrue(StopGoMeaPolicy.useTheory("auto", null, stopGoLikely = true, distance = 250.0))
    }

    @Test
    fun explicitStopGoModesOverrideAutomaticDetection() {
        assertTrue(StopGoMeaPolicy.useTheory("force", "precise", stopGoLikely = false, distance = 100.0))
        assertFalse(StopGoMeaPolicy.useTheory("off", "theory", stopGoLikely = true, distance = 500.0))
    }

    @Test
    fun legacyMeaOverrideStillWorksInAutomaticMode() {
        assertTrue(StopGoMeaPolicy.useTheory(null, "theory", stopGoLikely = false, distance = 100.0))
        assertFalse(StopGoMeaPolicy.useTheory(null, "precise", stopGoLikely = true, distance = 500.0))
    }

    @Test
    fun onIsNotAnAliasForForce() {
        assertFalse(StopGoMeaPolicy.useTheory("on", null, stopGoLikely = false, distance = 500.0))
        assertTrue(StopGoMeaPolicy.useTheory("on", null, stopGoLikely = true, distance = 500.0))
    }
}
