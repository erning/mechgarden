package zen.mirage

/** Keeps guess-factor orientation stable while the target is stopped. */
object OrbitDirection {
    fun sign(
        lateralVelocity: Double,
        stickyDirection: Int,
    ): Int =
        when {
            stickyDirection > 0 -> 1
            stickyDirection < 0 -> -1
            lateralVelocity < 0.0 -> -1
            else -> 1
        }
}
