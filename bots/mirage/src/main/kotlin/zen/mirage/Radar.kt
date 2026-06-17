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
 * layers.
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
 *
 * Lock: point at the enemy and overshoot past it on the side the beam is
 * approaching from (the sign of the offset, which self-corrects so the beam
 * wobbles across the target instead of running away). The overshoot magnitude
 * scales with the measured per-tick bearing rate plus a safety margin, so faster
 * angular motion (close passes, ramming) gets a wider lead and the swept arc still
 * covers the enemy next tick where a fixed 2x overshoot would drop a frame. With
 * no usable history (first contact, or just-reacquired) the lead falls back to
 * |offset|, reproducing the plain 2x overshoot.
 *
 * First contact has to undo the same fast sweep: the beam can overshoot the target
 * by more than a decoupled radar's 45°/tick reach. When the first-contact offset
 * exceeds the radar's own turn rate, the radar stays coupled for one recovery tick
 * and turns body, gun, and radar toward the target so their rates stack and swing
 * the beam back; otherwise it decouples immediately.
 *
 * All angles in radians. The radar touches the body and gun only during the
 * opening sweep and that one recovery tick to stack rotation rates; a future
 * movement layer must account for this opening hand-off.
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
        /** Opening sweep before first contact; no enemy data yet. */
        SEARCHING,

        /** First contact overshot wide; the beam is swinging back while the radar
         *  still steers the body and gun for one recovery tick. */
        ACQUIRING,

        /** Holding a fresh lock; the enemy is scanned every tick. */
        LOCKED,

        /** The enemy was seen but the lock went stale; the beam is re-searching. */
        REACQUIRING,
    }

    /** Current lifecycle phase, derived live from the existing state (no stored
     *  duplicate to keep in sync). */
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

    /**
     * Handle a scan: keep the lock on the enemy. On the cold-start recovery tick the
     * radar steers the body and gun and [state] reads [State.ACQUIRING]; the caller
     * must leave the gun alone until [state] is [State.LOCKED].
     */
    fun onScan(e: ScannedRobotEvent) {
        lastScanTime = bot.time
        val absBearing = Angles.normalizeAbsolute(bot.headingRadians + e.bearingRadians)

        if (!locked) {
            locked = true
            val offset = Angles.normalizeRelative(absBearing - bot.radarHeadingRadians)
            if (Math.abs(offset) > Rules.RADAR_TURN_RATE_RADIANS) {
                // The opening sweep overshot the target by more than a decoupled
                // radar can swing back in one tick. Stay coupled this tick and turn
                // body, gun, and radar toward it so their rates stack (~75°/tick).
                coupledLockPending = true
                bot.setTurnRightRadians(offset)
                bot.setTurnGunRightRadians(offset)
                lock(absBearing)
                return
            }
            decouple()
        } else if (coupledLockPending) {
            // Recovery tick done; settle into the decoupled stable lock.
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
                // No usable history: lead by |offset|, i.e. the plain 2x overshoot.
                Math.abs(offset)
            } else {
                val dt = (time - lastBearingTime).toDouble()
                val bearingRate = Angles.normalizeRelative(absBearingRadians - lastBearingRadians) / dt
                Math.min(Math.abs(bearingRate) + SAFETY_MARGIN, MAX_LEAD)
            }
        // Overshoot on the side the beam is approaching from so it wobbles across
        // the target and self-corrects, never running away with the drift.
        val direction = if (offset >= 0.0) 1.0 else -1.0
        bot.setTurnRadarRightRadians(offset + direction * lead)
        lastBearingRadians = absBearingRadians
        lastBearingTime = time
    }

    /** Spin the beam indefinitely to (re)acquire, dropping stale bearing history so
     *  the next lock re-seeds its rate from fresh scans. */
    private fun search() {
        bot.setTurnRadarRightRadians(Double.POSITIVE_INFINITY)
        lastBearingRadians = Double.NaN
        lastBearingTime = -1L
    }

    private companion object {
        /** Ticks without a fresh scan before the lock is treated as lost. */
        const val REACQUIRE_TICKS = 1L

        /** Extra lead beyond the measured per-tick drift, absorbing acceleration. */
        val SAFETY_MARGIN = Math.toRadians(15.0)

        /** Cap on the lead so it never demands more than the radar can deliver. */
        val MAX_LEAD = Rules.RADAR_TURN_RATE_RADIANS
    }
}
