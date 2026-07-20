package zen.proteus.core

import kotlin.math.exp

/** Error function (Abramowitz & Stegun 7.1.26, |error| < 1.5e-7). */
fun erf(x: Double): Double {
    val sign = if (x < 0.0) -1.0 else 1.0
    val t = 1.0 / (1.0 + 0.2275 * kotlin.math.abs(x))
    val poly =
        t * (
            0.254829592 +
                t * (-0.284496736 + t * (1.421413741 + t * (-1.453152027 + t * 1.061405429)))
        )
    return sign * (1.0 - poly * exp(-x * x))
}

/** CDF of the standard normal distribution. */
fun normalCdf(x: Double): Double = 0.5 * (1.0 + erf(x / kotlin.math.sqrt(2.0)))

/** Mass of N(center, sigma^2) over [lo, hi]. */
fun normalMass(
    center: Double,
    sigma: Double,
    lo: Double,
    hi: Double,
): Double = normalCdf((hi - center) / sigma) - normalCdf((lo - center) / sigma)
