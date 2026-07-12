package zen.mirage

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AnglesTest {
    @Test
    fun fastRelativeNormalizationMatchesModuloDefinition() {
        for (step in -400..400) {
            val angle = step * Angles.PI / 37.0
            assertEquals(relativeByModulo(angle), Angles.normalizeRelative(angle), 1e-12)
        }
        for (boundary in doubleArrayOf(-3.0 * Angles.PI, -Angles.PI, Angles.PI, 3.0 * Angles.PI)) {
            for (angle in doubleArrayOf(Math.nextDown(boundary), boundary, Math.nextUp(boundary))) {
                assertEquals(relativeByModulo(angle), Angles.normalizeRelative(angle), 1e-12)
            }
        }
        assertEquals(Angles.PI, Angles.normalizeRelative(-Angles.PI))
        assertEquals(Angles.PI, Angles.normalizeRelative(3.0 * Angles.PI))
        assertEquals(
            java.lang.Double.doubleToRawLongBits(-0.0),
            java.lang.Double.doubleToRawLongBits(Angles.normalizeRelative(-0.0)),
        )
        assertTrue(Angles.normalizeRelative(Double.POSITIVE_INFINITY).isNaN())
    }

    @Test
    fun fastAbsoluteNormalizationMatchesModuloDefinition() {
        for (step in -400..400) {
            val angle = step * Angles.PI / 37.0
            assertEquals(absoluteByModulo(angle), Angles.normalizeAbsolute(angle), 1e-12)
        }
        for (boundary in doubleArrayOf(-Angles.TWO_PI, 0.0, Angles.TWO_PI, 2.0 * Angles.TWO_PI)) {
            for (angle in doubleArrayOf(Math.nextDown(boundary), boundary, Math.nextUp(boundary))) {
                assertEquals(absoluteByModulo(angle), Angles.normalizeAbsolute(angle), 1e-12)
            }
        }
        assertEquals(0.0, Angles.normalizeAbsolute(Angles.TWO_PI))
        assertEquals(
            java.lang.Double.doubleToRawLongBits(-0.0),
            java.lang.Double.doubleToRawLongBits(Angles.normalizeAbsolute(-0.0)),
        )
        assertTrue(Angles.normalizeAbsolute(Double.NEGATIVE_INFINITY).isNaN())
    }

    private fun relativeByModulo(angle: Double): Double {
        var normalized = angle % Angles.TWO_PI
        if (normalized <= -Angles.PI) normalized += Angles.TWO_PI
        if (normalized > Angles.PI) normalized -= Angles.TWO_PI
        return normalized
    }

    private fun absoluteByModulo(angle: Double): Double {
        var normalized = angle % Angles.TWO_PI
        if (normalized < 0.0) normalized += Angles.TWO_PI
        return normalized
    }
}
