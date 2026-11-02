package app.tapflow.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import app.tapflow.Session
import app.tapflow.model.Limits
import app.tapflow.model.RunConfig
import app.tapflow.model.RunState
import app.tapflow.model.StopMode
import app.tapflow.model.StopReason
import app.tapflow.model.Target
import app.tapflow.model.TargetKind
import app.tapflow.model.steps
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.math.abs
import kotlin.random.Random

/**
 * Runs the click loop. One gesture is in flight at a time: the next one is only sent after the
 * system reports the previous one as completed/cancelled, so gestures can never pile up.
 */
class ClickEngine(
    private val service: AccessibilityService,
    private val scope: CoroutineScope,
) {
    private var job: Job? = null
    private val handler = Handler(Looper.getMainLooper())
    private val power = service.getSystemService(PowerManager::class.java)

    /** Active (non-paused) run time carried over between pause/resume. */
    private var activeMs = 0L
    private var segmentStart = 0L

    /** Where a paused run continues: the step that was cut short (or the next one if it had finished). */
    private var resumeStep = 0

    /** Seconds of the start countdown still to wait; non-zero only when paused during the countdown. */
    private var countdownLeft = 0

    val isRunning: Boolean get() = job?.isActive == true

    /**
     * [minDelaySec] lets a caller that is about to hide the app guarantee some time to get out of the
     * way, whatever the configured start delay is.
     */
    fun start(minDelaySec: Int = 0) {
        if (isRunning) return
        val targets = Session.targets.value
        if (targets.isEmpty()) {
            Session.run.value = RunState.Finished(StopReason.NO_TARGETS)
            return
        }
        Session.stats.value = app.tapflow.model.Stats()
        activeMs = 0L
        resumeStep = 0
        countdownLeft = Session.config.value.sanitized().startDelaySec.coerceAtLeast(minDelaySec)
        launch(targets)
    }

    fun pause() {
        if (!Session.isActive) return
        if (Session.run.value is RunState.Running) activeMs += SystemClock.elapsedRealtime() - segmentStart
        job?.cancel()
        job = null
        Session.run.value = RunState.Paused
    }

    fun resume() {
        if (Session.run.value != RunState.Paused) return
        val targets = Session.targets.value
        if (targets.isEmpty()) {
            stop(StopReason.NO_TARGETS)
            return
        }
        launch(targets)
    }

    fun stop(reason: StopReason) {
        job?.cancel()
        job = null
        activeMs = 0L
        resumeStep = 0
        countdownLeft = 0
        Session.run.value = RunState.Finished(reason)
    }

    private fun launch(targets: List<Target>) {
        val cfg = Session.config.value.sanitized()
        job = scope.launch {
            while (countdownLeft > 0) {
                Session.run.value = RunState.Countdown(countdownLeft)
                delay(1000)
                countdownLeft--
            }
            Session.run.value = RunState.Running
            // The overlay turns its markers touch-through in response to this state. While paused (and right
            // after Start/Resume without a countdown) they still catch touches, so the first injected tap
            // would hit our own marker; the user's finger may also still be lifting off the button.
            // segmentStart must be set before suspending: pause() reads it as soon as the state is Running.
            segmentStart = SystemClock.elapsedRealtime()
            delay(SETTLE_MS)
            runLoop(cfg, targets)
        }
    }

    private suspend fun runLoop(cfg: RunConfig, targets: List<Target>) {
        var consecutiveFailures = 0
        var cycles = Session.stats.value.cycles
        val steps = targets.steps()
        // Targets can be deleted while paused, so the saved position may be past the end.
        var index = resumeStep.coerceIn(0, steps.size)
        while (true) {
            while (index < steps.size) {
                val group = steps[index]
                resumeStep = index
                if (!power.isInteractive) return finish(StopReason.SCREEN_OFF)
                val elapsed = activeMs + (SystemClock.elapsedRealtime() - segmentStart)
                if (cfg.stopMode == StopMode.DURATION && elapsed >= cfg.stopDurationSec * 1000L) {
                    return finish(StopReason.TIME_REACHED)
                }
                val done = perform(group, cfg)
                // The step finished: a pause during the wait below must not repeat it.
                resumeStep = index + 1
                val ok = done > 0
                consecutiveFailures = if (ok) 0 else consecutiveFailures + 1
                Session.stats.update {
                    it.copy(
                        actions = it.actions + done,
                        failures = it.failures + if (ok) 0 else 1,
                        elapsedMs = elapsed,
                    )
                }
                // Cancelled gestures are normal while the user also touches the screen; many in a
                // row mean the system is rejecting us (service revoked, secure window, ...).
                if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) return finish(StopReason.GESTURE_FAILED)
                val last = group.last()
                val base = if (last.delayAfterMs >= 0) last.delayAfterMs else cfg.intervalMs
                var wait = jittered(base, cfg.timeJitterPct)
                if (cfg.stopMode == StopMode.DURATION) {
                    // Don't sleep past the time limit: stop on time instead of up to one interval late.
                    val remaining = cfg.stopDurationSec * 1000L - (activeMs + SystemClock.elapsedRealtime() - segmentStart)
                    wait = wait.coerceAtMost(remaining.coerceAtLeast(0L))
                }
                delay(wait)
                index++
            }
            index = 0
            cycles++
            Session.stats.update { it.copy(cycles = cycles) }
            if (cfg.stopMode == StopMode.COUNT && cycles >= cfg.stopCount) return finish(StopReason.COUNT_REACHED)
        }
    }

    private fun finish(reason: StopReason) {
        activeMs = 0L
        Session.run.value = RunState.Finished(reason)
    }

    private fun jittered(base: Long, pct: Int): Long {
        val spread = base * pct / 100
        val v = if (spread > 0) base + Random.nextLong(-spread, spread + 1) else base
        return v.coerceAtLeast(Limits.MIN_INTERVAL_MS)
    }

    /**
     * Performs one step: every target in [group] together. Returns how many strokes (touches) were
     * delivered, or 0 if the system rejected or cancelled the gesture. A step with more strokes than
     * the device allows in one gesture is split into consecutive gestures, never in the middle of a
     * target (a double tap's second stroke must stay in the gesture that holds its first).
     */
    private suspend fun perform(group: List<Target>, cfg: RunConfig): Int {
        val perTarget = buildStrokes(group, cfg) ?: return 0
        val limit = GestureDescription.getMaxStrokeCount().coerceAtLeast(1)
        var delivered = 0
        for (chunk in packStrokes(perTarget, limit)) {
            val gesture = try {
                GestureDescription.Builder().also { b -> chunk.forEach { b.addStroke(it) } }.build()
            } catch (e: IllegalArgumentException) {
                return delivered
            }
            if (!dispatch(gesture, chunk)) return delivered
            delivered += chunk.size
        }
        return delivered
    }

    /** One list of strokes per target, in [group] order. */
    private fun buildStrokes(group: List<Target>, cfg: RunConfig): List<List<GestureDescription.StrokeDescription>>? {
        val s = service.screenSize()
        if (s.width <= 1 || s.height <= 1) return null
        val maxX = (s.width - 1).toFloat()
        val maxY = (s.height - 1).toFloat()
        val maxDuration = GestureDescription.getMaxGestureDuration()

        fun jx(v: Float) = (v * s.width + jitter(cfg.posJitterPx)).coerceIn(0f, maxX)
        fun jy(v: Float) = (v * s.height + jitter(cfg.posJitterPx)).coerceIn(0f, maxY)

        val out = ArrayList<List<GestureDescription.StrokeDescription>>()
        try {
            for (t in group) {
                val x1 = jx(t.x)
                val y1 = jy(t.y)
                val path = Path().apply { moveTo(x1, y1) }
                if (t.kind == TargetKind.SWIPE) {
                    var x2 = jx(t.x2)
                    val y2 = jy(t.y2)
                    // A zero-length swipe is rejected by some builds; nudge the end point by one pixel.
                    if (abs(x2 - x1) < 1f && abs(y2 - y1) < 1f) x2 = if (x1 + 1f <= maxX) x1 + 1f else x1 - 1f
                    path.lineTo(x2, y2)
                }
                val duration = t.durationMs.coerceIn(1L, maxDuration)
                val repeats = if (t.kind == TargetKind.TAP) t.taps else 1
                out += (0 until repeats).map { k ->
                    // Repeats are extra strokes at later start times inside the same gesture, so the gap is exact.
                    val start = (k * (duration + t.tapGapMs)).coerceAtMost((maxDuration - duration).coerceAtLeast(0L))
                    GestureDescription.StrokeDescription(Path(path), start, duration)
                }
            }
        } catch (e: IllegalArgumentException) {
            return null
        }
        return out
    }

    private fun jitter(px: Int): Int = if (px > 0) Random.nextInt(-px, px + 1) else 0

    private suspend fun dispatch(gesture: GestureDescription, strokes: List<GestureDescription.StrokeDescription>): Boolean {
        val length = strokes.maxOf { it.startTime + it.duration }
        val timeout = length + DISPATCH_GRACE_MS
        return withTimeoutOrNull(timeout) {
            suspendCancellableCoroutine { cont ->
                val done = AtomicBoolean(false)
                fun complete(ok: Boolean) {
                    if (done.compareAndSet(false, true) && cont.isActive) cont.resume(ok)
                }
                val callback = object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) = complete(true)
                    override fun onCancelled(gestureDescription: GestureDescription?) = complete(false)
                }
                val accepted = try {
                    service.dispatchGesture(gesture, callback, handler)
                } catch (e: RuntimeException) {
                    false
                }
                if (!accepted) complete(false)
            }
        } ?: false
    }

    private companion object {
        const val MAX_CONSECUTIVE_FAILURES = 10
        const val DISPATCH_GRACE_MS = 3000L
        const val SETTLE_MS = 300L
    }
}

/**
 * Packs per-target stroke lists into gestures of at most [limit] strokes. A target's strokes always
 * travel together unless that one target alone exceeds [limit], which is then split as a last resort.
 */
internal fun <T> packStrokes(perTarget: List<List<T>>, limit: Int): List<List<T>> {
    val out = ArrayList<List<T>>()
    var current = ArrayList<T>()
    for (strokes in perTarget) {
        if (strokes.size > limit) {
            if (current.isNotEmpty()) { out += current; current = ArrayList() }
            out += strokes.chunked(limit)
            continue
        }
        if (current.size + strokes.size > limit) { out += current; current = ArrayList() }
        current += strokes
    }
    if (current.isNotEmpty()) out += current
    return out
}
