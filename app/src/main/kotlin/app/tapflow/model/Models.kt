package app.tapflow.model

enum class TargetKind { TAP, SWIPE }

enum class StopMode { INFINITE, COUNT, DURATION }

object Limits {
    const val MIN_INTERVAL_MS = 20L
    const val MAX_INTERVAL_MS = 24L * 60 * 60 * 1000
    const val MAX_COUNT = 1_000_000
    const val MAX_DURATION_SEC = 24 * 60 * 60
    const val MAX_START_DELAY_SEC = 60
    const val MAX_TARGETS = 40
    const val MAX_TIME_JITTER_PCT = 50
    const val MAX_POS_JITTER_PX = 40
    const val MAX_HOLD_MS = 60_000L
    const val MAX_NAME_LENGTH = 60
    const val MAX_SCRIPTS = 200
    const val MAX_IMPORT_BYTES = 512 * 1024
    const val MAX_TAPS = 3
    const val MIN_TAP_GAP_MS = 20L
    const val MAX_TAP_GAP_MS = 1000L
    const val DEFAULT_TAP_GAP_MS = 60L
    const val MAX_STEP = 999
    /** Press time from which a tap counts as a long press (used for labels and presets). */
    const val LONG_PRESS_MS = 500L
    const val DEFAULT_TAP_MS = 40L
    const val DEFAULT_SWIPE_MS = 300L
}

/**
 * One click point or swipe. Coordinates are normalized (0..1) against the display so that a
 * script survives rotation, split-screen resizes and resolution changes.
 */
data class Target(
    val id: Long,
    val kind: TargetKind,
    val x: Float,
    val y: Float,
    val x2: Float = x,
    val y2: Float = y,
    val durationMs: Long = if (kind == TargetKind.TAP) Limits.DEFAULT_TAP_MS else Limits.DEFAULT_SWIPE_MS,
    /** Wait after this target; [USE_GLOBAL_DELAY] means "use the run's interval". */
    val delayAfterMs: Long = USE_GLOBAL_DELAY,
    /** Run order. Targets sharing a step are performed together (multi-touch) in one gesture. */
    val step: Int = 1,
    /** How many times a TAP is repeated back-to-back inside one gesture (2 = double tap). Ignored for swipes. */
    val taps: Int = 1,
    val tapGapMs: Long = Limits.DEFAULT_TAP_GAP_MS,
) {
    fun sanitized(): Target = copy(
        x = x.unit(), y = y.unit(), x2 = x2.unit(), y2 = y2.unit(),
        durationMs = durationMs.coerceIn(1L, Limits.MAX_HOLD_MS),
        step = step.coerceIn(1, Limits.MAX_STEP),
        taps = if (kind == TargetKind.TAP) taps.coerceIn(1, Limits.MAX_TAPS) else 1,
        tapGapMs = tapGapMs.coerceIn(Limits.MIN_TAP_GAP_MS, Limits.MAX_TAP_GAP_MS),
        delayAfterMs = if (delayAfterMs < 0) USE_GLOBAL_DELAY
        else delayAfterMs.coerceIn(Limits.MIN_INTERVAL_MS, Limits.MAX_INTERVAL_MS),
    )

    /** True when position and shape are identical, ignoring timing fields. */
    fun sameGeometry(o: Target) = id == o.id && kind == o.kind && x == o.x && y == o.y && x2 == o.x2 && y2 == o.y2

    /** True when the marker would look identical: same shape plus the fields shown in its label. */
    fun samePresentation(o: Target) = sameGeometry(o) && step == o.step && taps == o.taps &&
        (durationMs >= Limits.LONG_PRESS_MS) == (o.durationMs >= Limits.LONG_PRESS_MS)

    val isLongPress get() = kind == TargetKind.TAP && durationMs >= Limits.LONG_PRESS_MS

    companion object {
        const val USE_GLOBAL_DELAY = -1L
    }
}

/** Targets in run order, grouped by step. Equal steps are one group; the sort is stable, so list order breaks ties. */
fun List<Target>.steps(): List<List<Target>> =
    withIndex().sortedWith(compareBy({ it.value.step }, { it.index })).map { it.value }
        .groupBy { it.step }.values.toList()

/** Position of a target in the run: its 1-based dense step rank and its index within a shared step. */
/** Renumbers steps to 1..k without gaps, keeping relative order and which targets share a step. */
fun List<Target>.normalizedSteps(): List<Target> {
    val rank = map { it.step }.distinct().sorted().withIndex().associate { it.value to it.index + 1 }
    return map { it.copy(step = rank.getValue(it.step)) }
}

/**
 * Copies target [id] right after itself with the same step, so the copy runs together with the original.
 * The copy is shifted sideways by [dx] (normalized) so it does not sit on top of the original; the direction
 * flips near the right edge. Null if [id] is unknown or the list is already full.
 */
fun List<Target>.withClone(id: Long, newId: Long, dx: Float): Pair<List<Target>, Target>? {
    if (size >= Limits.MAX_TARGETS) return null
    val index = indexOfFirst { it.id == id }
    if (index < 0) return null
    val src = this[index]
    val shift = if (maxOf(src.x, src.x2) + dx <= 0.97f) dx else -dx
    val copy = src.copy(id = newId, x = src.x + shift, x2 = src.x2 + shift).sanitized()
    return toMutableList().also { it.add(index + 1, copy) } to copy
}

class TargetLabel(val rank: Int, val indexInStep: Int, val stepSize: Int) {
    /** "3", or "3b" when several targets share the step. */
    fun text(): String = if (stepSize > 1) rank.toString() + ('a' + indexInStep) else rank.toString()
}

fun List<Target>.labels(): Map<Long, TargetLabel> {
    val out = HashMap<Long, TargetLabel>(size)
    steps().forEachIndexed { r, group -> group.forEachIndexed { i, t -> out[t.id] = TargetLabel(r + 1, i, group.size) } }
    return out
}

private fun Float.unit(): Float = if (isNaN()) 0.5f else coerceIn(0f, 1f)

data class RunConfig(
    val intervalMs: Long = 1000L,
    val timeJitterPct: Int = 0,
    val posJitterPx: Int = 0,
    val stopMode: StopMode = StopMode.INFINITE,
    val stopCount: Int = 100,
    val stopDurationSec: Int = 60,
    val startDelaySec: Int = 3,
) {
    fun sanitized(): RunConfig = copy(
        intervalMs = intervalMs.coerceIn(Limits.MIN_INTERVAL_MS, Limits.MAX_INTERVAL_MS),
        timeJitterPct = timeJitterPct.coerceIn(0, Limits.MAX_TIME_JITTER_PCT),
        posJitterPx = posJitterPx.coerceIn(0, Limits.MAX_POS_JITTER_PX),
        stopCount = stopCount.coerceIn(1, Limits.MAX_COUNT),
        stopDurationSec = stopDurationSec.coerceIn(1, Limits.MAX_DURATION_SEC),
        startDelaySec = startDelaySec.coerceIn(0, Limits.MAX_START_DELAY_SEC),
    )
}

data class Script(
    val id: String,
    val name: String,
    val targets: List<Target>,
    val config: RunConfig,
    val updatedAt: Long,
)

enum class StopReason {
    USER, COUNT_REACHED, TIME_REACHED, SCREEN_OFF, GESTURE_FAILED, NO_TARGETS, SERVICE_LOST, VOLUME_KEY,
}

sealed interface RunState {
    data object Idle : RunState
    data class Countdown(val seconds: Int) : RunState
    data object Running : RunState
    data object Paused : RunState
    data class Finished(val reason: StopReason) : RunState
}

data class Stats(
    val actions: Int = 0,
    val failures: Int = 0,
    val cycles: Int = 0,
    val elapsedMs: Long = 0L,
)
