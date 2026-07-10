package zen.mirage

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AntiRamPlannerTest {
    @Test
    fun staysInactiveWithoutThreatOrCollision() {
        val planner = AntiRamPlanner()

        val plan = planner.plan(frame(), inactiveThreat(), FIELD_WIDTH, FIELD_HEIGHT)

        assertNull(plan)
    }

    @Test
    fun choosesTheHigherScoringEscapeDirection() {
        val planner = AntiRamPlanner()
        val frame = frame(selfX = 55.0, selfY = 140.0, enemyX = 120.0, enemyY = 140.0)

        val plan = assertNotNull(planner.plan(frame, activeThreat(), FIELD_WIDTH, FIELD_HEIGHT))
        val chosen = planner.escapeScore(frame, plan.preferredDirection, FIELD_WIDTH, FIELD_HEIGHT)
        val rejected = planner.escapeScore(frame, -plan.preferredDirection, FIELD_WIDTH, FIELD_HEIGHT)

        assertTrue(chosen >= rejected)
        assertEquals(540.0, plan.targetRange)
        assertTrue(plan.directionPenalty > 0.0)
        assertNotNull(plan.escapeHeadingRadians)
    }

    @Test
    fun collisionArmsEmergencyEscapeWithoutFreshPursuitEvidence() {
        val planner = AntiRamPlanner()
        planner.recordCollision()

        val plan = assertNotNull(planner.plan(frame(), inactiveThreat(), FIELD_WIDTH, FIELD_HEIGHT))

        assertTrue(plan.emergency)
        assertNotNull(plan.escapeHeadingRadians)
    }

    @Test
    fun ignoresDistantPursuitUntilCollisionIsImminent() {
        val planner = AntiRamPlanner()
        val distantFrame = frame(enemyY = 600.0)
        val distantThreat = activeThreat().copy(collisionTicks = 60.0)

        assertNull(planner.plan(distantFrame, distantThreat, FIELD_WIDTH, FIELD_HEIGHT))
    }

    private fun activeThreat() =
        RamThreatDetector.Snapshot(
            active = true,
            confidence = 0.7,
            collisionTicks = 8.0,
            pursuitHeadingRadians = Angles.PI,
        )

    private fun inactiveThreat() =
        RamThreatDetector.Snapshot(
            active = false,
            confidence = 0.0,
            collisionTicks = Double.POSITIVE_INFINITY,
            pursuitHeadingRadians = Double.NaN,
        )

    private fun frame(
        selfX: Double = 400.0,
        selfY: Double = 300.0,
        enemyX: Double = 400.0,
        enemyY: Double = 380.0,
    ): Tracker.Frame {
        val absoluteBearingRadians = Angles.absoluteBearing(selfX, selfY, enemyX, enemyY)
        val distance = kotlin.math.hypot(enemyX - selfX, enemyY - selfY)
        return Tracker.Frame(
            self = RobotState(1L, selfX, selfY, 100.0, 8.0, 0.0, 0.0, 0.0, 0.0),
            enemy =
                EnemyState(
                    time = 1L,
                    x = enemyX,
                    y = enemyY,
                    energy = 100.0,
                    velocity = 8.0,
                    headingRadians = Angles.normalizeAbsolute(absoluteBearingRadians + Angles.PI),
                    absoluteBearingRadians = absoluteBearingRadians,
                    distance = distance,
                ),
            derived = null,
        )
    }

    private companion object {
        const val FIELD_WIDTH = 800.0
        const val FIELD_HEIGHT = 600.0
    }
}
