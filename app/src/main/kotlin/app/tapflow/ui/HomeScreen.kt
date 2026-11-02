package app.tapflow.ui

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tapflow.R
import app.tapflow.Session
import app.tapflow.data.Store
import app.tapflow.model.Limits
import app.tapflow.model.RunConfig
import app.tapflow.model.RunState
import app.tapflow.model.Stats
import app.tapflow.model.StopMode
import app.tapflow.model.StopReason
import app.tapflow.model.Target
import app.tapflow.model.TargetKind
import app.tapflow.model.TargetLabel
import app.tapflow.model.labels
import app.tapflow.model.normalizedSteps
import app.tapflow.model.steps
import app.tapflow.model.withClone
import app.tapflow.service.ClickerService
import kotlinx.coroutines.flow.update
import kotlin.math.roundToInt
import kotlin.math.roundToLong

@Composable
fun HomeScreen(
    a11y: A11yStatus,
    onMessage: (String) -> Unit,
    onOpenGuide: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val run by Session.run.collectAsStateWithLifecycle()
    val stats by Session.stats.collectAsStateWithLifecycle()
    val targets by Session.targets.collectAsStateWithLifecycle()
    val cfg by Session.config.collectAsStateWithLifecycle()
    val epoch by Session.configEpoch.collectAsStateWithLifecycle()
    val overlayVisible by Session.overlayVisible.collectAsStateWithLifecycle()
    val loadedId by Session.loadedScriptId.collectAsStateWithLifecycle()
    val scripts by Store.scripts.collectAsStateWithLifecycle()
    val res = LocalResources.current
    val busy = run is RunState.Countdown || run is RunState.Running || run is RunState.Paused
    var showSave by remember { mutableStateOf(false) }

    Column(
        modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RunCard(a11y, run, stats, targets.size, cfg, overlayVisible, onOpenGuide, onMessage)

        SectionCard(title = stringResource(R.string.cfg_title)) {
            IntervalEditor(cfg.intervalMs, epoch, enabled = !busy) { ms -> Session.updateConfig { it.copy(intervalMs = ms) } }
            StopEditor(cfg.stopMode, cfg.stopCount, cfg.stopDurationSec, epoch, enabled = !busy)
            AdvancedSection(cfg, enabled = !busy)
        }

        if (targets.isNotEmpty()) {
            TargetsCard(targets, epoch, enabled = !busy, defaultDelayMs = cfg.intervalMs, onSave = { showSave = true })
        }
        Spacer(Modifier.height(16.dp))
    }

    if (showSave) {
        val existing = loadedId?.let(Store::find)
        SaveDialog(
            defaultName = existing?.name ?: stringResource(R.string.save_default_name, scripts.size + 1),
            canOverwrite = existing != null,
            onDismiss = { showSave = false },
            onSave = { name, overwrite ->
                showSave = false
                if (scripts.size >= Limits.MAX_SCRIPTS && !overwrite) {
                    onMessage(res.getString(R.string.err_library_full))
                } else {
                    val saved = Store.saveScript(name, Session.targets.value, Session.config.value, if (overwrite) existing?.id else null)
                    Session.loadedScriptId.value = saved.id
                    onMessage(res.getString(R.string.saved_snack, saved.name))
                }
            },
        )
    }
}

/**
 * The one place that says what is going on and what to press next: setup help while the service is off,
 * a three-step guide with no targets, otherwise the plan plus the run controls for the current state.
 */
@Composable
private fun RunCard(
    a11y: A11yStatus,
    run: RunState,
    stats: Stats,
    targetCount: Int,
    cfg: RunConfig,
    overlayVisible: Boolean,
    onOpenGuide: () -> Unit,
    onMessage: (String) -> Unit,
) {
    val ctx = LocalContext.current
    val res = LocalResources.current
    val p = LocalPalette.current
    val ready = a11y == A11yStatus.ON
    val title = when {
        !ready -> stringResource(if (a11y == A11yStatus.STALE) R.string.status_stale else R.string.status_setup)
        run is RunState.Idle -> stringResource(R.string.status_ready)
        else -> runHeadline(run)
    }
    val subtitle = when {
        !ready -> null
        run is RunState.Idle -> if (targetCount == 0) null else planSummary(targetCount, cfg)
        else -> stringResource(R.string.run_stats, stats.actions, stats.cycles, formatElapsed(stats.elapsedMs))
    }
    val svc = ClickerService.instance

    fun togglePanel() {
        if (overlayVisible) svc?.hideOverlay() else {
            svc?.showOverlay()
            // Get out of the way so the user can go to the app they want to click in.
            (ctx as? Activity)?.moveTaskToBack(true)
        }
    }

    fun start() {
        if (svc?.startFromApp() == true) (ctx as? Activity)?.moveTaskToBack(true)
        else onMessage(res.getString(R.string.toast_panel_covers))
    }

    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(12.dp).background(if (ready) p.success else p.warn, CircleShape))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = p.muted)
            }
        }

        if (!ready) {
            Text(
                stringResource(if (a11y == A11yStatus.STALE) R.string.status_stale_desc else R.string.status_setup_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = p.muted,
            )
            AppButton(onClick = { ctx.openAccessibilitySettings() }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.btn_open_accessibility))
            }
            // Sideloaded installs on Android 13+ cannot enable the service until this is allowed.
            Text(stringResource(R.string.restricted_hint), style = MaterialTheme.typography.bodySmall, color = p.muted)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AppOutlinedButton(onClick = { ctx.openAppInfo() }) { Text(stringResource(R.string.btn_open_app_info)) }
                AppTextButton(onClick = onOpenGuide) { Text(stringResource(R.string.btn_setup_guide)) }
            }
        } else {
            ReadyBody(run, stats, targetCount, overlayVisible, svc, ::togglePanel, ::start)
        }
    }
}

@Composable
private fun ReadyBody(
    run: RunState,
    stats: Stats,
    targetCount: Int,
    overlayVisible: Boolean,
    svc: ClickerService?,
    togglePanel: () -> Unit,
    start: () -> Unit,
) {
    val p = LocalPalette.current
    if (run is RunState.Finished) {
        if (stats.failures > 0) {
            Text(stringResource(R.string.run_failures, stats.failures), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        if (run.reason == StopReason.SCREEN_OFF) {
            Text(stringResource(R.string.run_screen_off_hint), style = MaterialTheme.typography.bodySmall, color = p.muted)
        }
    }
    if (run is RunState.Paused) {
        Text(stringResource(R.string.run_paused_hint), style = MaterialTheme.typography.bodySmall, color = p.muted)
    }

    when {
        // Nothing to run yet: show how to place the first target instead of an inert Start button.
        targetCount == 0 && !run.isBusy() -> {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(R.string.empty_step1, R.string.empty_step2, R.string.empty_step3).forEachIndexed { i, step ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                        AppBadge((i + 1).toString(), Modifier.width(32.dp))
                        Text(stringResource(step), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    }
                }
            }
            AppButton(onClick = togglePanel, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(if (overlayVisible) R.string.btn_hide_panel else R.string.btn_show_panel))
            }
        }
        run is RunState.Countdown || run is RunState.Running -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AppOutlinedButton(onClick = { svc?.playPause() }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.panel_pause)) }
            AppButton(onClick = { svc?.engine?.stop(StopReason.USER) }, modifier = Modifier.weight(1f), destructive = true) {
                Text(stringResource(R.string.panel_stop))
            }
        }
        run is RunState.Paused -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AppButton(onClick = { svc?.playPause() }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.panel_resume)) }
            AppButton(onClick = { svc?.engine?.stop(StopReason.USER) }, modifier = Modifier.weight(1f), destructive = true) {
                Text(stringResource(R.string.panel_stop))
            }
        }
        else -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AppButton(onClick = start, modifier = Modifier.weight(1.4f)) {
                Text(stringResource(if (run is RunState.Finished) R.string.btn_run_again else R.string.panel_start))
            }
            AppOutlinedButton(onClick = togglePanel, modifier = Modifier.weight(1f)) {
                Text(stringResource(if (overlayVisible) R.string.btn_panel_hide else R.string.btn_panel_show))
            }
        }
    }
}

private fun RunState.isBusy() = this is RunState.Countdown || this is RunState.Running || this is RunState.Paused

/** "3 targets · every 1 s · until you stop": what pressing Start will do, in one line. */
@Composable
private fun planSummary(targetCount: Int, cfg: RunConfig): String {
    val stop = when (cfg.stopMode) {
        StopMode.INFINITE -> stringResource(R.string.plan_until_stop)
        StopMode.COUNT -> pluralStringResource(R.plurals.plan_cycles, cfg.stopCount, cfg.stopCount)
        StopMode.DURATION -> stringResource(R.string.plan_for_time, formatElapsed(cfg.stopDurationSec * 1000L))
    }
    return pluralStringResource(R.plurals.panel_targets, targetCount, targetCount) +
        " · " + stringResource(R.string.summary_every, formatMs(cfg.intervalMs)) + " · " + stop
}

@Composable
private fun runHeadline(run: RunState): String = when (run) {
    is RunState.Countdown -> stringResource(R.string.run_countdown, run.seconds)
    RunState.Running -> stringResource(R.string.run_running)
    RunState.Paused -> stringResource(R.string.run_paused)
    is RunState.Finished -> stringResource(R.string.run_stopped, stringResource(stopReasonRes(run.reason)))
    RunState.Idle -> ""
}

private enum class IntervalUnit(val ms: Long, val label: Int) {
    MS(1, R.string.unit_ms), S(1000, R.string.unit_s), MIN(60_000, R.string.unit_min);

    companion object {
        fun best(ms: Long) = when {
            ms % 60_000 == 0L -> MIN
            ms % 1000 == 0L -> S
            else -> MS
        }
    }
}

private fun formatIn(ms: Long, unit: IntervalUnit): String {
    val v = ms.toDouble() / unit.ms
    return if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString()
}

@Composable
private fun IntervalEditor(intervalMs: Long, epoch: Int, enabled: Boolean, onChange: (Long) -> Unit) {
    var unit by remember(epoch) { mutableStateOf(IntervalUnit.best(intervalMs)) }
    var text by remember(epoch) { mutableStateOf(formatIn(intervalMs, unit)) }
    var error by remember(epoch) { mutableStateOf<String?>(null) }
    val required = stringResource(R.string.err_required)
    val invalid = stringResource(R.string.err_number)
    val tooSmall = stringResource(R.string.err_interval_min, Limits.MIN_INTERVAL_MS)
    val tooLarge = stringResource(R.string.err_interval_max)

    fun commit(t: String, u: IntervalUnit) {
        val v = t.toDoubleOrNull()
        when {
            t.isBlank() -> error = required
            v == null || v.isNaN() || v.isInfinite() -> error = invalid
            else -> {
                val ms = (v * u.ms).roundToLong()
                when {
                    ms < Limits.MIN_INTERVAL_MS -> error = tooSmall
                    ms > Limits.MAX_INTERVAL_MS -> error = tooLarge
                    else -> { error = null; onChange(ms) }
                }
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // Stacked rather than side by side: long translations and large font sizes would squeeze the unit selector.
        AppTextField(
            value = text,
            onValueChange = { raw ->
                val t = raw.filter { it.isDigit() || it == '.' }.take(10)
                text = t
                commit(t, unit)
            },
            label = { Text(stringResource(R.string.cfg_interval)) },
            isError = error != null,
            supportingText = error?.let { { Text(it) } },
            singleLine = true,
            enabled = enabled,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth(),
        )
        AppSegmented(
            options = IntervalUnit.entries.map { stringResource(it.label) },
            selected = IntervalUnit.entries.indexOf(unit),
            enabled = enabled,
            onSelect = { i -> val u = IntervalUnit.entries[i]; unit = u; commit(text, u) },
            modifier = Modifier.fillMaxWidth(),
        )
        // One scrolling row keeps the presets from taking a second and third line.
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(end = 4.dp, bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(100L, 250L, 500L, 1000L, 5000L).forEach { preset ->
                AppChip(
                    selected = intervalMs == preset,
                    enabled = enabled,
                    onClick = {
                        unit = IntervalUnit.best(preset)
                        text = formatIn(preset, unit)
                        error = null
                        onChange(preset)
                    },
                    label = formatMs(preset),
                )
            }
        }
        if (intervalMs < 100) {
            Text(
                stringResource(R.string.warn_fast_interval),
                style = MaterialTheme.typography.bodySmall,
                color = LocalPalette.current.warn,
            )
        }
    }
}

@Composable
private fun StopEditor(mode: StopMode, count: Int, durationSec: Int, epoch: Int, enabled: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.cfg_stop_mode), style = MaterialTheme.typography.labelLarge)
        val labels = listOf(R.string.stop_mode_infinite, R.string.stop_mode_count, R.string.stop_mode_time)
        AppSegmented(
            options = labels.map { stringResource(it) },
            selected = StopMode.entries.indexOf(mode),
            enabled = enabled,
            onSelect = { i -> Session.updateConfig { it.copy(stopMode = StopMode.entries[i]) } },
            modifier = Modifier.fillMaxWidth(),
        )
        when (mode) {
            StopMode.COUNT -> IntField(
                label = stringResource(R.string.cfg_count),
                initial = count, min = 1, max = Limits.MAX_COUNT, enabled = enabled, resetKey = epoch,
                onValid = { v -> Session.updateConfig { it.copy(stopCount = v) } },
                modifier = Modifier.fillMaxWidth(),
            )
            StopMode.DURATION -> IntField(
                label = stringResource(R.string.cfg_duration),
                initial = durationSec, min = 1, max = Limits.MAX_DURATION_SEC, enabled = enabled, resetKey = epoch,
                onValid = { v -> Session.updateConfig { it.copy(stopDurationSec = v) } },
                modifier = Modifier.fillMaxWidth(),
            )
            StopMode.INFINITE -> Unit
        }
    }
}

/** Start delay and the two randomisation sliders, folded away because most runs never touch them. */
@Composable
private fun AdvancedSection(cfg: RunConfig, enabled: Boolean) {
    var open by rememberSaveable { mutableStateOf(false) }
    Column {
        Row(
            Modifier.fillMaxWidth().clickable(role = Role.Button) { open = !open }.heightIn(min = 48.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.adv_title), style = MaterialTheme.typography.labelLarge)
                if (!open) {
                    Text(
                        stringResource(R.string.adv_summary, cfg.startDelaySec, cfg.timeJitterPct, cfg.posJitterPx),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(if (open) "▴" else "▾", style = MaterialTheme.typography.titleMedium)
        }
        if (open) {
            LabeledSlider(
                label = stringResource(R.string.cfg_start_delay, cfg.startDelaySec),
                value = cfg.startDelaySec.toFloat(), range = 0f..10f, steps = 9, enabled = enabled,
            ) { v -> Session.updateConfig { it.copy(startDelaySec = v.roundToInt()) } }
            LabeledSlider(
                label = stringResource(R.string.cfg_time_jitter, cfg.timeJitterPct),
                value = cfg.timeJitterPct.toFloat(), range = 0f..Limits.MAX_TIME_JITTER_PCT.toFloat(), steps = 0, enabled = enabled,
            ) { v -> Session.updateConfig { it.copy(timeJitterPct = v.roundToInt()) } }
            LabeledSlider(
                label = stringResource(R.string.cfg_pos_jitter, cfg.posJitterPx),
                value = cfg.posJitterPx.toFloat(), range = 0f..Limits.MAX_POS_JITTER_PX.toFloat(), steps = 0, enabled = enabled,
            ) { v -> Session.updateConfig { it.copy(posJitterPx = v.roundToInt()) } }
        }
    }
}

@Composable
private fun LabeledSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    enabled: Boolean,
    onChange: (Float) -> Unit,
) {
    Column {
        Text(label, style = MaterialTheme.typography.labelLarge)
        androidx.compose.material3.Slider(
            value = value, onValueChange = onChange, valueRange = range, steps = steps, enabled = enabled,
        )
    }
}

// ---- targets -----------------------------------------------------------------------------------

/** Copies a target next to itself in the same step (so the pair runs together). Returns the copy's id, or null if full. */
private fun cloneTarget(id: Long): Long? {
    val result = Session.targets.value.withClone(id, Session.newTargetId(), CLONE_OFFSET) ?: return null
    Session.targets.value = result.first
    return result.second.id
}

private const val CLONE_OFFSET = 0.08f

private fun edit(id: Long, change: (Target) -> Target) =
    Session.targets.update { list -> list.map { if (it.id == id) change(it).sanitized() else it } }

/** Moves one target to a new order. Equal numbers mean "together"; numbers are always re-densified afterwards. */
private fun reorder(id: Long, newStep: Int) = Session.targets.update { list ->
    list.normalizedSteps().map { if (it.id == id) it.copy(step = newStep.coerceAtLeast(1)) else it }.normalizedSteps()
}

/** One collapsed line per target; tapping a line opens its editor (one at a time). */
@Composable
private fun TargetsCard(targets: List<Target>, epoch: Int, enabled: Boolean, defaultDelayMs: Long, onSave: () -> Unit) {
    val p = LocalPalette.current
    var openId by rememberSaveable { mutableStateOf<Long?>(null) }
    val labels = targets.labels()
    val groups = labels.values.maxOfOrNull { it.rank } ?: 0

    SectionCard(
        title = stringResource(R.string.tgt_title),
        headerAction = { AppTonalButton(onClick = onSave) { Text(stringResource(R.string.btn_save)) } },
    ) {
        // Show rows in run order (steps), not in the order they were added.
        val ordered = targets.steps().flatten()
        Column {
            ordered.forEachIndexed { i, t ->
                val label = labels[t.id]
                if (label != null) {
                    key(t.id) {
                        val open = openId == t.id
                        Row(
                            Modifier.fillMaxWidth().clickable(role = Role.Button) { openId = if (open) null else t.id }.heightIn(min = 48.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            AppBadge(label.text(), Modifier.width(56.dp), tonal = t.kind == TargetKind.TAP)
                            Text(targetSummary(t), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                            Text(if (open) "▴" else "▾", style = MaterialTheme.typography.titleMedium)
                        }
                        if (open) {
                            TargetEditor(
                                t, label, groups, epoch, enabled, defaultDelayMs,
                                canClone = targets.size < Limits.MAX_TARGETS,
                                onClone = { cloneTarget(t.id)?.let { openId = it } },
                            ) {
                                openId = null
                                Session.targets.update { l -> l.filterNot { it.id == t.id } }
                            }
                        }
                        if (i < ordered.lastIndex) HorizontalDivider(thickness = 1.dp, color = p.outline)
                    }
                }
            }
        }
    }
}

@Composable
private fun targetSummary(t: Target): String {
    val kind = stringResource(
        when {
            t.kind == TargetKind.SWIPE -> R.string.tgt_kind_swipe
            t.taps > 1 -> R.string.tgt_type_double
            t.isLongPress -> R.string.tgt_type_long
            else -> R.string.tgt_type_tap
        },
    )
    return kind + " · " + t.durationMs + " " + stringResource(R.string.unit_ms)
}

@Composable
private fun TargetEditor(
    t: Target,
    label: TargetLabel,
    groups: Int,
    epoch: Int,
    enabled: Boolean,
    defaultDelayMs: Long,
    canClone: Boolean,
    onClone: () -> Unit,
    onDelete: () -> Unit,
) {
    // Programmatic changes (type / presets) must refresh the numeric fields; typing must not.
    var nonce by remember(t.id) { mutableIntStateOf(0) }
    Column(Modifier.padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // Order: equal numbers run together as multi-touch.
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.tgt_order), style = MaterialTheme.typography.labelLarge)
                Text(
                    stringResource(R.string.tgt_order_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            AppOutlinedButton(
                onClick = { reorder(t.id, label.rank - 1) },
                enabled = enabled && label.rank > 1,
                modifier = Modifier.width(52.dp),
            ) { Text("−") }
            Text(label.rank.toString(), style = MaterialTheme.typography.titleLarge, modifier = Modifier.widthIn(min = 24.dp))
            AppOutlinedButton(
                onClick = { reorder(t.id, label.rank + 1) },
                enabled = enabled && (label.rank < groups || label.stepSize > 1),
                modifier = Modifier.width(52.dp),
            ) { Text("+") }
        }

        if (t.kind == TargetKind.TAP) {
            val selected = when {
                t.taps > 1 -> 1
                t.isLongPress -> 2
                else -> 0
            }
            AppSegmented(
                options = listOf(R.string.tgt_type_tap, R.string.tgt_type_double, R.string.tgt_type_long).map { stringResource(it) },
                selected = selected,
                enabled = enabled,
                onSelect = { i ->
                    edit(t.id) {
                        when (i) {
                            0 -> it.copy(taps = 1, durationMs = Limits.DEFAULT_TAP_MS)
                            1 -> it.copy(taps = 2, durationMs = if (it.isLongPress) Limits.DEFAULT_TAP_MS else it.durationMs)
                            else -> it.copy(taps = 1, durationMs = maxOf(it.durationMs, 1000L))
                        }
                    }
                    nonce++
                },
                modifier = Modifier.fillMaxWidth(),
            )
            if (selected == 2) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(500L, 1000L, 2000L, 5000L).forEach { ms ->
                        AppChip(
                            selected = t.durationMs == ms, enabled = enabled,
                            onClick = { edit(t.id) { it.copy(durationMs = ms) }; nonce++ },
                            label = formatMs(ms),
                        )
                    }
                }
            }
        }

        IntField(
            label = stringResource(if (t.kind == TargetKind.TAP) R.string.tgt_hold else R.string.tgt_swipe_time),
            initial = t.durationMs.toInt(), min = 1, max = Limits.MAX_HOLD_MS.toInt(),
            enabled = enabled, resetKey = listOf(epoch, t.id, nonce),
            onValid = { v -> edit(t.id) { it.copy(durationMs = v.toLong()) } },
            modifier = Modifier.fillMaxWidth(),
        )
        if (t.taps > 1) {
            IntField(
                label = stringResource(R.string.tgt_gap),
                initial = t.tapGapMs.toInt(), min = Limits.MIN_TAP_GAP_MS.toInt(), max = Limits.MAX_TAP_GAP_MS.toInt(),
                enabled = enabled, resetKey = listOf(epoch, t.id, nonce),
                onValid = { v -> edit(t.id) { it.copy(tapGapMs = v.toLong()) } },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        SwitchRow(stringResource(R.string.tgt_custom_delay), null, t.delayAfterMs >= 0) { on ->
            if (enabled) edit(t.id) { it.copy(delayAfterMs = if (on) defaultDelayMs else Target.USE_GLOBAL_DELAY) }
        }
        if (t.delayAfterMs >= 0) {
            IntField(
                label = stringResource(R.string.tgt_delay),
                initial = t.delayAfterMs.toInt(),
                min = Limits.MIN_INTERVAL_MS.toInt(), max = Limits.MAX_INTERVAL_MS.toInt(),
                enabled = enabled, resetKey = listOf(epoch, t.id, nonce),
                onValid = { v -> edit(t.id) { it.copy(delayAfterMs = v.toLong()) } },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AppOutlinedButton(onClick = onClone, enabled = enabled && canClone, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.menu_clone))
            }
            AppOutlinedButton(onClick = onDelete, enabled = enabled, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.btn_delete))
            }
        }
    }
}

@Composable
private fun SaveDialog(defaultName: String, canOverwrite: Boolean, onDismiss: () -> Unit, onSave: (String, Boolean) -> Unit) {
    var name by remember { mutableStateOf(defaultName) }
    var overwrite by remember { mutableStateOf(canOverwrite) }
    val blank = name.isBlank()
    AppAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.save_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AppTextField(
                    value = name,
                    onValueChange = { name = it.take(Limits.MAX_NAME_LENGTH) },
                    label = { Text(stringResource(R.string.save_name_label)) },
                    isError = blank,
                    supportingText = if (blank) ({ Text(stringResource(R.string.err_required)) }) else null,
                    singleLine = true,
                )
                if (canOverwrite) {
                    SwitchRow(stringResource(R.string.save_overwrite), null, overwrite) { overwrite = it }
                }
            }
        },
        confirmButton = { AppTextButton(onClick = { onSave(name, overwrite) }, enabled = !blank) { Text(stringResource(R.string.btn_save)) } },
        dismissButton = { AppTextButton(onClick = onDismiss) { Text(stringResource(R.string.btn_cancel)) } },
    )
}
