package zen.mirage

import robocode.AdvancedRobot
import robocode.Rules
import robocode.ScannedRobotEvent

/**
 * Radar layer — owns the whole radar lifecycle: the fast cold-start search, the
 * target lock, and reacquire. It only keeps the beam on the enemy; while locked
 * that yields a fresh scan every tick, and other layers collect enemy data from
 * those events themselves rather than from the radar. The robot drives the radar
 * with three calls. Aiming, firing, tracking, and movement stay in their own
 * layers. Inherited verbatim from Mirage (see bots/mirage docs/radar.md).
 *
 * Lifecycle:
 *  - [beginRound] once at round start: spin the body, gun, and radar together
 *    toward the field center (~75°/tick) to find the enemy fast. The adjust flags
 *    are left coupled so the three rates stack; [onScan] decouples them once
 *    locked.
 *  - [update] every tick from the run loop: re-search if the lock has gone stale.
 *  - [onScan] for every [ScannedRobotEvent]: lock onto the enemy and remember its
 *    bearing. During the one cold-start recovery tick the radar commandeers the
 *    body and gun, and [state] reads [State.ACQUIRING]; the caller must leave the
 *    gun alone until [state] is [State.LOCKED] again.
 */
class Radar(
    private val bot: AdvancedRobot,
) {
    private var lastScanTime = 0L
    private var locked = false
    private var coupledLockPending = false
    private var lastBearingRadians = Double.NaN
    private var lastBearingTime = -1L

    /** Phase of the find/lock/reacquire lifecycle. */
    enum class State {
        SEARCHING,
        ACQUIRING,
        LOCKED,
        REACQUIRING,
    }

    val state: State
        get() =
            when {
                !locked -> State.SEARCHING
                coupledLockPending -> State.ACQUIRING
                bot.time - lastScanTime > REACQUIRE_TICKS -> State.REACQUIRING
                else -> State.LOCKED
            }

    /** Start the opening search: stack body, gun, and radar toward field center. */
    fun beginRound() {
        val centerBearing =
            Angles.absoluteBearing(bot.x, bot.y, bot.battleFieldWidth / 2.0, bot.battleFieldHeight / 2.0)
        val angle =
            if (Angles.normalizeRelative(centerBearing - bot.radarHeadingRadians) >= 0.0) {
                Double.POSITIVE_INFINITY
            } else {
                Double.NEGATIVE_INFINITY
            }
        bot.setTurnRightRadians(angle)
        bot.setTurnGunRightRadians(angle)
        bot.setTurnRadarRightRadians(angle)
    }

    /** Per-tick upkeep: if the lock has gone stale, spin the beam to reacquire. */
    fun update() {
        if (locked && bot.time - lastScanTime > REACQUIRE_TICKS) search()
    }

    fun onScan(e: ScannedRobotEvent) {
        lastScanTime = bot.time
        val absBearing = Angles.normalizeAbsolute(bot.headingRadians + e.bearingRadians)

        if (!locked) {
            locked = true
            val offset = Angles.normalizeRelative(absBearing - bot.radarHeadingRadians)
            if (Math.abs(offset) > Rules.RADAR_TURN_RATE_RADIANS) {
                coupledLockPending = true
                bot.setTurnRightRadians(offset)
                bot.setTurnGunRightRadians(offset)
                lock(absBearing)
                return
            }
            decouple()
        } else if (coupledLockPending) {
            coupledLockPending = false
            decouple()
        }

        lock(absBearing)
    }

    /** Stop the opening body spin and decouple the gun and radar for a stable lock. */
    private fun decouple() {
        bot.setTurnRightRadians(0.0)
        bot.isAdjustGunForRobotTurn = true
        bot.isAdjustRadarForGunTurn = true
    }

    /** Lock the beam onto [absBearingRadians], leading by the bearing rate. */
    private fun lock(absBearingRadians: Double) {
        val offset = Angles.normalizeRelative(absBearingRadians - bot.radarHeadingRadians)
        val time = bot.time
        val lead: Double =
            if (lastBearingRadians.isNaN() || time <= lastBearingTime) {
                Math.abs(offset)
            } else {
                val dt = (time - lastBearingTime).toDouble()
                val bearingRate = Angles.normalizeRelative(absBearingRadians - lastBearingRadians) / dt
                Math.min(Math.abs(bearingRate) + SAFETY_MARGIN, MAX_LEAD)
            }
        val direction = if (offset >= 0.0) 1.0 else -1.0
        bot.setTurnRadarRightRadians(offset + direction * lead)
        lastBearingRadians = absBearingRadians
        lastBearingTime = time
    }

    private fun search() {
        bot.setTurnRadarRightRadians(Double.POSITIVE_INFINITY)
        lastBearingRadians = Double.NaN
        lastBearingTime = -1L
    }

    private companion object {
        const val REACQUIRE_TICKS = 1L
        val SAFETY_MARGIN = Math.toRadians(15.0)
        val MAX_LEAD = Rules.RADAR_TURN_RATE_RADIANS
    }
}
