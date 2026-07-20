package zen.proteus.train

import java.io.DataInputStream
import java.io.File
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max

/**
 * Offline KNN-embedding trainer (M8). Reads .pgf datasets written by the
 * robot's Dataset exporter (see scripts/collect_dataset.py) and learns the
 * per-dimension embedding weights by stochastic gradient descent on the
 * KNN kernel-density cross-entropy, following the same method as the published
 * BeepBoop workflow: neighbors are found with the current weights, the loss is
 * optimized against those fixed neighbor sets, and the neighbor sets are
 * refreshed every few epochs (the second-order effect of weights changing the
 * neighbor sets is ignored).
 *
 * Run: ./gradlew :bots:proteus:trainEmbedding --args="[datasetDir] [--epochs N] [--lr X]"
 */
object EmbeddingTrainer {
    private const val MAGIC = 0x50474631 // "PGF1"
    private const val K = 30
    private const val TEMPERATURE = 1.0
    private const val KERNEL_HALF_WIDTH = 0.1
    private const val GRADIENT_CLAMP = 1.0
    private const val EPS = 1e-9
    private const val REFRESH_EVERY = 2

    // Current hand-tuned weights (zen.proteus.aim.Features.WEIGHTS) as the init.
    private val HAND_WEIGHTS =
        doubleArrayOf(3.0, 3.0, 2.0, 2.0, 1.0, 1.0, 1.0, 2.0, 2.0, 1.0, 1.0)

    class Sample(
        val features: DoubleArray,
        val gf: Double,
    )

    private class Neighbor(
        val distance: Double,
        val gf: Double,
        val features: DoubleArray,
    )

    @JvmStatic
    fun main(args: Array<String>) {
        val positional = ArrayList<String>()
        var i = 0
        while (i < args.size) {
            if (args[i].startsWith("--")) {
                i += 2
            } else {
                positional.add(args[i])
                i++
            }
        }
        val datasetDir = File(positional.firstOrNull() ?: ".cache/proteus-datasets")
        val epochs = argValue(args, "--epochs")?.toIntOrNull() ?: 12
        val lr = argValue(args, "--lr")?.toDoubleOrNull() ?: 1.0

        val samples = load(datasetDir)
        println("loaded ${samples.size} samples from $datasetDir")
        if (samples.isEmpty()) return

        val weights = HAND_WEIGHTS.copyOf()
        val random = java.util.Random(42)
        println("epoch 0 loss ${"%.6f".format(loss(samples, findNeighbors(samples, weights, K)))}")

        for (epoch in 1..epochs) {
            val neighbors = findNeighbors(samples, weights, K)
            val gradients = DoubleArray(weights.size)
            var used = 0
            for (i in samples.indices) {
                if (accumulate(samples[i], neighbors[i], weights, gradients)) used++
            }
            for (d in gradients.indices) {
                gradients[d] = (gradients[d] / max(1, used)).coerceIn(-GRADIENT_CLAMP, GRADIENT_CLAMP)
                weights[d] *= exp(-lr * gradients[d])
            }
            // The embedding is scale-free (absolute scale trades off with the
            // softmax temperature); pin the sum to stop global drift.
            val sum = weights.sum()
            for (d in weights.indices) weights[d] *= weights.size / sum
            val epochLoss = loss(samples, findNeighbors(samples, weights, K))
            println("epoch $epoch loss ${"%.6f".format(epochLoss)} (gradients from $used samples)")
        }
        val loss = loss(samples, findNeighbors(samples, weights, K))
        val initialLoss = loss(samples, findNeighbors(samples, HAND_WEIGHTS.copyOf(), K))

        println()
        println("// Learned by EmbeddingTrainer (loss ${"%.4f".format(initialLoss)} -> ${"%.4f".format(loss)})")
        println("val WEIGHTS =")
        println(
            weights.joinToString(",\n    ", "    doubleArrayOf(\n    ", ",\n)") { "%.4f".format(it) },
        )
    }

    private fun argValue(
        args: Array<String>,
        name: String,
    ): String? {
        val index = args.indexOf(name)
        return if (index >= 0 && index + 1 < args.size) args[index + 1] else null
    }

    private fun load(dir: File): List<Sample> {
        val samples = ArrayList<Sample>()
        for (file in dir.listFiles { f -> f.extension == "pgf" } ?: emptyArray()) {
            DataInputStream(file.inputStream().buffered()).use { input ->
                if (input.readInt() != MAGIC) return@use
                val featureCount = input.readInt()
                while (input.available() >= (featureCount + 2) * 4) {
                    val features = DoubleArray(featureCount) { input.readFloat().toDouble() }
                    val gfLo = input.readFloat().toDouble()
                    val gfHi = input.readFloat().toDouble()
                    samples.add(Sample(features, (gfLo + gfHi) / 2.0))
                }
            }
            println("  ${file.name}")
        }
        return samples
    }

    /** The k nearest samples per sample by weighted Manhattan distance. */
    private fun findNeighbors(
        samples: List<Sample>,
        weights: DoubleArray,
        k: Int,
    ): Array<List<Neighbor>> {
        val result = ArrayList<List<Neighbor>>(samples.size)
        val distances = DoubleArray(samples.size)
        for (i in samples.indices) {
            val a = samples[i].features
            for (j in samples.indices) {
                if (i == j) {
                    distances[j] = Double.POSITIVE_INFINITY
                    continue
                }
                val b = samples[j].features
                var distance = 0.0
                for (d in a.indices) {
                    distance += weights[d] * abs(a[d] - b[d])
                }
                distances[j] = distance
            }
            val order = distances.indices.sortedBy { distances[it] }
            result.add(order.take(k).map { Neighbor(distances[it], samples[it].gf, samples[it].features) })
        }
        return result.toTypedArray()
    }

    /** Accumulates one sample's gradient into [gradients]; returns false when
     *  the sample has no kernel hits and contributes nothing. */
    private fun accumulate(
        sample: Sample,
        neighbors: List<Neighbor>,
        weights: DoubleArray,
        gradients: DoubleArray,
    ): Boolean {
        // alpha = softmax(-distance / T); B = kernel hit of the true gf.
        var maxLogit = Double.NEGATIVE_INFINITY
        val logits = DoubleArray(neighbors.size)
        for (j in neighbors.indices) {
            logits[j] = -neighbors[j].distance / TEMPERATURE
            if (logits[j] > maxLogit) maxLogit = logits[j]
        }
        var sumExp = 0.0
        for (j in logits.indices) {
            logits[j] = exp(logits[j] - maxLogit)
            sumExp += logits[j]
        }
        val alpha = DoubleArray(neighbors.size) { logits[it] / sumExp }

        val hits =
            BooleanArray(neighbors.size) {
                abs(neighbors[it].gf - sample.gf) < KERNEL_HALF_WIDTH
            }
        var density = 0.0
        for (j in hits.indices) if (hits[j]) density += alpha[j]
        if (density <= 0.0) return false

        // dL/dlogit_j = alpha_j * (1 - B_j / density), then chain through
        // logit_j = -distance_j / T and the Manhattan distance.
        for (d in weights.indices) {
            var gradient = 0.0
            for (j in neighbors.indices) {
                val bOverDensity = if (hits[j]) 1.0 / (density + EPS) else 0.0
                gradient += alpha[j] * (1.0 - bOverDensity) * (-abs(sample.features[d] - neighbors[j].features[d]) / TEMPERATURE)
            }
            gradients[d] += gradient
        }
        return true
    }

    private fun loss(
        samples: List<Sample>,
        neighbors: Array<List<Neighbor>>,
    ): Double {
        var total = 0.0
        for (i in samples.indices) {
            var maxLogit = Double.NEGATIVE_INFINITY
            for (n in neighbors[i]) if (-n.distance / TEMPERATURE > maxLogit) maxLogit = -n.distance / TEMPERATURE
            var sumExp = 0.0
            var density = 0.0
            for (n in neighbors[i]) {
                val weight = exp(-n.distance / TEMPERATURE - maxLogit)
                sumExp += weight
                if (abs(n.gf - samples[i].gf) < KERNEL_HALF_WIDTH) density += weight
            }
            total += -ln(density / sumExp + EPS)
        }
        return total / samples.size
    }
}
