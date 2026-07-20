package zen.proteus.knn

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KnnModelTest {
    private val weights = DoubleArray(2) { 1.0 }

    private fun entry(
        x: Double,
        gfLo: Double,
        gfHi: Double,
        didHit: Boolean = false,
    ) = KnnModel.Entry(doubleArrayOf(x, 0.0), gfLo, gfHi, didHit)

    @Test
    fun `peak sits near the neighbors most similar to the query`() {
        val model = KnnModel(100, weights)
        // Situations far from the query (x ~ 0.9) land at -0.8; near ones at +0.6.
        repeat(30) { model.add(entry(0.9 + it * 0.001, -0.85, -0.75)) }
        repeat(30) { model.add(entry(0.1 + it * 0.001, 0.55, 0.65)) }
        val aim = model.aimGuessFactor(doubleArrayOf(0.1, 0.0), includeDidHit = true)
        assertEquals(0.6, aim, 0.15)
    }

    @Test
    fun `anti-surfer query excludes didHit samples`() {
        val model = KnnModel(100, weights)
        repeat(30) { model.add(entry(0.1 + it * 0.001, 0.55, 0.65, didHit = true)) }
        val aim = model.aimGuessFactor(doubleArrayOf(0.1, 0.0), includeDidHit = false)
        assertEquals(0.0, aim, 1e-12)
    }

    @Test
    fun `empty model aims straight`() {
        assertEquals(0.0, KnnModel(100, weights).aimGuessFactor(doubleArrayOf(0.5, 0.5), true), 1e-12)
    }

    @Test
    fun `capacity evicts oldest entries`() {
        val model = KnnModel(10, weights)
        repeat(10) { model.add(entry(0.9, -0.85, -0.75)) }
        repeat(10) { model.add(entry(0.1, 0.55, 0.65)) }
        assertEquals(10, model.size)
        assertTrue(model.aimGuessFactor(doubleArrayOf(0.0, 0.0), true) > 0.3)
    }
}
