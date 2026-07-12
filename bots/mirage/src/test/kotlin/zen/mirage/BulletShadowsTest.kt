package zen.mirage

import robocode.Bullet
import kotlin.test.Test
import kotlin.test.assertEquals

class BulletShadowsTest {
    @Test
    fun reconstructedDeadBulletRetractsItsFutureShadow() {
        val wave =
            EnemyWave(
                sourceX = 400.0,
                sourceY = 100.0,
                fireTime = 0L,
                power = 2.0,
                velocity = 14.0,
                directAngleRadians = 0.0,
                orbitDirection = 1,
                features = WaveFeatures(200.0, 0.0, 0, 1.0),
                dangerBins = DoubleArray(GuessFactorDanger.BINS) { 1.0 },
            )
        val shadows = BulletShadows()

        shadows.onFire(bullet(id = 41, active = true), now = 0L, waves = listOf(wave))
        assertEquals(0.0, wave.dangerWindow(0.0, 0.0))

        shadows.onBulletDead(bullet(id = 41, active = false), now = 0L)
        assertEquals(1.0, wave.dangerWindow(0.0, 0.0))
        assertEquals(0, shadows.trackedShotCount())
    }

    private fun bullet(
        id: Int,
        active: Boolean,
    ): Bullet =
        Bullet(
            Angles.PI,
            400.0,
            300.0,
            1.0,
            "zen.Mirage",
            null,
            active,
            id,
        )
}
