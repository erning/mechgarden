package zen.fencer

/**
 * Wave-surfing cost profile. Profiles share the same [Surfer] implementation;
 * only candidate speeds and cost weights change.
 */
data class SurfProfile(
    val name: String,
    /** Candidate speed caps to evaluate per orbit direction; first entry wins ties. */
    val speeds: List<Double> = listOf(Kinematics.MAX_VELOCITY),
    /** Weight on the second-nearest wave's danger at its predicted arrival. */
    val secondWaveDiscount: Double = 0.0,
    /** Wall/corner risk weight. */
    val wallWeight: Double = 0.03,
    /** Cost reduction for keeping the previous orbit direction. */
    val inertiaBonus: Double = 0.0,
    /** Random jitter added to each candidate cost; 0 keeps movement deterministic. */
    val tieBreakNoise: Double = 0.0,
) {
    companion object {
        val DEFAULT = SurfProfile("default")

        val MULTI_WAVE = SurfProfile("multiwave", secondWaveDiscount = 0.5)
    }
}
