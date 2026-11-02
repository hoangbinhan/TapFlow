package app.tapflow

import app.tapflow.data.InvalidScriptException
import app.tapflow.data.ScriptJson
import app.tapflow.model.RunConfig
import app.tapflow.model.Script
import app.tapflow.model.StopMode
import app.tapflow.model.Target
import app.tapflow.model.TargetKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ScriptJsonTest {
    private var next = 100L
    private val newId = { "id" + next++ }
    private val newTargetId = { next++ }

    @Test
    fun roundTripKeepsStepsTapsAndConfig() {
        val script = Script(
            "a", "Farm",
            listOf(
                Target(1, TargetKind.TAP, 0.25f, 0.75f, step = 2, taps = 2, tapGapMs = 80, durationMs = 40),
                Target(2, TargetKind.SWIPE, 0.1f, 0.2f, 0.9f, 0.8f, step = 2, delayAfterMs = 500),
            ),
            RunConfig(intervalMs = 750, stopMode = StopMode.COUNT, stopCount = 7, startDelaySec = 1),
            123L,
        )
        val back = ScriptJson.parseExport(ScriptJson.export(listOf(script)), newId, newTargetId).single()
        assertEquals(script.config, back.config)
        assertEquals(listOf(2, 2), back.targets.map { it.step })
        assertEquals(2, back.targets[0].taps)
        assertEquals(80L, back.targets[0].tapGapMs)
        assertEquals(500L, back.targets[1].delayAfterMs)
        assertEquals(0.9f, back.targets[1].x2, 1e-6f)
    }

    @Test
    fun legacyFilesWithoutStepRunInListOrder() {
        val json = """{"app":"tapflow","version":1,"scripts":[{"name":"old","targets":[
            {"kind":"TAP","x":0.1,"y":0.1},{"kind":"TAP","x":0.2,"y":0.2},{"kind":"TAP","x":0.3,"y":0.3}]}]}"""
        val s = ScriptJson.parseExport(json, newId, newTargetId).single()
        assertEquals(listOf(1, 2, 3), s.targets.map { it.step })
        assertEquals(listOf(1, 1, 1), s.targets.map { it.taps })
    }

    @Test
    fun rejectsForeignAndBrokenFiles() {
        listOf("not json", """{"app":"other","version":1,"scripts":[]}""", """{"app":"tapflow","version":99,"scripts":[{}]}""").forEach {
            assertThrows(InvalidScriptException::class.java) { ScriptJson.parseExport(it, newId, newTargetId) }
        }
    }

    @Test
    fun outOfRangeValuesAreClampedNotTrusted() {
        val json = """{"app":"tapflow","version":1,"scripts":[{"name":"x","config":{"intervalMs":1,"timeJitterPct":900},
            "targets":[{"kind":"TAP","x":7,"y":-1,"durationMs":99999999,"taps":50}]}]}"""
        val s = ScriptJson.parseExport(json, newId, newTargetId).single()
        assertEquals(1f, s.targets[0].x, 0f)
        assertEquals(0f, s.targets[0].y, 0f)
        assertEquals(3, s.targets[0].taps)
        assertEquals(20L, s.config.intervalMs)
        assertEquals(50, s.config.timeJitterPct)
    }
}
