package zen.fencer

import kotlin.math.abs
import kotlin.math.hypot

/**
 * Holds the enemy bullet waves in flight. [FireDetector] feeds newly inferred
 * shots via [add];
 * [active] is read by surfing to choose a dodge; [sweep] tracks each wave's
 * crossing of our hull and hands the surfer the fully-passed waves (to learn
 * from) before dropping them; [matchBullet]
 * pulls the wave a given bullet belongs to (it hit us, or one of our bullets
 * destroyed it). No strategy here.
 */
class EnemyWaveTracker {
    private val waves = mutableListOf<EnemyWave>()

    /** Waves still approaching us, oldest first. */
    val active: List<EnemyWave> get() = waves

    fun add(wave: EnemyWave) {
        waves += wave
    }

    /**
     * Advance the passage bookkeeping for our position ([px], [py]) this tick:
     * waves whose front is crossing our hull widen their covered-GF interval,
     * and waves whose front has cleared the hull's far edge are removed and
     * returned — they've fully passed without a recorded hit, so a surfer
     * learns them as precise-interval "visits". Keeping a wave alive through
     * the whole crossing (not dropping it at center-pass) also lets a bullet
     * that connects a tick later still find its wave in [matchBullet].
     */
    fun sweep(
        now: Long,
        px: Double,
        py: Double,
    ): List<EnemyWave> {
        val done = mutableListOf<EnemyWave>()
        val iter = waves.iterator()
        while (iter.hasNext()) {
            val w = iter.next()
            val dist = hypot(px - w.sourceX, py - w.sourceY)
            val r = w.radius(now)
            if (r >= dist - HULL_RADIUS) w.cover(px, py)
            if (r >= dist + HULL_RADIUS) {
                done += w
                iter.remove()
            }
        }
        return done
    }

    /**
     * Find and remove the wave a bullet at ([px], [py]) with speed
     * [bulletVelocity] belongs to — the closest match in speed and radius.
     * Used both when a bullet hits us and when one of ours destroys it.
     */
    fun matchBullet(
        now: Long,
        px: Double,
        py: Double,
        bulletVelocity: Double,
    ): EnemyWave? {
        val match =
            waves
                .filter { abs(it.velocity - bulletVelocity) < SPEED_TOLERANCE }
                .minByOrNull { abs(it.radius(now) - hypot(px - it.sourceX, py - it.sourceY)) }
        if (match != null) waves.remove(match)
        return match
    }

    private companion object {
        const val SPEED_TOLERANCE = 0.5

        /** Hull reach around our center for passage bookkeeping, px. */
        const val HULL_RADIUS = 18.0
    }
}
