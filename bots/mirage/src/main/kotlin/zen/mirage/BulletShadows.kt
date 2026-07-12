package zen.mirage

import robocode.Bullet
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Bullet shadows: the guess factors of an enemy wave that our own in-flight
 * bullets physically protect. The engine collides bullets whose tick segments
 * intersect, and an enemy bullet at guess factor g rides its ray at exactly the
 * wave front — so wherever our bullet's tick segment overlaps the front's
 * expanding annulus, every enemy bullet that could occupy those angles dies on
 * contact and that GF band is *guaranteed safe to stand in*. Surfing reads treat
 * shadowed bins as zero danger ([EnemyWave.dangerWindow]).
 *
 * Casting happens on the two discrete events (our fire × existing waves, new
 * wave × live bullets), never per tick. A shadow is provisional on our bullet
 * surviving to the crossing: if it dies early it hadn't flown yet, the pending
 * shadow is retracted; reaching the wall means the full path was flown and the
 * cast stands.
 */
class BulletShadows {
    private class Cast(
        val wave: EnemyWave,
        val lowGf: Double,
        val highGf: Double,
        val crossTime: Long,
    )

    private class Shot(
        val bullet: Bullet,
        val x0: Double,
        val y0: Double,
        val dx: Double,
        val dy: Double,
        val fireTime: Long,
    ) {
        val casts = ArrayList<Cast>(4)
    }

    private val shots = ArrayList<Shot>()

    fun onFire(
        bullet: Bullet,
        now: Long,
        waves: List<EnemyWave>,
    ) {
        val rad = Math.toRadians(bullet.heading)
        val shot = Shot(bullet, bullet.x, bullet.y, sin(rad) * bullet.velocity, cos(rad) * bullet.velocity, now)
        shots += shot
        for (w in waves) cast(shot, w, now)
    }

    fun onWave(
        wave: EnemyWave,
        now: Long,
    ) {
        for (s in shots) cast(s, wave, now)
    }

    fun onBulletDead(
        bullet: Bullet,
        now: Long,
    ) {
        // Bullet events contain a reconstructed Bullet instance. Robocode's
        // Bullet.equals() matches that instance to the fired bullet by its
        // engine bullet id; reference identity can therefore never resolve an
        // ordinary BulletHit/BulletMissed/BulletHitBullet event.
        val i = shots.indexOfFirst { it.bullet == bullet }
        if (i < 0) return
        val shot = shots.removeAt(i)
        for (c in shot.casts) if (c.crossTime > now) c.wave.removeShadow(c.lowGf, c.highGf)
    }

    fun onBulletGone(bullet: Bullet) {
        shots.removeAll { it.bullet == bullet }
    }

    internal fun trackedShotCount(): Int = shots.size

    private fun cast(
        shot: Shot,
        wave: EnemyWave,
        now: Long,
    ) {
        var t = maxOf(now, shot.fireTime)
        val end = t + MAX_TICKS
        while (t < end) {
            val bx = shot.x0 + shot.dx * (t - shot.fireTime)
            val by = shot.y0 + shot.dy * (t - shot.fireTime)
            val px = bx - wave.sourceX
            val py = by - wave.sourceY
            val dist = hypot(px, py)
            val rIn = wave.radius(t)
            if (rIn >= dist) return
            val rOut = rIn + wave.velocity
            val a = shot.dx * shot.dx + shot.dy * shot.dy
            val b = px * shot.dx + py * shot.dy
            val c0 = px * px + py * py
            var lowGf = Double.NaN
            var highGf = Double.NaN
            for (s in candidateS(a, b, c0, rIn, rOut)) {
                val d2 = c0 + 2.0 * b * s + a * s * s
                if (d2 < rIn * rIn - EPS || d2 > rOut * rOut + EPS) continue
                val gf = wave.guessFactor(bx + shot.dx * s, by + shot.dy * s)
                if (lowGf.isNaN() || gf < lowGf) lowGf = gf
                if (highGf.isNaN() || gf > highGf) highGf = gf
            }
            if (!lowGf.isNaN()) {
                wave.addShadow(lowGf, highGf)
                shot.casts += Cast(wave, lowGf, highGf, t + 1)
                return
            }
            t++
        }
    }

    private fun candidateS(
        a: Double,
        b: Double,
        c0: Double,
        rIn: Double,
        rOut: Double,
    ): DoubleArray {
        val out = ArrayList<Double>(6)
        out += 0.0
        out += 1.0
        for (boundary in doubleArrayOf(rIn, rOut)) {
            val c = c0 - boundary * boundary
            val disc = b * b - a * c
            if (disc < 0.0 || a == 0.0) continue
            val sq = sqrt(disc)
            for (root in doubleArrayOf((-b - sq) / a, (-b + sq) / a)) {
                if (root in 0.0..1.0) out += root
            }
        }
        return out.toDoubleArray()
    }

    private companion object {
        const val MAX_TICKS = 120L
        const val EPS = 1e-6
    }
}
