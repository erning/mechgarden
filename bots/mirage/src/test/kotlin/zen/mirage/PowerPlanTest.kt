package zen.mirage

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PowerPlanTest {
    @Test
    fun antiRamOverridesHarvestAndDisablesAdaptivePower() {
        val plan = plan(adaptivePower = true, antiRam = true, harvest = true)

        assertEquals(FirePowerSelector.Profile.AGGRESSIVE, plan.profile)
        assertEquals(1.2, plan.floor)
        assertFalse(plan.adaptive)
    }

    @Test
    fun lowThreatHarvestKeepsAdaptiveEligibility() {
        val plan = plan(adaptivePower = true, antiRam = false, harvest = true)

        assertEquals(FirePowerSelector.Profile.BALANCED, plan.profile)
        assertEquals(1.2, plan.floor)
        assertTrue(plan.adaptive)
    }

    @Test
    fun policyIsTheDefaultPlan() {
        val plan = plan(adaptivePower = false, antiRam = false, harvest = false)

        assertEquals(FirePowerSelector.Profile.ECONOMY, plan.profile)
        assertEquals(0.6, plan.floor)
        assertFalse(plan.adaptive)
    }

    private fun plan(
        adaptivePower: Boolean,
        antiRam: Boolean,
        harvest: Boolean,
    ): Gun.PowerPlan =
        Gun.PowerPlan.forSituation(
            policyProfile = FirePowerSelector.Profile.ECONOMY,
            policyFloor = 0.6,
            adaptivePower = adaptivePower,
            antiRamFireActive = antiRam,
            lowThreatHarvestActive = harvest,
            tacticalFloor = 1.2,
        )
}
