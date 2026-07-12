package zen.mirage

import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DcGunAntiSurferTest {
    @Test
    fun `hit penalty uses bullet contact GF instead of later center visit`() {
        val gun = DcGun(k = 1, halfLifeOverride = 0.0, hitPenalty = 2.0)
        val features = gun.features(100.0, 8.0, 0.0, 0.0, 1.0, 10)
        val hitBullet = ShotToken(1)
        val hitEventBullet = ShotToken(1)

        gun.onFire(features, 0.0, 0.0, 0, 10.0, 0.0, 1, 1.0, hitBullet)
        gun.recordHit(hitEventBullet, sin(0.5) * 100.0, cos(0.5) * 100.0)
        gun.update(10, sin(-0.5) * 100.0, cos(-0.5) * 100.0)

        val aim = gun.aimRadians(features, 0.0, 1, 1.0, 0.0)
        assertTrue(aim < 0.0, "expected positive visit mass at the negative center GF, got $aim")
    }

    @Test
    fun `killing hit survives round cleanup without a later center visit`() {
        val gun = DcGun(k = 1, halfLifeOverride = 0.0, hitPenalty = 2.0)
        val features = gun.features(100.0, 8.0, 0.0, 0.0, 1.0, 10)
        val shot = ShotToken(2)

        gun.onFire(features, 0.0, 0.0, 0, 10.0, 0.0, 1, 1.0, shot)
        gun.recordHit(ShotToken(2), sin(0.5) * 100.0, cos(0.5) * 100.0)
        gun.beginRound()

        val aim = gun.aimRadians(features, 0.0, 1, 1.0, 0.0)
        assertEquals(1, gun.size())
        assertTrue(aim < 0.0, "expected aim away from the preserved positive-GF killing hit, got $aim")
    }

    @Test
    fun `recency weighting favors the newest matching observation`() {
        val gun = DcGun(k = 2, halfLifeOverride = 0.25)
        val features = gun.features(100.0, 8.0, 0.0, 0.0, 1.0, 10)

        observe(gun, features, guessFactor = -0.6)
        observe(gun, features, guessFactor = 0.6)

        val aim = gun.aimRadians(features, 0.0, 1, 1.0, 0.0)
        assertTrue(aim > 0.0, "expected the newer positive-GF observation to dominate, got $aim")
    }

    private fun observe(
        gun: DcGun,
        features: DoubleArray,
        guessFactor: Double,
    ) {
        gun.onFire(features, 0.0, 0.0, 0, 10.0, 0.0, 1, 1.0)
        gun.update(10, sin(guessFactor) * 100.0, cos(guessFactor) * 100.0)
    }

    private data class ShotToken(
        val id: Int,
    )
}
