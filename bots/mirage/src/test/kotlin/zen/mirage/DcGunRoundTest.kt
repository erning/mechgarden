package zen.mirage

import kotlin.test.Test
import kotlin.test.assertEquals

class DcGunRoundTest {
    @Test
    fun beginRoundDropsPendingWaves() {
        val gun = DcGun()
        gun.onFire(FEATURES, 0.0, 0.0, 100, 10.0, 0.0, 1, 0.5)

        gun.beginRound()
        gun.update(1_000, 0.0, 0.0)

        assertEquals(0, gun.size())
    }

    @Test
    fun beginRoundPreservesResolvedObservations() {
        val gun = DcGun()
        gun.onFire(FEATURES, 0.0, 0.0, 0, 10.0, 0.0, 1, 0.5)
        gun.update(20, 100.0, 0.0)

        gun.beginRound()

        assertEquals(1, gun.size())
    }

    private companion object {
        val FEATURES = doubleArrayOf(0.4, 0.8, 0.2, 0.0, 0.5, 0.1)
    }
}
