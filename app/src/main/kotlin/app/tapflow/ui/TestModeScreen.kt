package app.tapflow.ui

import android.app.Activity
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tapflow.R
import app.tapflow.Session
import app.tapflow.model.StopReason
import app.tapflow.model.labels
import app.tapflow.service.ClickerService
import kotlin.math.hypot
import kotlin.math.roundToInt

/** One touch the screen received, from finger down to finger up. Points are in this view's pixels. */
private class Touch(val id: Int, val startNanos: Long) {
    val points = mutableStateListOf<Offset>()
    var endNanos by mutableLongStateOf(0L)
    var isSwipe by mutableStateOf(false)
    /** Label of the configured target this touch matches ("2", "3b"), or empty for an unmatched (manual) touch. */
    var label by mutableStateOf("")
    val finished get() = endNanos != 0L
    val holdMs: Long get() = if (finished) (endNanos - startNanos) / 1_000_000 else 0
}

private const val MAX_GAP_MS = 600_000L
private const val SAME_STEP_MS = 30L
private const val TAP_FADE_MS = 900f
private const val SWIPE_FADE_MS = 1400f

/**
 * Full-screen black stage for rehearsing a flow. The floating panel is the real one (same markers,
 * same engine); this screen only *displays* every touch it receives, so injected taps and swipes
 * light up exactly where they land. Real gestures, no simulation: what you see is what an app gets.
 */
@Composable
fun TestModeScreen(a11y: A11yStatus, onExit: () -> Unit) {
    if (a11y != A11yStatus.ON) {
        TestNeedsService(onExit)
        return
    }
    val ctx = LocalContext.current
    val resources = LocalResources.current
    val view = LocalView.current
    val run by Session.run.collectAsStateWithLifecycle()
    val p = LocalPalette.current

    val touches = remember { mutableStateListOf<Touch>() }
    var taps by remember { mutableIntStateOf(0) }
    var swipes by remember { mutableIntStateOf(0) }
    var seq by remember { mutableIntStateOf(0) }
    var nowNanos by remember { mutableLongStateOf(0L) }
    var last by remember { mutableStateOf<String?>(null) }
    // Gaps between consecutive touch starts: what the device really delivers vs the configured interval.
    val gaps = remember { mutableStateListOf<Long>() }
    var lastDownNanos by remember { mutableLongStateOf(0L) }
    val cfg by Session.config.collectAsStateWithLifecycle()
    val slop = with(androidx.compose.ui.platform.LocalDensity.current) { 16.dp.toPx() }

    // Keep the screen awake (runs stop on screen-off) and go truly black: hide system bars.
    DisposableEffect(view) {
        val window = (ctx as? Activity)?.window
        val prevKeepOn = view.keepScreenOn
        view.keepScreenOn = true
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose {
            view.keepScreenOn = prevKeepOn
            controller?.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    // Show the real panel on entry; hide it again on exit only if we were the one who showed it.
    // Leaving always stops a run: it must never keep clicking in whatever app comes next.
    DisposableEffect(Unit) {
        val svc = ClickerService.instance
        val weShowed = svc != null && !Session.overlayVisible.value
        if (weShowed) svc?.showOverlay()
        onDispose {
            val s = ClickerService.instance
            if (Session.isBusy) s?.engine?.stop(StopReason.USER)
            if (weShowed) s?.hideOverlay()
        }
    }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_STOP && Session.isBusy) ClickerService.instance?.engine?.stop(StopReason.USER)
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }

    // Animation clock: only ticks while something is still visible.
    LaunchedEffect(touches.size) {
        while (touches.isNotEmpty()) {
            androidx.compose.runtime.withFrameNanos { t ->
                nowNanos = t
                touches.removeAll { it.finished && (t - it.endNanos) / 1_000_000f > SWIPE_FADE_MS }
            }
        }
    }

    val measurer = rememberTextMeasurer()
    val label = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Black, color = Color.Black, textAlign = TextAlign.Center)

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(slop) {
                awaitEachGesture {
                    // Tracks every finger of one gesture: injected multi-touch steps arrive as several pointers.
                    val active = HashMap<PointerId, Touch>()
                    val travelled = HashMap<PointerId, Float>()
                    val names = Session.targets.value.labels()
                    val tolerance = 64.dp.toPx() + Session.config.value.posJitterPx
                    // Strokes of one step start together, or up to a double/triple tap's span later.
                    val stepWindowMs = SAME_STEP_MS + (Session.targets.value.maxOfOrNull {
                        if (it.taps > 1) (it.durationMs + it.tapGapMs) * (it.taps - 1) else 0L
                    } ?: 0L)

                    fun begin(id: PointerId, pos: Offset) {
                        val t = Touch(++seq, System.nanoTime().also { nowNanos = it })
                        t.points.add(pos)
                        t.label = nearestTarget(pos, size.width, size.height, Session.targets.value, tolerance)
                            ?.let { names[it]?.text() }.orEmpty()
                        touches.add(t)
                        active[id] = t
                        travelled[id] = 0f
                        // Touches that start together are one step; only the first one defines the gap.
                        if (lastDownNanos == 0L || (t.startNanos - lastDownNanos) / 1_000_000 > stepWindowMs) {
                            if (lastDownNanos != 0L) {
                                val gap = (t.startNanos - lastDownNanos) / 1_000_000
                                if (gap <= MAX_GAP_MS) gaps.add(gap)
                            }
                            lastDownNanos = t.startNanos
                        }
                    }

                    fun finish(t: Touch) {
                        t.endNanos = System.nanoTime()
                        if (t.isSwipe) {
                            swipes++
                            last = resources.getString(R.string.tm_last_swipe, t.id, t.holdMs)
                        } else {
                            taps++
                            val a = t.points.first()
                            last = resources.getString(R.string.tm_last_tap, t.id, a.x.roundToInt(), a.y.roundToInt(), t.holdMs)
                        }
                    }

                    // Ignore touches a control (exit/reset) already consumed.
                    val down = awaitFirstDown(requireUnconsumed = true)
                    begin(down.id, down.position)
                    while (active.isNotEmpty()) {
                        val ev = awaitPointerEvent()
                        for (ch in ev.changes) {
                            val t = active[ch.id]
                            if (t == null) {
                                if (ch.pressed && !ch.previousPressed && !ch.isConsumed) begin(ch.id, ch.position)
                                continue
                            }
                            if (ch.pressed) {
                                val prev = t.points.last()
                                val p = ch.position
                                if (p != prev) {
                                    val d = (travelled[ch.id] ?: 0f) + hypot(p.x - prev.x, p.y - prev.y)
                                    travelled[ch.id] = d
                                    t.points.add(p)
                                    if (d > slop) t.isSwipe = true
                                }
                            } else {
                                finish(t)
                                active.remove(ch.id)
                            }
                        }
                        // The system cancelled the gesture without lifting: close whatever is left.
                        if (ev.changes.none { it.pressed }) {
                            active.values.toList().forEach(::finish)
                            active.clear()
                        }
                    }
                }
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            drawGrid()
            for (t in touches) drawTouch(t, nowNanos, measurer, label)
        }

        // HUD sits at the bottom so the floating panel's default spot (top) stays clear.
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().padding(12.dp),
            verticalArrangement = Arrangement.Bottom,
        ) {
            if (touches.isEmpty() && taps == 0 && swipes == 0) {
                Text(
                    stringResource(R.string.tm_hint),
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color(0xFFDDDDDD),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                )
            }
            Column(
                Modifier.fillMaxWidth().background(p.surface, CardShape).border(1.dp, p.outline, CardShape).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    AppBadge(stringResource(R.string.test_taps) + " " + taps)
                    AppBadge(stringResource(R.string.test_swipes) + " " + swipes)
                    Box(Modifier.weight(1f))
                    Text(
                        runLabel(run),
                        style = MaterialTheme.typography.labelLarge,
                        color = p.text,
                        modifier = Modifier.weight(2f, fill = false),
                    )
                }
                last?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = p.muted) }
                if (gaps.size >= 2) {
                    Text(
                        stringResource(R.string.tm_interval, gaps.average().roundToInt(), gaps.min(), gaps.max(), cfg.intervalMs),
                        style = MaterialTheme.typography.bodySmall, color = p.muted,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    AppOutlinedButton(
                        onClick = { taps = 0; swipes = 0; seq = 0; last = null; touches.clear(); gaps.clear(); lastDownNanos = 0L },
                        modifier = Modifier.weight(1f),
                    ) { Text(stringResource(R.string.btn_reset)) }
                    AppButton(onClick = onExit, modifier = Modifier.weight(1f)) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                        Text(stringResource(R.string.tm_exit), modifier = Modifier.padding(start = 6.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun runLabel(run: app.tapflow.model.RunState): String = when (run) {
    is app.tapflow.model.RunState.Countdown -> stringResource(R.string.run_countdown, run.seconds)
    app.tapflow.model.RunState.Running -> stringResource(R.string.run_running)
    app.tapflow.model.RunState.Paused -> stringResource(R.string.run_paused)
    else -> ""
}

@Composable
private fun TestNeedsService(onExit: () -> Unit) {
    val ctx = LocalContext.current
    val p = LocalPalette.current
    Column(
        Modifier.fillMaxSize().background(p.bg).safeDrawingPadding().padding(16.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        SectionCard(title = stringResource(R.string.status_setup)) {
            Text(stringResource(R.string.tm_needs_service), style = MaterialTheme.typography.bodyMedium)
            AppButton(onClick = { ctx.openAccessibilitySettings() }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.btn_open_accessibility))
            }
            AppOutlinedButton(onClick = onExit, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.btn_back)) }
        }
    }
}

/** Glow colours for the black canvas; bright on purpose, so they stay readable in both app themes. */
private object TestColors {
    val tap = Color(0xFF7C9BFF)
    val swipeStart = Color(0xFF4CCB8A)
    val swipeEnd = Color(0xFFFFA05C)
}

private fun DrawScope.drawGrid() {
    val step = 48.dp.toPx()
    val dot = Color(0xFF2A2A2A)
    var y = step
    while (y < size.height) {
        var x = step
        while (x < size.width) {
            drawCircle(dot, radius = 1.5.dp.toPx(), center = Offset(x, y))
            x += step
        }
        y += step
    }
}

private fun DrawScope.drawTouch(t: Touch, now: Long, measurer: TextMeasurer, label: TextStyle) {
    if (t.points.isEmpty()) return
    val ageMs = if (t.finished) ((now - t.endNanos) / 1_000_000f).coerceAtLeast(0f) else 0f
    val fade = if (t.isSwipe) SWIPE_FADE_MS else TAP_FADE_MS
    val a = (1f - ageMs / fade).coerceIn(0f, 1f)
    if (a <= 0f) return
    val liveMs = if (t.finished) (t.endNanos - t.startNanos) / 1_000_000f else ((now - t.startNanos) / 1_000_000f).coerceAtLeast(0f)
    val d = density
    val start = t.points.first()
    val end = t.points.last()

    if (t.isSwipe) {
        val glow = TestColors.tap
        // Trail: wide soft stroke under a bright thin one, so the path reads at a glance.
        for (i in 1 until t.points.size) {
            drawLine(glow.copy(alpha = 0.25f * a), t.points[i - 1], t.points[i], strokeWidth = 22 * d, cap = StrokeCap.Round)
        }
        for (i in 1 until t.points.size) {
            drawLine(Color.White.copy(alpha = a), t.points[i - 1], t.points[i], strokeWidth = 5 * d, cap = StrokeCap.Round)
        }
        drawGlowDot(start, TestColors.swipeStart, a, 18 * d)
        drawGlowDot(end, TestColors.swipeEnd, a, 22 * d)
        drawBadge(start, t.label.ifEmpty { "·" }, measurer, label, TestColors.swipeStart, a)
        // Direction cue: a small ring at the end point that keeps expanding.
        drawCircle(TestColors.swipeEnd.copy(alpha = a * 0.8f), radius = (22 + (1f - a) * 30) * d, center = end, style = Stroke(3 * d))
    } else {
        // Expanding shock ring + steady glow; while held, the ring keeps pulsing.
        val pulse = if (t.finished) ageMs / TAP_FADE_MS else (liveMs % 600f) / 600f
        val ringR = (18 + pulse * 46) * d
        drawGlowDot(start, TestColors.tap, a, 34 * d)
        drawCircle(TestColors.tap.copy(alpha = a * (1f - pulse * 0.6f)), radius = ringR, center = start, style = Stroke(4 * d, join = StrokeJoin.Round))
        drawBadge(start, t.label.ifEmpty { "·" }, measurer, label, TestColors.tap, a)
    }
}

private fun DrawScope.drawGlowDot(c: Offset, color: Color, a: Float, r: Float) {
    drawCircle(
        brush = Brush.radialGradient(
            0f to color.copy(alpha = 0.95f * a),
            0.45f to color.copy(alpha = 0.45f * a),
            1f to color.copy(alpha = 0f),
            center = c, radius = r * 1.8f,
        ),
        radius = r * 1.8f, center = c,
    )
    drawCircle(color.copy(alpha = a), radius = r * 0.32f, center = c)
}

/** Sequence number in a small chip next to the touch, so a flow's order is readable. */
private fun DrawScope.drawBadge(at: Offset, text: String, measurer: TextMeasurer, style: TextStyle, fill: Color, a: Float) {
    val layout = measurer.measure(text, style.copy(color = Color.Black.copy(alpha = a)))
    val pad = 5 * density
    val w = layout.size.width + pad * 2
    val h = layout.size.height + pad
    val tl = Offset(at.x + 20 * density, at.y - 20 * density - h)
    drawRoundRect(fill.copy(alpha = a), topLeft = tl, size = androidx.compose.ui.geometry.Size(w, h), cornerRadius = CornerRadius(6 * density))
    drawText(layout, topLeft = Offset(tl.x + pad, tl.y + pad / 2))
}

/** Id of the closest configured target (by start point) within [tolerance] pixels, or null. */
private fun nearestTarget(p: Offset, w: Int, h: Int, targets: List<app.tapflow.model.Target>, tolerance: Float): Long? {
    var best: Long? = null
    var bestD = tolerance
    for (t in targets) {
        val d = hypot(p.x - t.x * w, p.y - t.y * h)
        if (d <= bestD) { bestD = d; best = t.id }
    }
    return best
}
