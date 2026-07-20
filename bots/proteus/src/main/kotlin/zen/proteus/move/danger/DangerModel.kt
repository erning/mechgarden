package zen.proteus.move.danger

/** A danger model: one hypothesis about where the enemy's bullets go. */
internal interface DangerModel {
    /** Activation gate: the model is active while the enemy's hit-rate
     *  confidence interval overlaps [minHitRate, maxHitRate]. */
    val minHitRate: Double
    val maxHitRate: Double

    /**
     * Danger density over guess factors for [wave], evaluated at (evalX, evalY)
     * (the surfing sim's current position; only dynamic models like CurrentGF
     * use it). Implementations cache per wave where possible; null means the
     * model has no opinion yet (insufficient data).
     */
    fun binsFor(
        wave: EnemyWave,
        evalX: Double,
        evalY: Double,
    ): DoubleArray?

    /** The wave resolved as a real bullet at [actualGf] (hit us, or collided). */
    fun learn(
        wave: EnemyWave,
        actualGf: Double,
    ) {}

    /** The wave passed us harmlessly, covering [wave.wave]'s visit interval. */
    fun onPass(wave: EnemyWave) {}

    /** The wave is gone; drop any per-wave cache. */
    fun forget(wave: EnemyWave) {}
}
