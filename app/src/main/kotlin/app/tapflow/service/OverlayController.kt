package app.tapflow.service

import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.PointF
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.Toast
import app.tapflow.AppSettings
import app.tapflow.MainActivity
import app.tapflow.R
import app.tapflow.Session
import app.tapflow.data.Store
import app.tapflow.model.labels
import app.tapflow.model.steps
import app.tapflow.model.withClone
import app.tapflow.model.Limits
import app.tapflow.model.RunState
import app.tapflow.model.Stats
import app.tapflow.model.StopReason
import app.tapflow.model.Target
import app.tapflow.model.TargetKind
import kotlinx.coroutines.flow.update
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Draws the control panel and the draggable target markers as accessibility overlays
 * (TYPE_ACCESSIBILITY_OVERLAY), so no SYSTEM_ALERT_WINDOW permission is needed and synthetic
 * touches are not blocked as "untrusted overlay" input.
 *
 * Three independent flags describe what is on screen:
 * - [visible]: the user wants the overlay (panel button / tile); survives everything below.
 * - [suppressed]: the screen is off, dozing (always-on display) or locked; windows are detached meanwhile.
 * - [minimized]: the panel is folded into a small bubble.
 */
class OverlayController(private val service: ClickerService) {
    private val wm = service.getSystemService(WindowManager::class.java)
    private val density = service.resources.displayMetrics.density
    private val touchSlop = ViewConfiguration.get(service).scaledTouchSlop

    private companion object {
        const val FLASH_MS = 3000L
        const val UNDO_MS = 6000L
        const val MENU_MS = 6000L
        const val APP_START_DELAY_SEC = 2
    }

    private fun dp(v: Int) = (v * density).roundToInt()
    private val markerSize get() = dp(44)
    private val bubbleSize get() = dp(44)

    private class Marker(
        val targetId: Long,
        val isEnd: Boolean,
        val view: MarkerView,
        val params: WindowManager.LayoutParams,
    )

    private var panel: PanelView? = null
    private var panelParams: WindowManager.LayoutParams? = null
    private var bubble: IconView? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var lines: LinesView? = null
    private val markers = mutableListOf<Marker>()
    private var menu: MenuView? = null
    private var scrim: View? = null

    /** The list we last drew or published ourselves; lets us ignore our own updates. */
    private var lastPublished: List<Target>? = null
    private var panelX = dp(8)
    private var panelY = dp(120)
    private var bubbleX = -1
    private var bubbleY = -1

    var visible = false
        private set
    private var suppressed = false
    private var minimized = false
    private var attached = false
    private var nightAtAttach = false

    private val handler = Handler(Looper.getMainLooper())
    private var flashText: String? = null
    private val clearFlash = Runnable {
        flashText = null
        undo = null
        render()
    }
    private val closeMenu = Runnable { dismissMenu() }

    /** A target removed from the panel or by the marker menu; tapping the status line brings it back. */
    private class Removed(val target: Target, val index: Int)
    private var undo: Removed? = null

    // ---- lifecycle ----------------------------------------------------------------------------

    /** Asks for the overlay. A minimized panel opens again; while the screen is locked it waits for unlock. */
    fun show() {
        if (visible) {
            if (minimized) expand()
            return
        }
        visible = true
        Session.overlayVisible.value = true
        if (!suppressed) attach()
    }

    fun hide() {
        detach()
        visible = false
        minimized = false
        Session.overlayVisible.value = false
    }

    /**
     * Screen off, always-on display or lock screen: take every window down so nothing of ours shows there,
     * and put it back (same positions, same minimized state) when the device is usable again.
     */
    fun setSuppressed(value: Boolean) {
        if (suppressed == value) return
        suppressed = value
        if (!visible) return
        if (value) detach() else attach()
    }

    private fun attach() {
        if (attached) return
        try {
            attached = true
            nightAtAttach = isNightMode(service)
            addLines()
            if (minimized) addBubble() else addPanel()
            rebuildMarkers(Session.targets.value)
            render()
        } catch (e: RuntimeException) {
            // e.g. BadTokenException when the service was revoked while we were starting.
            hide()
            toast(R.string.toast_overlay_failed)
        }
    }

    private fun detach() {
        if (!attached) return
        dismissMenu()
        handler.removeCallbacks(clearFlash)
        flashText = null
        undo = null
        removeMarkers()
        panel?.let { safeRemove(it) }
        bubble?.let { safeRemove(it) }
        lines?.let { safeRemove(it) }
        panel = null
        panelParams = null
        bubble = null
        bubbleParams = null
        lines = null
        lastPublished = null
        attached = false
    }

    /** Re-layout everything after rotation / resize / fold / theme change. Coordinates are normalized so they follow. */
    fun relayout() {
        if (!visible || !attached) return
        if (isNightMode(service) != nightAtAttach) {
            // Overlay colours are fixed at construction; rebuild to pick up the new theme.
            detach()
            attach()
            return
        }
        rebuildMarkers(Session.targets.value)
        panel?.let { applyStrings(it) }
        clampPanel()
        clampBubble()
        lines?.invalidate()
        render()
    }

    fun onTargetsChanged(list: List<Target>) {
        if (!visible || !attached || list == lastPublished) return
        val old = lastPublished
        // Editing timing fields from the app must not rebuild (and flicker) the markers.
        val sameShape = old != null && old.size == list.size && old.indices.all { old[it].samePresentation(list[it]) }
        if (sameShape) lastPublished = list else rebuildMarkers(list)
        render()
    }

    /** True if any target point lies under the panel (or the bubble), where injected taps would hit our own controls. */
    private fun coveredByPanel(): Boolean {
        val s = service.screenSize()
        val margin = dp(8)
        val left: Int
        val top: Int
        val right: Int
        val bottom: Int
        val p = panel
        val pl = panelParams
        val b = bubble
        val bl = bubbleParams
        if (p != null && pl != null) {
            // Right after show() the panel has not been laid out yet and reports a size of 0.
            if (p.width == 0 || p.height == 0) p.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
            left = pl.x
            top = pl.y
            right = pl.x + (if (p.width > 0) p.width else p.measuredWidth)
            bottom = pl.y + (if (p.height > 0) p.height else p.measuredHeight)
        } else if (b != null && bl != null) {
            left = bl.x
            top = bl.y
            right = bl.x + bubbleSize
            bottom = bl.y + bubbleSize
        } else {
            return false
        }
        fun inside(fx: Float, fy: Float): Boolean {
            val px = fx * s.width
            val py = fy * s.height
            return px in (left - margin).toFloat()..(right + margin).toFloat() && py in (top - margin).toFloat()..(bottom + margin).toFloat()
        }
        return Session.targets.value.any { t ->
            inside(t.x, t.y) || (t.kind == TargetKind.SWIPE && inside(t.x2, t.y2))
        }
    }

    fun render() {
        if (!attached) return
        val run = Session.run.value
        val active = run is RunState.Countdown || run is RunState.Running
        if (active) dismissMenu()
        applyMarkerState(active)
        bubble?.badge = Session.isBusy
        val p = panel ?: return
        val stats = Session.stats.value
        val count = Session.targets.value.size
        val busy = active || run is RunState.Paused

        // One button, three meanings: Start (idle/finished), Pause (running), Resume (paused). Words, not just icons.
        p.playPause.glyph = if (active) Glyph.PAUSE else Glyph.PLAY
        p.playPause.label = str(
            when {
                active -> R.string.panel_pause
                run is RunState.Paused -> R.string.panel_resume
                else -> R.string.panel_start
            },
        )
        p.playPause.contentDescription = p.playPause.label
        p.playPause.isEnabled = count > 0 || busy
        p.playPause.invalidate()
        p.stop.label = str(R.string.panel_stop)
        p.stop.isEnabled = busy
        p.save.isEnabled = count > 0
        p.addTap.isEnabled = !busy
        p.addSwipe.isEnabled = !busy
        p.removeLast.isEnabled = !busy && count > 0
        val flash = flashText
        p.status.text = flash ?: statusText(run, stats, count)
        p.status.setTextColor(if (flash != null) p.pal.warn else p.pal.text)
        p.requestLayout()
    }

    /**
     * While a run is active the markers must not intercept the very taps we inject. This runs whether or not
     * the panel exists (it is gone while minimized), otherwise the markers would swallow the first tap again.
     */
    private fun applyMarkerState(active: Boolean) {
        val touchable = !active
        for (m in markers) {
            val flags = if (touchable) m.params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            else m.params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            if (flags != m.params.flags) {
                m.params.flags = flags
                updateLayout(m.view, m.params)
            }
            m.view.alpha = if (touchable) 1f else 0.45f
        }
    }

    private fun statusText(run: RunState, stats: Stats, count: Int): String = when (run) {
        RunState.Idle -> service.localizedContext().resources.getQuantityString(R.plurals.panel_targets, count, count)
        is RunState.Countdown -> str(R.string.panel_countdown, run.seconds)
        RunState.Running -> str(R.string.panel_running, stats.actions)
        RunState.Paused -> str(R.string.panel_paused, stats.actions)
        is RunState.Finished -> str(R.string.panel_finished, stats.actions)
    }

    // ---- windows ------------------------------------------------------------------------------

    private fun params(w: Int, h: Int, touchable: Boolean) = WindowManager.LayoutParams(
        w, h,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            (if (touchable) 0 else WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE),
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        // Let overlays reach into the notch area so coordinates stay display-absolute on cutout devices.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
    }

    private fun addLines() {
        val v = LinesView(service) {
            markers.groupBy { it.targetId }.values.mapNotNull { pair ->
                val start = pair.firstOrNull { !it.isEnd } ?: return@mapNotNull null
                val end = pair.firstOrNull { it.isEnd } ?: return@mapNotNull null
                centerOf(start) to centerOf(end)
            }
        }
        wm.addView(v, params(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, false))
        lines = v
    }

    private fun centerOf(m: Marker) = PointF(m.params.x + markerSize / 2f, m.params.y + markerSize / 2f)

    private fun addPanel() {
        val p = PanelView(service, ::dp, PanelLabels(str(R.string.panel_start), str(R.string.panel_pause), str(R.string.panel_resume), str(R.string.panel_stop)))
        val lp = params(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, true)
        lp.x = panelX
        lp.y = panelY
        wm.addView(p, lp)
        panel = p
        panelParams = lp
        wireButtons(p)
        p.post { clampPanel() }
    }

    private fun applyStrings(p: PanelView) {
        p.grip.contentDescription = str(R.string.cd_drag)
        p.stop.contentDescription = str(R.string.cd_stop)
        p.stop.label = str(R.string.panel_stop)
        p.addTap.contentDescription = str(R.string.cd_add_tap)
        p.addSwipe.contentDescription = str(R.string.cd_add_swipe)
        p.removeLast.contentDescription = str(R.string.cd_remove_last)
        p.save.contentDescription = str(R.string.cd_save)
        p.openApp.contentDescription = str(R.string.cd_open_app)
        p.minimize.contentDescription = str(R.string.cd_minimize)
        p.close.contentDescription = str(R.string.cd_close)
    }

    private fun wireButtons(p: PanelView) {
        applyStrings(p)

        p.playPause.setOnClickListener { onPlayPause() }
        p.stop.setOnClickListener { service.engine.stop(StopReason.USER) }
        p.addTap.setOnClickListener { addTarget(TargetKind.TAP) }
        p.addSwipe.setOnClickListener { addTarget(TargetKind.SWIPE) }
        p.removeLast.setOnClickListener {
            // "Last" means the highest number on screen, which is not always the one added most recently.
            Session.targets.value.steps().lastOrNull()?.lastOrNull()?.let { remove(it.id) }
        }
        p.save.setOnClickListener { saveScript() }
        p.openApp.setOnClickListener {
            service.startActivity(
                Intent(service, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
            )
        }
        p.minimize.setOnClickListener { minimize() }
        p.close.setOnClickListener {
            if (Session.isBusy) service.engine.stop(StopReason.USER)
            hide()
        }

        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var dragged = false
        // Both the grip and the status text drag the panel: a bigger target than the grip alone.
        // A tap on the status line (no drag) restores the last removed target while the offer is shown.
        val drag = View.OnTouchListener { v, e ->
            val lp = panelParams ?: return@OnTouchListener false
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; startX = lp.x; startY = lp.y
                    dragged = false
                    v.isPressed = true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!dragged && hypot(e.rawX - downX, e.rawY - downY) > touchSlop) dragged = true
                    if (dragged) {
                        lp.x = startX + (e.rawX - downX).roundToInt()
                        lp.y = startY + (e.rawY - downY).roundToInt()
                        clampPanel()
                    }
                }
                MotionEvent.ACTION_UP -> {
                    v.isPressed = false
                    if (!dragged && v === p.status) undoRemove()
                }
                MotionEvent.ACTION_CANCEL -> v.isPressed = false
            }
            true
        }
        p.grip.setOnTouchListener(drag)
        p.status.setOnTouchListener(drag)
    }

    // ---- minimize -----------------------------------------------------------------------------

    /** Folds the panel into a small bubble. The run (if any) keeps going; tap the bubble to get the panel back. */
    private fun minimize() {
        if (minimized || !attached) return
        dismissMenu()
        panel?.let { safeRemove(it) }
        panel = null
        panelParams = null
        minimized = true
        addBubble()
        render()
    }

    private fun expand() {
        if (!minimized) return
        bubble?.let { safeRemove(it) }
        bubble = null
        bubbleParams = null
        minimized = false
        if (attached) {
            addPanel()
            render()
        }
    }

    private fun addBubble() {
        val b = bubbleView(service)
        b.contentDescription = str(R.string.cd_bubble)
        val s = service.screenSize()
        val lp = params(bubbleSize, bubbleSize, true)
        // First time: the right edge, level with where the panel was.
        lp.x = if (bubbleX >= 0) bubbleX else s.width - bubbleSize - dp(4)
        lp.y = if (bubbleY >= 0) bubbleY else panelY
        wm.addView(b, lp)
        bubble = b
        bubbleParams = lp
        clampBubble()

        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var dragged = false
        b.setOnTouchListener { v, e ->
            val params = bubbleParams ?: return@setOnTouchListener false
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; startX = params.x; startY = params.y
                    dragged = false
                    v.isPressed = true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!dragged && hypot(e.rawX - downX, e.rawY - downY) > touchSlop) dragged = true
                    if (dragged) {
                        params.x = startX + (e.rawX - downX).roundToInt()
                        params.y = startY + (e.rawY - downY).roundToInt()
                        clampBubble()
                    }
                }
                MotionEvent.ACTION_UP -> {
                    v.isPressed = false
                    if (dragged) snapBubble() else expand()
                }
                MotionEvent.ACTION_CANCEL -> v.isPressed = false
            }
            true
        }
    }

    private fun clampBubble() {
        val b = bubble ?: return
        val lp = bubbleParams ?: return
        val s = service.screenSize()
        lp.x = lp.x.coerceIn(0, max(0, s.width - bubbleSize))
        lp.y = lp.y.coerceIn(0, max(0, s.height - bubbleSize))
        bubbleX = lp.x
        bubbleY = lp.y
        updateLayout(b, lp)
    }

    /** After a drag the bubble settles against the nearest side edge, out of the middle of the screen where targets live. */
    private fun snapBubble() {
        val lp = bubbleParams ?: return
        val s = service.screenSize()
        lp.x = if (lp.x + bubbleSize / 2 < s.width / 2) dp(4) else s.width - bubbleSize - dp(4)
        clampBubble()
    }

    // ---- actions ------------------------------------------------------------------------------

    /**
     * Quick save without a name prompt (the overlay cannot take keyboard focus): overwrite the script
     * that is currently loaded, otherwise add a new "Script N". Rename it later in the app.
     */
    private fun saveScript() {
        val targets = Session.targets.value
        if (targets.isEmpty()) {
            toast(R.string.toast_no_targets)
            return
        }
        val existing = Session.loadedScriptId.value?.let(Store::find)
        val count = Store.scripts.value.size
        if (existing == null && count >= Limits.MAX_SCRIPTS) {
            toast(R.string.err_library_full)
            return
        }
        val saved = Store.saveScript(existing?.name ?: str(R.string.save_default_name, count + 1), targets, Session.config.value, existing?.id)
        Session.loadedScriptId.value = saved.id
        flash(str(R.string.saved_snack, saved.name))
    }

    /** Start/stop from outside the panel (Quick Settings tile). Same guards as the panel's own button. */
    fun toggleRun() {
        when {
            Session.isBusy -> service.engine.stop(StopReason.USER)
            Session.targets.value.isEmpty() -> show() // nothing to run yet: bring up the panel so targets can be placed
            attached && coveredByPanel() -> toast(R.string.toast_panel_covers)
            else -> service.engine.start()
        }
    }

    /**
     * Start from the app's own Start button. The panel comes up with the run (it is the stop control; a
     * minimized one stays minimized) and the countdown is at least 2 s so the app can leave the foreground.
     * False if nothing started.
     */
    fun startFromApp(): Boolean {
        if (Session.targets.value.isEmpty()) return false
        if (!visible) show()
        if (coveredByPanel()) {
            toast(R.string.toast_panel_covers)
            return false
        }
        service.engine.start(minDelaySec = APP_START_DELAY_SEC)
        return true
    }

    fun onPlayPause() {
        when (Session.run.value) {
            is RunState.Countdown, RunState.Running -> service.engine.pause()
            RunState.Paused -> if (coveredByPanel()) toast(R.string.toast_panel_covers) else service.engine.resume()
            else -> when {
                Session.targets.value.isEmpty() -> toast(R.string.toast_no_targets)
                coveredByPanel() -> toast(R.string.toast_panel_covers)
                else -> service.engine.start()
            }
        }
    }

    private fun clampPanel() {
        val p = panel ?: return
        val lp = panelParams ?: return
        val s = service.screenSize()
        p.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        lp.x = lp.x.coerceIn(0, max(0, s.width - p.measuredWidth))
        lp.y = lp.y.coerceIn(0, max(0, s.height - p.measuredHeight))
        panelX = lp.x
        panelY = lp.y
        updateLayout(p, lp)
    }

    // ---- markers ------------------------------------------------------------------------------

    private fun remove(id: Long) {
        val list = Session.targets.value
        val index = list.indexOfFirst { it.id == id }
        if (index < 0) return
        val gone = list[index]
        val label = list.labels()[id]?.text().orEmpty()
        Session.targets.value = list.filterNot { it.id == id }
        flash(str(R.string.panel_removed, label), UNDO_MS)
        undo = Removed(gone, index) // after flash(), which clears any older offer
    }

    private fun undoRemove() {
        val r = undo ?: return
        undo = null
        val list = Session.targets.value
        if (list.size >= Limits.MAX_TARGETS || list.any { it.id == r.target.id }) return
        Session.targets.value = list.toMutableList().also { it.add(r.index.coerceIn(0, it.size), r.target) }
        flash(str(R.string.panel_restored))
    }

    /** Copy of a target, right next to it and in the same step, so the two are performed together. */
    private fun cloneTarget(id: Long) {
        if (Session.isBusy) return
        val dx = dp(52).toFloat() / service.screenSize().width
        val result = Session.targets.value.withClone(id, Session.newTargetId(), dx)
        if (result == null) {
            toast(R.string.toast_max_targets)
            return
        }
        Session.targets.value = result.first
        flash(str(R.string.panel_cloned, result.first.labels()[result.second.id]?.text().orEmpty()))
    }

    private fun addTarget(kind: TargetKind) {
        if (Session.isBusy) return
        val list = Session.targets.value
        if (list.size >= Limits.MAX_TARGETS) {
            toast(R.string.toast_max_targets)
            return
        }
        val off = (list.size % 6) * 0.04f
        val step = (list.maxOfOrNull { it.step } ?: 0) + 1
        val t = when (kind) {
            TargetKind.TAP -> Target(Session.newTargetId(), kind, 0.5f, 0.4f + off, step = step)
            TargetKind.SWIPE -> Target(Session.newTargetId(), kind, 0.3f, 0.5f + off, 0.7f, 0.5f + off, step = step)
        }
        Session.targets.value = list + t
    }

    private fun removeMarkers() {
        markers.forEach { safeRemove(it.view) }
        markers.clear()
    }

    private fun rebuildMarkers(list: List<Target>) {
        dismissMenu()
        removeMarkers()
        lastPublished = list
        val s = service.screenSize()
        val names = list.labels()
        list.forEach { t ->
            val label = names[t.id]?.text() ?: "?"
            val tag = if (t.taps > 1) "×" + t.taps else if (t.isLongPress) "L" else ""
            addMarker(t.id, false, label, tag, if (t.kind == TargetKind.TAP) MarkerStyle.TAP else MarkerStyle.SWIPE_START, t.x, t.y, s)
            if (t.kind == TargetKind.SWIPE) addMarker(t.id, true, label, "", MarkerStyle.SWIPE_END, t.x2, t.y2, s)
        }
        lines?.invalidate()
    }

    private fun addMarker(id: Long, isEnd: Boolean, label: String, tag: String, style: MarkerStyle, fx: Float, fy: Float, s: ScreenSize) {
        val view = MarkerView(service, label, style, tag)
        view.contentDescription = str(R.string.cd_marker, label)
        val lp = params(markerSize, markerSize, !Session.isActive)
        lp.x = (fx * s.width - markerSize / 2f).roundToInt()
        lp.y = (fy * s.height - markerSize / 2f).roundToInt()
        val m = Marker(id, isEnd, view, lp)
        attachDrag(m)
        wm.addView(view, lp)
        markers += m
    }

    private fun attachDrag(m: Marker) {
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var moved = false
        val detector = GestureDetector(service, object : GestureDetector.SimpleOnGestureListener() {
            override fun onLongPress(e: MotionEvent) {
                if (moved) return
                if (AppSettings.haptics.value) m.view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                showMenu(m)
            }
        })
        m.view.setOnTouchListener { v, e ->
            detector.onTouchEvent(e)
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY
                    startX = m.params.x; startY = m.params.y
                    moved = false
                    v.isPressed = true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!moved && hypot(dx, dy) > touchSlop) {
                        moved = true
                        // The finger kept going after the long press: it is a drag, not a menu request.
                        dismissMenu()
                    }
                    if (moved) {
                        val s = service.screenSize()
                        m.params.x = (startX + dx).roundToInt().coerceIn(-markerSize / 2, s.width - markerSize / 2)
                        m.params.y = (startY + dy).roundToInt().coerceIn(-markerSize / 2, s.height - markerSize / 2)
                        updateLayout(v, m.params)
                        lines?.invalidate()
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    if (moved) commit(m)
                }
            }
            true
        }
    }

    /** Publish the marker's final position (measured on screen) as normalized coordinates. */
    private fun commit(m: Marker) {
        val s = service.screenSize()
        val loc = IntArray(2)
        m.view.getLocationOnScreen(loc)
        val fx = ((loc[0] + m.view.width / 2f) / s.width).coerceIn(0f, 1f)
        val fy = ((loc[1] + m.view.height / 2f) / s.height).coerceIn(0f, 1f)
        val updated = Session.targets.value.map {
            if (it.id != m.targetId) it
            else if (m.isEnd) it.copy(x2 = fx, y2 = fy) else it.copy(x = fx, y = fy)
        }
        lastPublished = updated
        Session.targets.value = updated
    }

    // ---- marker menu --------------------------------------------------------------------------

    /**
     * Clone / Delete popup above (or below) the marker. A transparent full-screen scrim sits behind it:
     * a tap anywhere else only closes the menu, the way a popup normally behaves.
     */
    private fun showMenu(m: Marker) {
        if (Session.isActive || !attached) return
        dismissMenu()
        val s = service.screenSize()
        val back = View(service)
        back.setOnTouchListener { _, e ->
            if (e.actionMasked == MotionEvent.ACTION_DOWN) dismissMenu()
            true
        }
        val v = MenuView(service, ::dp, str(R.string.menu_clone), str(R.string.btn_delete))
        v.clone.isEnabled = !Session.isBusy
        v.clone.setOnClickListener { dismissMenu(); cloneTarget(m.targetId) }
        v.delete.setOnClickListener { dismissMenu(); remove(m.targetId) }
        v.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val lp = params(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, true)
        lp.x = (m.params.x + markerSize / 2 - v.measuredWidth / 2).coerceIn(0, max(0, s.width - v.measuredWidth))
        val above = m.params.y - v.measuredHeight - dp(4)
        lp.y = if (above >= 0) above else m.params.y + markerSize + dp(4)
        try {
            wm.addView(back, params(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, true))
            wm.addView(v, lp)
        } catch (e: RuntimeException) {
            safeRemove(back)
            return
        }
        scrim = back
        menu = v
        handler.postDelayed(closeMenu, MENU_MS)
    }

    private fun dismissMenu() {
        handler.removeCallbacks(closeMenu)
        menu?.let { safeRemove(it) }
        scrim?.let { safeRemove(it) }
        menu = null
        scrim = null
    }

    // ---- helpers ------------------------------------------------------------------------------

    private fun updateLayout(v: View, lp: WindowManager.LayoutParams) {
        try {
            wm.updateViewLayout(v, lp)
        } catch (e: IllegalArgumentException) {
            // View already detached (e.g. removed by a concurrent rebuild).
        }
    }

    private fun safeRemove(v: View) {
        try {
            wm.removeViewImmediate(v)
        } catch (e: IllegalArgumentException) {
            // Not attached.
        }
    }

    private fun str(id: Int, vararg args: Any): String = service.localizedContext().getString(id, *args)

    /**
     * Feedback shown in the panel's status line. Toasts are not an option while the overlay is up: Android
     * suppresses toasts from an app without notification permission while another app is in the foreground.
     */
    private fun toast(res: Int) {
        if (!attached) {
            Toast.makeText(service, str(res), Toast.LENGTH_SHORT).show()
            return
        }
        flash(str(res))
    }

    private fun flash(text: String, durationMs: Long = FLASH_MS) {
        // A minimized panel has no status line; bring it back so the message (and an undo offer) can be seen.
        if (minimized && attached) expand()
        flashText = text
        undo = null
        handler.removeCallbacks(clearFlash)
        handler.postDelayed(clearFlash, durationMs)
        render()
    }
}
