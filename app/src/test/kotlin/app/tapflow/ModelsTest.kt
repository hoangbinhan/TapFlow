package app.tapflow

import app.tapflow.model.Limits
import app.tapflow.model.Target
import app.tapflow.model.TargetKind
import app.tapflow.model.labels
import app.tapflow.model.normalizedSteps
import app.tapflow.model.steps
import app.tapflow.model.withClone
import app.tapflow.service.packStrokes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelsTest {
    private fun tap(id: Long, step: Int, taps: Int = 1) = Target(id, TargetKind.TAP, 0.5f, 0.5f, step = step, taps = taps)

    @Test
    fun stepsGroupSharedNumbersAndKeepListOrderForTies() {
        val list = listOf(tap(1, 2), tap(2, 1), tap(3, 2), tap(4, 3))
        val groups = list.steps().map { g -> g.map { it.id } }
        assertEquals(listOf(listOf(2L), listOf(1L, 3L), listOf(4L)), groups)
    }

    @Test
    fun labelsUseDenseRankAndLettersOnlyForSharedSteps() {
        val list = listOf(tap(1, 5), tap(2, 9), tap(3, 9))
        val l = list.labels()
        assertEquals("1", l.getValue(1).text())
        assertEquals("2a", l.getValue(2).text())
        assertEquals("2b", l.getValue(3).text())
    }

    @Test
    fun normalizedStepsClosesGapsAndKeepsGrouping() {
        val list = listOf(tap(1, 4), tap(2, 10), tap(3, 10)).normalizedSteps()
        assertEquals(listOf(1, 2, 2), list.map { it.step })
    }

    @Test
    fun sanitizedClampsEverythingUserEditable() {
        val t = Target(1, TargetKind.TAP, -3f, Float.NaN, durationMs = 10_000_000, step = 0, taps = 9, tapGapMs = 1, delayAfterMs = 1)
            .sanitized()
        assertEquals(0f, t.x, 0f)
        assertEquals(0.5f, t.y, 0f)
        assertEquals(Limits.MAX_HOLD_MS, t.durationMs)
        assertEquals(1, t.step)
        assertEquals(Limits.MAX_TAPS, t.taps)
        assertEquals(Limits.MIN_TAP_GAP_MS, t.tapGapMs)
        assertEquals(Limits.MIN_INTERVAL_MS, t.delayAfterMs)
    }

    @Test
    fun swipeNeverRepeats() {
        val s = Target(1, TargetKind.SWIPE, 0.1f, 0.1f, 0.9f, 0.9f, taps = 3).sanitized()
        assertEquals(1, s.taps)
    }

    @Test
    fun packKeepsOneTargetsStrokesTogether() {
        // Three targets of 3 strokes each with room for 5: never split a target across gestures.
        val packed = packStrokes(listOf(listOf(1, 2, 3), listOf(4, 5, 6), listOf(7, 8, 9)), 5)
        assertEquals(listOf(listOf(1, 2, 3), listOf(4, 5, 6), listOf(7, 8, 9)), packed)
    }

    @Test
    fun packFillsGesturesAndSplitsOnlyAnOversizedTarget() {
        val packed = packStrokes(listOf(listOf(1), listOf(2), listOf(3, 4, 5, 6), listOf(7)), 2)
        assertEquals(listOf(listOf(1, 2), listOf(3, 4), listOf(5, 6), listOf(7)), packed)
        assertTrue(packStrokes(emptyList<List<Int>>(), 3).isEmpty())
    }

    @Test
    fun cloneRunsTogetherRightAfterTheOriginal() {
        val list = listOf(tap(1, 1), tap(2, 2, taps = 2), tap(3, 3))
        val (out, copy) = list.withClone(2, 99, 0.1f)!!
        assertEquals(listOf(1L, 2L, 99L, 3L), out.map { it.id })
        assertEquals(2, copy.step)
        assertEquals(2, copy.taps)
        assertEquals(0.6f, copy.x, 1e-6f)
        assertEquals("2a", out.labels().getValue(2).text())
        assertEquals("2b", out.labels().getValue(99).text())
    }

    @Test
    fun cloneFlipsAtTheRightEdgeAndShiftsBothSwipeEnds() {
        val swipe = Target(1, TargetKind.SWIPE, 0.8f, 0.3f, 0.95f, 0.3f, step = 1)
        val copy = listOf(swipe).withClone(1, 2, 0.1f)!!.second
        assertEquals(0.7f, copy.x, 1e-6f)
        assertEquals(0.85f, copy.x2, 1e-6f)
        assertEquals(0.3f, copy.y, 0f)
    }

    @Test
    fun cloneRefusesUnknownIdAndFullList() {
        assertEquals(null, listOf(tap(1, 1)).withClone(7, 9, 0.1f))
        val full = (1L..Limits.MAX_TARGETS).map { tap(it, it.toInt()) }
        assertEquals(null, full.withClone(1, 999, 0.1f))
    }
}
