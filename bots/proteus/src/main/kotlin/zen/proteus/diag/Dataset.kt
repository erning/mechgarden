package zen.proteus.diag

import robocode.AdvancedRobot
import robocode.RobocodeFileOutputStream
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.IOException

/**
 * Offline-training dataset export (development tool, NOT robot persistence):
 * buffers (features, gfLo, gfHi) samples in memory per enemy and flushes them
 * as little-endian float32 records to the robot's data directory at battle end,
 * so the M8 trainer can learn the KNN embedding offline. Disabled by default;
 * flip [ENABLED] locally for training duels and never commit `true` (see
 * bots/proteus/docs/training.md).
 *
 * Buffering exists because the engine allows only 5 concurrently open streams
 * and robots are rebuilt per round — a long-lived open stream per round kills
 * the robot mid-battle. Format: magic "PGF1", int32 feature count, then per
 * sample featureCount+2 float32s (features..., gfLo, gfHi).
 */
internal object Dataset {
    const val ENABLED = false

    // 52 bytes per record against the engine's 200KB data quota.
    private const val MAX_RECORDS = 3_000
    private const val MAGIC = 0x50474631 // "PGF1"

    private val buffers = HashMap<String, ArrayList<FloatArray>>()

    fun write(
        enemyName: String,
        features: DoubleArray,
        gfLo: Double,
        gfHi: Double,
    ) {
        if (!ENABLED) return
        val buffer = buffers.getOrPut(enemyName) { ArrayList() }
        if (buffer.size >= MAX_RECORDS) return
        val record = FloatArray(features.size + 2)
        for (i in features.indices) record[i] = features[i].toFloat()
        record[features.size] = gfLo.toFloat()
        record[features.size + 1] = gfHi.toFloat()
        buffer.add(record)
    }

    /** Flushes every buffered enemy dataset and clears the buffers. */
    fun flushAll(robot: AdvancedRobot) {
        if (!ENABLED) return
        for ((enemyName, records) in buffers) {
            if (records.isEmpty()) continue
            try {
                val sanitized = enemyName.replace(Regex("[^A-Za-z0-9_.-]"), "_")
                val featureCount = records[0].size - 2
                // The engine's security policy requires this exact stream type.
                DataOutputStream(BufferedOutputStream(RobocodeFileOutputStream(robot.getDataFile("$sanitized.pgf"))))
                    .use { out ->
                        out.writeInt(MAGIC)
                        out.writeInt(featureCount)
                        for (record in records) {
                            for (value in record) out.writeFloat(value)
                        }
                    }
            } catch (e: IOException) {
                // Quota exhausted; whatever was written stands, keep the robot alive.
            }
        }
        buffers.clear()
    }
}
