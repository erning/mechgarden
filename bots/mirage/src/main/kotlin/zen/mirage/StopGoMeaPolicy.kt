package zen.mirage

/** Pure policy for selecting the gun's guess-factor MEA scale. */
object StopGoMeaPolicy {
    fun useTheory(
        stopGoMode: String?,
        meaMode: String?,
        stopGoLikely: Boolean,
        distance: Double,
    ): Boolean =
        when (stopGoMode?.trim()?.lowercase()) {
            "force" -> true
            "off" -> false
            else ->
                when (meaMode?.trim()?.lowercase()) {
                    "theory" -> true
                    "precise" -> false
                    else -> stopGoLikely && distance >= MIN_DISTANCE
                }
        }

    const val MIN_DISTANCE = 250.0
}
