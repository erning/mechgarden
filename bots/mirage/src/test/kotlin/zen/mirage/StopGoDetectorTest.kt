package zen.mirage

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StopGoDetectorTest {
    @Test
    fun classifiesRegularStopAndGoEpisodes() {
        val detector = StopGoDetector()

        repeat(2) {
            observeQualifyingRound(detector)
            detector.beginRound()
            assertFalse(detector.likely)
        }
        observeQualifyingRound(detector)
        detector.beginRound()

        assertTrue(detector.likely)
        assertTrue(detector.debugSummary().contains("stopGo=likely/3"))
    }

    @Test
    fun rejectsTwoTicksOfAverageLowSpeedDwell() {
        val detector = StopGoDetector()

        observeEpisodes(detector, regularStarts(), dwellTicks = 2)
        detector.beginRound()
        observeEpisodes(detector, regularStarts(), dwellTicks = 2)
        detector.beginRound()

        assertFalse(detector.likely)
    }

    @Test
    fun rejectsIrregularStopIntervals() {
        val detector = StopGoDetector()

        repeat(2) {
            observeEpisodes(detector, longArrayOf(10, 15, 74, 79, 138, 143, 202, 207, 266, 271, 330), dwellTicks = 3)
            detector.beginRound()
        }

        assertFalse(detector.likely)
        assertTrue(detector.debugSummary().contains("stopGo=open/0"))
    }

    @Test
    fun rejectsShortPeriodicStopsFromOrdinaryWaveSurfing() {
        val detector = StopGoDetector()
        val shortPeriodStarts = LongArray(11) { 10L + it * 20L }

        repeat(3) {
            observeEpisodes(detector, shortPeriodStarts, dwellTicks = 3)
            detector.beginRound()
        }

        assertFalse(detector.likely)
        assertTrue(detector.debugSummary().contains("stopGo=open/0"))
    }

    @Test
    fun acceptsTheObservedLowerBoundOfTheLongPeriodBand() {
        val detector = StopGoDetector()
        val borderlineLongPeriodStarts = LongArray(11) { 10L + it * 27L }

        repeat(3) {
            observeEpisodes(detector, borderlineLongPeriodStarts, dwellTicks = 3)
            detector.beginRound()
        }

        assertTrue(detector.likely)
    }

    @Test
    fun rejectsIntervalsAboveTheLongPeriodBand() {
        val detector = StopGoDetector()
        val overlyLongPeriodStarts = LongArray(11) { 10L + it * 46L }

        repeat(3) {
            observeEpisodes(detector, overlyLongPeriodStarts, dwellTicks = 3)
            detector.beginRound()
        }

        assertFalse(detector.likely)
    }

    @Test
    fun radarGapDoesNotCompleteOrConnectStopEpisodes() {
        val detector = StopGoDetector()

        detector.observe(0, 8.0)
        detector.observe(1, 0.0)
        detector.observe(10, 8.0)
        detector.observe(20, 0.0)

        assertTrue(detector.debugSummary().contains("episodes=0"))
        assertTrue(detector.debugSummary().contains("intervals=0"))
    }

    @Test
    fun lowSpeedEpisodesMustFirstBeArmedByHighSpeed() {
        val detector = StopGoDetector()
        var time = 0L
        repeat(5) {
            detector.observe(time, 3.0)
            detector.observe(time + 1, 0.0)
            detector.observe(time + 3, 3.0)
            time += 20
        }

        assertFalse(detector.likely)
        assertTrue(detector.debugSummary().contains("episodes=0"))
    }

    @Test
    fun beginRoundClearsSamplesWithoutImmediatelyDroppingClassification() {
        val detector = StopGoDetector()
        repeat(3) {
            observeQualifyingRound(detector)
            detector.beginRound()
        }
        assertTrue(detector.likely)

        assertTrue(detector.likely)
        assertTrue(detector.debugSummary().contains("episodes=0 dwell=n/a intervals=0"))
        detector.observe(0, 8.0)
    }

    @Test
    fun classificationRecoversAfterThreeNonQualifyingRounds() {
        val detector = StopGoDetector()
        repeat(3) {
            observeQualifyingRound(detector)
            detector.beginRound()
        }
        assertTrue(detector.likely)

        repeat(3) { detector.beginRound() }

        assertFalse(detector.likely)
        assertTrue(detector.debugSummary().contains("stopGo=open/"))
    }

    @Test
    fun forgetsQualifyingRoundsOutsideTheFiveRoundWindow() {
        val detector = StopGoDetector()
        observeQualifyingRound(detector)
        detector.beginRound()

        repeat(5) { detector.beginRound() }

        observeQualifyingRound(detector)
        detector.beginRound()

        assertFalse(detector.likely)
        assertTrue(detector.debugSummary().contains("stopGo=open/1"))
    }

    private fun observeQualifyingRound(detector: StopGoDetector) {
        observeEpisodes(detector, regularStarts(), dwellTicks = 3)
    }

    private fun regularStarts(): LongArray = LongArray(11) { 10L + it * 30L }

    private fun observeEpisodes(
        detector: StopGoDetector,
        starts: LongArray,
        dwellTicks: Long,
    ) {
        val endTime = starts.last() + dwellTicks
        for (time in 0L..endTime) {
            val stopped = starts.any { start -> time >= start && time < start + dwellTicks }
            detector.observe(time, if (stopped) 0.0 else 8.0)
        }
    }
}
