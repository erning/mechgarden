package zen.proteus.move

import zen.proteus.core.Battlefield
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Our bullets in flight, tracked analytically: a bullet spawns at the start of
 * the turn after we fired and moves in the same turn, so at time t it has
 * traveled (t - fireTime + 1) * speed. No engine feedback needed; bullets are
 * removed when their hit events arrive or when they would have left the field.
 */
internal class OurBullets {
    class Tracked(
        val x: Double,
        val y: Double,
        val angleRadians: Double,
        val speed: Double,
        val fireTime: Long,
        val power: Double,
    ) {
        fun xAt(time: Long): Double = x + sin(angleRadians) * traveled(time)

        fun yAt(time: Long): Double = y + cos(angleRadians) * traveled(time)

        private fun traveled(time: Long): Double = (time - fireTime + 1) * speed
    }

    private val bullets = ArrayList<Tracked>()

    fun add(bullet: Tracked) {
        bullets.add(bullet)
    }

    fun clear() {
        bullets.clear()
    }

    fun all(): List<Tracked> = bullets

    /** Our bullet ended at (x, y) at [time] — it hit something. Removes the closest match. */
    fun removeNear(
        x: Double,
        y: Double,
        time: Long,
    ) {
        var best: Tracked? = null
        var bestDistance = Double.POSITIVE_INFINITY
        for (bullet in bullets) {
            val distance = hypot(bullet.xAt(time) - x, bullet.yAt(time) - y)
            if (distance < bestDistance) {
                bestDistance = distance
                best = bullet
            }
        }
        if (best != null && bestDistance <= best.speed + MATCH_SLACK) {
            bullets.remove(best)
        }
    }

    /** Drops bullets whose straight path would have left the field (wall hits). */
    fun prune(
        field: Battlefield,
        time: Long,
    ) {
        bullets.removeAll { bullet ->
            val x = bullet.xAt(time)
            val y = bullet.yAt(time)
            x < 0.0 || x > field.width || y < 0.0 || y > field.height
        }
    }

    private companion object {
        const val MATCH_SLACK = 20.0
    }
}
