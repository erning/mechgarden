package zen.proteus.radar

import robocode.AdvancedRobot
import zen.proteus.control.Controls
import zen.proteus.core.Angles
import kotlin.math.PI
import kotlin.math.sign

/**
 * 1v1 radar. Cold start: full-rate spin. On scan: infinity lock — overshoot past
 * the target so next tick's sweep crosses it again, giving one scan per tick.
 * On lost contact (skipped turns): keep sweeping in the last lock direction.
 */
internal class Radar(
    private val robot: AdvancedRobot,
) {
    private var sweepDirection = 1.0
    private var lastScanTime = Long.MIN_VALUE

    fun onRoundStart() {
        sweepDirection = 1.0
        lastScanTime = Long.MIN_VALUE
        robot.setTurnRadarRightRadians(Double.POSITIVE_INFINITY)
    }

    fun onScan(
        absoluteBearingRadians: Double,
        controls: Controls,
    ) {
        lastScanTime = robot.time
        val turn = Angles.normalizeRelative(absoluteBearingRadians - robot.radarHeadingRadians)
        val direction = if (turn == 0.0) sweepDirection else sign(turn)
        sweepDirection = direction
        controls.radarTurnRadians = turn + direction * OVERSHOOT_RADIANS
    }

    /** Called on ticks without a scan: keep sweeping to reacquire. */
    fun search(controls: Controls) {
        if (robot.time - lastScanTime > LOST_TICKS) {
            controls.radarTurnRadians = Double.POSITIVE_INFINITY * sweepDirection
        }
    }

    private companion object {
        const val OVERSHOOT_RADIANS = PI / 32.0
        const val LOST_TICKS = 2L
    }
}
