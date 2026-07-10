package zen.mirage

/**
 * Per-enemy threat instrumentation: how often the enemy's bullets actually hit us.
 *
 * The two counts are disjoint by construction. An enemy wave either fully passes
 * our hull (we dodged it — counted via [recordWavePassed] at the wave tracker's
 * passed-callback, which removes the wave) or connects ([recordHit] in
 * `onHitByBullet`, where `EnemyWaveTracker.matchBullet` pulls the wave before it
 * could ever pass). Bullet-on-bullet waves are removed by `matchBullet` in
 * `onBulletHitBullet` without landing on either counter — that only happens against
 * bullet-shielding opponents and is rare enough to ignore here, at the cost of a
 * tiny denominator understatement.
 *
 * Lives in a per-enemy static registry ([forEnemy]) so the counts accumulate across
 * Robocode's per-round robot rebuild; the hit rate is only meaningful once a few
 * dozen waves have resolved (see `bots/mirage/docs/tuning.md` Phase 2's
 * `wavesObserved >= 60` gate).
 */
class ThreatStats {
    private var wavesPassed = 0L
    private var hitsTaken = 0L

    /** A wave fully crossed our hull without connecting — we dodged it. */
    fun recordWavePassed() {
        wavesPassed++
    }

    /** An enemy bullet hit us. Counted unconditionally (a connect is a connect even
     *  if `matchBullet` can't pair it to a tracked wave). */
    fun recordHit() {
        hitsTaken++
    }

    /** Total enemy waves resolved against us (dodged + connected). */
    fun wavesObserved(): Long = wavesPassed + hitsTaken

    /** The enemy's realized hit rate against us: connects / (dodged + connects).
     *  NaN until the first wave resolves. */
    fun enemyHitRate(): Double {
        val total = wavesPassed + hitsTaken
        return if (total == 0L) Double.NaN else hitsTaken.toDouble() / total.toDouble()
    }

    companion object {
        private val perEnemy = HashMap<String, ThreatStats>()

        fun forEnemy(name: String): ThreatStats = perEnemy.getOrPut(name) { ThreatStats() }
    }
}
