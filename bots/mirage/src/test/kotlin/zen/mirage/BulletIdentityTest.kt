package zen.mirage

import robocode.Bullet
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BulletIdentityTest {
    @Test
    fun reconstructedEventBulletClearsTrackedShadowShot() {
        val shadows = BulletShadows()
        shadows.onFire(bullet(id = 17, active = true), now = 0L, waves = emptyList())

        shadows.onBulletGone(bullet(id = 17, active = false))

        assertEquals(0, shadows.trackedShotCount())
    }

    @Test
    fun reconstructedEventBulletCompletesPerShotReward() {
        val rewards = PerShotRewards(size = 2, priorWeight = 0.0, priorReward = 0.0)
        rewards.onFire(ordinal = 1, bullet = bullet(id = 23, active = true))

        rewards.complete(bullet(id = 23, active = false), outcomeReward = 4.0)

        assertEquals(1L, rewards.shotCount(1))
        assertEquals(4.0, rewards.rewardPerShot(1))
    }

    @Test
    fun unresolvedRoundEndBulletStillCountsItsEnergyCost() {
        val rewards = PerShotRewards(size = 1, priorWeight = 0.0, priorReward = 0.0)
        rewards.onFire(ordinal = 0, bullet = bullet(id = 29, active = true, power = 2.0))

        rewards.settlePending { -it.power }

        assertEquals(1L, rewards.shotCount(0))
        assertEquals(-2.0, rewards.rewardPerShot(0))
    }

    @Test
    fun adaptiveFirepowerInterleavesProfilesAndLearnsFromEventBullets() {
        val selector = FirePowerSelector()
        val previousPowerProperty = System.getProperty("mirage.power")
        System.setProperty("mirage.power", "auto")
        try {
            repeat(32) { index ->
                val selection = selector.select(FirePowerSelector.Profile.ECONOMY)
                val expected =
                    if (index % 2 == 0) {
                        FirePowerSelector.Profile.BALANCED
                    } else {
                        FirePowerSelector.Profile.ECONOMY
                    }
                assertEquals(expected, selection.profile)
                assertTrue(selection.adaptive)
                val fired = bullet(id = 100 + index, active = true)
                selector.onFire(selection, fired)
                val event = bullet(id = 100 + index, active = false)
                if (selection.profile == FirePowerSelector.Profile.BALANCED) {
                    selector.recordHit(event)
                } else {
                    selector.recordMiss(event)
                }
            }

            assertEquals(16L, selector.resolvedShots(FirePowerSelector.Profile.BALANCED))
            assertEquals(16L, selector.resolvedShots(FirePowerSelector.Profile.ECONOMY))
            assertEquals(FirePowerSelector.Profile.BALANCED, selector.select().profile)
        } finally {
            if (previousPowerProperty == null) {
                System.clearProperty("mirage.power")
            } else {
                System.setProperty("mirage.power", previousPowerProperty)
            }
        }
    }

    @Test
    fun adaptiveFirepowerInterleavesWhileEarlierShotsArePending() {
        withPowerOverride("auto") {
            val selector = FirePowerSelector()
            val selected =
                List(4) { index ->
                    selector.select().also { selector.onFire(it, bullet(id = 200 + index, active = true)) }.profile
                }

            assertEquals(
                listOf(
                    FirePowerSelector.Profile.BALANCED,
                    FirePowerSelector.Profile.ECONOMY,
                    FirePowerSelector.Profile.BALANCED,
                    FirePowerSelector.Profile.ECONOMY,
                ),
                selected,
            )
        }
    }

    @Test
    fun invalidPowerOverrideFailsFast() {
        withPowerOverride("balnced") {
            val error = assertFailsWith<IllegalArgumentException> { FirePowerSelector().select(FirePowerSelector.Profile.ECONOMY) }
            assertTrue(error.message.orEmpty().contains("balnced"))
        }
    }

    @Test
    fun policyShotsDoNotPolluteALaterAdaptiveTrial() {
        val selector = FirePowerSelector()
        val selection = selector.select(FirePowerSelector.Profile.ECONOMY, adaptiveEnabled = false)
        assertFalse(selection.adaptive)
        selector.onFire(selection, bullet(id = 71, active = true))
        selector.recordHit(bullet(id = 71, active = false))

        assertEquals(0L, selector.resolvedShots(FirePowerSelector.Profile.ECONOMY))
    }

    private fun bullet(
        id: Int,
        active: Boolean,
        power: Double = 1.0,
    ): Bullet =
        Bullet(
            0.0,
            400.0,
            300.0,
            power,
            "zen.Mirage",
            null,
            active,
            id,
        )

    private inline fun withPowerOverride(
        value: String,
        block: () -> Unit,
    ) {
        val previous = System.getProperty("mirage.power")
        System.setProperty("mirage.power", value)
        try {
            block()
        } finally {
            if (previous == null) {
                System.clearProperty("mirage.power")
            } else {
                System.setProperty("mirage.power", previous)
            }
        }
    }
}
