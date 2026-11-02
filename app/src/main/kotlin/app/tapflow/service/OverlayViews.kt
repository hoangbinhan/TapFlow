package app.tapflow.service

import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/** Overlay colours, resolved from the system light/dark setting (the overlay does not use Compose or app themes). */
class OverlayPalette(
    val surface: Int,
    val outline: Int,
    val text: Int,
    val muted: Int,
    val chip: Int,
    val accent: Int,
    val onAccent: Int,
    val dangerSoft: Int,
    val danger: Int,
    val warn: Int,
)

private val LIGHT = OverlayPalette(
    surface = 0xFFFFFFFF.toInt(), outline = 0xFFD5D9E0.toInt(), text = 0xFF14171F.toInt(), muted = 0xFF5B6372.toInt(),
    chip = 0xFFEEF0F4.toInt(), accent = 0xFF2F5BEA.toInt(), onAccent = 0xFFFFFFFF.toInt(),
    dangerSoft = 0xFFFDECEE.toInt(), danger = 0xFFC62839.toInt(), warn = 0xFF9A5B00.toInt(),
)

private val DARK = OverlayPalette(
    surface = 0xFF1B1F27.toInt(), outline = 0xFF363C48.toInt(), text = 0xFFECEFF4.toInt(), muted = 0xFF9AA3B2.toInt(),
    chip = 0xFF2A303B.toInt(), accent = 0xFF7C9BFF.toInt(), onAccent = 0xFF0B1533.toInt(),
    dangerSoft = 0xFF3A1F24.toInt(), danger = 0xFFFF7A86.toInt(), warn = 0xFFF0B04D.toInt(),
)

fun isNightMode(context: Context): Boolean =
    context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

fun overlayPalette(context: Context): OverlayPalette = if (isNightMode(context)) DARK else LIGHT

private const val RUNNING_DOT = 0xFF1FA463.toInt()

enum class Glyph { GRIP, PLAY, PAUSE, STOP, TAP, SWIPE, TRASH, APP, CLOSE, SAVE, MINIMIZE, CLONE }

/**
 * A button drawn in code, so the overlay needs no resource or theme lookups. With a [label] it shows the
 * glyph and the text side by side; without one, only the glyph. [fill] 0 means no background.
 */
class IconView(
    context: Context,
    var glyph: Glyph,
    private val fill: Int,
    private val content: Int,
    label: String = "",
    private val cornerDp: Float = 10f,
    /** Colour of the small dot shown while [badge] is true (used by the minimized bubble to say "a run is going"). */
    private val badgeColor: Int = 0,
) : View(context) {
    var badge: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    var label: String = label
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    private val d = resources.displayMetrics.density
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = content
    }
    private val solid = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = content }
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fill }
    private val pressedShade = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x26000000 }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = content
        typeface = Typeface.DEFAULT_BOLD
    }
    private val box = RectF()
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = badgeColor }
    private val dotRing = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }

    override fun setPressed(pressed: Boolean) {
        super.setPressed(pressed)
        invalidate()
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        alpha = if (enabled) 1f else 0.4f
    }

    override fun onDraw(c: Canvas) {
        if (fill != 0) {
            box.set(0f, 0f, width.toFloat(), height.toFloat())
            val r = cornerDp * d
            c.drawRoundRect(box, r, r, bg)
            if (isPressed) c.drawRoundRect(box, r, r, pressedShade)
        }
        val gs = height * (if (fill == 0) 0.75f else 0.52f)
        val gap = 6f * d
        val pad = 10f * d
        var textW = 0f
        if (label.isNotEmpty()) {
            // Shrink long translations / large font scales instead of clipping them.
            var size = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 14f, resources.displayMetrics)
            text.textSize = size
            val room = width - pad * 2 - gs - gap
            while (text.measureText(label) > room && size > 8f * d) {
                size *= 0.92f
                text.textSize = size
            }
            textW = text.measureText(label)
        }
        val total = gs + if (label.isEmpty()) 0f else gap + textW
        val left = (width - total) / 2f
        c.save()
        c.translate(left, (height - gs) / 2f)
        drawGlyph(c, gs / 24f)
        c.restore()
        if (label.isNotEmpty()) {
            c.drawText(label, left + gs + gap, height / 2f - (text.descent() + text.ascent()) / 2f, text)
        }
        if (badge && badgeColor != 0) {
            val r = 5f * d
            c.drawCircle(width - r - d, r + d, r + 1.5f * d, dotRing)
            c.drawCircle(width - r - d, r + d, r, dot)
        }
    }

    private fun drawGlyph(c: Canvas, u: Float) {
        stroke.strokeWidth = 2f * u
        when (glyph) {
            Glyph.GRIP -> for (col in 0..1) for (row in 0..2) {
                c.drawCircle((9f + col * 6f) * u, (6f + row * 6f) * u, 1.5f * u, solid)
            }
            Glyph.PLAY -> c.drawPath(Path().apply {
                moveTo(8f * u, 5.5f * u); lineTo(19f * u, 12f * u); lineTo(8f * u, 18.5f * u); close()
            }, solid)
            Glyph.PAUSE -> {
                c.drawRoundRect(7f * u, 5.5f * u, 10.5f * u, 18.5f * u, u, u, solid)
                c.drawRoundRect(13.5f * u, 5.5f * u, 17f * u, 18.5f * u, u, u, solid)
            }
            Glyph.STOP -> c.drawRoundRect(6.5f * u, 6.5f * u, 17.5f * u, 17.5f * u, 2f * u, 2f * u, solid)
            Glyph.TAP -> {
                c.drawCircle(12f * u, 12f * u, 7f * u, stroke)
                c.drawCircle(12f * u, 12f * u, 2.5f * u, solid)
            }
            Glyph.SWIPE -> {
                c.drawCircle(5f * u, 12f * u, 1.8f * u, solid)
                c.drawLine(6f * u, 12f * u, 19f * u, 12f * u, stroke)
                c.drawPath(Path().apply { moveTo(14f * u, 7f * u); lineTo(19f * u, 12f * u); lineTo(14f * u, 17f * u) }, stroke)
            }
            Glyph.TRASH -> {
                c.drawLine(5.5f * u, 7f * u, 18.5f * u, 7f * u, stroke)
                c.drawLine(9.5f * u, 4.5f * u, 14.5f * u, 4.5f * u, stroke)
                c.drawPath(Path().apply {
                    moveTo(7f * u, 7f * u); lineTo(8f * u, 19f * u); lineTo(16f * u, 19f * u); lineTo(17f * u, 7f * u)
                }, stroke)
            }
            Glyph.APP -> {
                c.drawRoundRect(6f * u, 4f * u, 18f * u, 20f * u, 3f * u, 3f * u, stroke)
                c.drawLine(10f * u, 17f * u, 14f * u, 17f * u, stroke)
            }
            Glyph.CLOSE -> {
                c.drawLine(7f * u, 7f * u, 17f * u, 17f * u, stroke)
                c.drawLine(17f * u, 7f * u, 7f * u, 17f * u, stroke)
            }
            Glyph.MINIMIZE -> c.drawLine(6.5f * u, 17f * u, 17.5f * u, 17f * u, stroke)
            Glyph.CLONE -> {
                c.drawRoundRect(4.5f * u, 8.5f * u, 15.5f * u, 19.5f * u, 2.5f * u, 2.5f * u, stroke)
                c.drawPath(Path().apply {
                    moveTo(8.5f * u, 8.5f * u); lineTo(8.5f * u, 6.5f * u)
                    quadTo(8.5f * u, 4.5f * u, 10.5f * u, 4.5f * u); lineTo(17.5f * u, 4.5f * u)
                    quadTo(19.5f * u, 4.5f * u, 19.5f * u, 6.5f * u); lineTo(19.5f * u, 13.5f * u)
                    quadTo(19.5f * u, 15.5f * u, 17.5f * u, 15.5f * u); lineTo(15.5f * u, 15.5f * u)
                }, stroke)
            }
            Glyph.SAVE -> {
                c.drawPath(Path().apply {
                    moveTo(5f * u, 5f * u); lineTo(16f * u, 5f * u); lineTo(19f * u, 8f * u)
                    lineTo(19f * u, 19f * u); lineTo(5f * u, 19f * u); close()
                }, stroke)
                c.drawLine(8f * u, 5f * u, 8f * u, 9f * u, stroke)
                c.drawLine(8f * u, 9f * u, 15f * u, 9f * u, stroke)
                c.drawRect(8f * u, 13f * u, 16f * u, 19f * u, stroke)
            }
        }
    }
}

enum class MarkerStyle { TAP, SWIPE_START, SWIPE_END }

/**
 * A draggable numbered marker. Shape tells the kind apart (circle = tap, rounded square = swipe start,
 * outlined square with an arrow = swipe end); colour is the single accent.
 */
class MarkerView(
    context: Context,
    private val label: String,
    private val markerStyle: MarkerStyle,
    /** Short extra mark, e.g. "×2" for a double tap or "L" for a long press. */
    private val tag: String = "",
) : View(context) {
    private val pal = overlayPalette(context)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = if (markerStyle == MarkerStyle.SWIPE_END) pal.surface else pal.accent
    }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0xFFFFFFFF.toInt() }
    private val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0x40000000 }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = pal.accent }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = if (markerStyle == MarkerStyle.SWIPE_END) pal.accent else pal.onAccent
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }
    private val tagBox = RectF()
    private val tagFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF14171F.toInt() }
    private val tagText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }
    private val body = RectF()

    override fun setPressed(pressed: Boolean) {
        super.setPressed(pressed)
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val grow = if (isPressed) width * 0.06f else 0f
        val r = width / 2f - width * 0.1f + grow
        val w = width * 0.045f
        ring.strokeWidth = w
        halo.strokeWidth = w * 0.6f
        edge.strokeWidth = w
        if (markerStyle == MarkerStyle.TAP) {
            c.drawCircle(cx, cy, r, fill)
            c.drawCircle(cx, cy, r + w * 0.15f, halo)
            c.drawCircle(cx, cy, r - w * 0.1f, ring)
        } else {
            body.set(cx - r, cy - r, cx + r, cy + r)
            val corner = r * 0.42f
            c.drawRoundRect(body, corner, corner, fill)
            if (markerStyle == MarkerStyle.SWIPE_END) c.drawRoundRect(body, corner, corner, edge)
            else c.drawRoundRect(body, corner, corner, ring)
            body.inset(-w * 0.3f, -w * 0.3f)
            c.drawRoundRect(body, corner, corner, halo)
        }
        text.textSize = width * (if (label.length > 2) 0.3f else 0.4f)
        val txt = if (markerStyle == MarkerStyle.SWIPE_END) "→" else label
        c.drawText(txt, cx, cy - (text.descent() + text.ascent()) / 2f, text)
        if (tag.isNotEmpty() && markerStyle != MarkerStyle.SWIPE_END) {
            // Small dark badge in the top-right corner.
            val bw = width * 0.42f
            val bh = width * 0.3f
            val bx = width - bw
            tagBox.set(bx, 0f, bx + bw, bh)
            c.drawRoundRect(tagBox, bh / 2f, bh / 2f, tagFill)
            tagText.textSize = bh * 0.72f
            c.drawText(tag, tagBox.centerX(), tagBox.centerY() - (tagText.descent() + tagText.ascent()) / 2f, tagText)
        }
    }
}

/** Full-screen, never-touchable layer that draws arrows between swipe start/end markers. */
class LinesView(context: Context, private val segments: () -> List<Pair<PointF, PointF>>) : View(context) {
    private val pal = overlayPalette(context)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = pal.accent
        strokeCap = Paint.Cap.ROUND
        strokeWidth = resources.displayMetrics.density * 3f
    }
    private val halo = Paint(line).apply { color = 0xFFFFFFFF.toInt(); strokeWidth = resources.displayMetrics.density * 6f }
    private val head = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = pal.accent }
    private val headEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = 0xFFFFFFFF.toInt(); strokeJoin = Paint.Join.ROUND
        strokeWidth = resources.displayMetrics.density * 2f
    }

    private val origin = IntArray(2)

    override fun onDraw(c: Canvas) {
        val d = resources.displayMetrics.density
        // Segments are in screen coordinates; this window's own origin can be inset by system bars.
        getLocationOnScreen(origin)
        c.translate(-origin[0].toFloat(), -origin[1].toFloat())
        for ((a, b) in segments()) {
            c.drawLine(a.x, a.y, b.x, b.y, halo)
            c.drawLine(a.x, a.y, b.x, b.y, line)
            val ang = atan2(b.y - a.y, b.x - a.x)
            val len = 14f * d
            val p = Path().apply {
                moveTo(b.x, b.y)
                lineTo(b.x - len * cos(ang - 0.45f), b.y - len * sin(ang - 0.45f))
                lineTo(b.x - len * cos(ang + 0.45f), b.y - len * sin(ang + 0.45f))
                close()
            }
            c.drawPath(p, head)
            c.drawPath(p, headEdge)
        }
    }
}

/**
 * The floating control bar, three fixed rows so nothing shifts between idle and running:
 * header (drag grip, status, close), the two run controls with text labels, and the editing tools.
 * A soft, symmetric shadow is layered inside the window, because the window is only as big as the view.
 */
class PanelView(context: Context, private val dp: (Int) -> Int, labels: PanelLabels) : LinearLayout(context) {
    val pal = overlayPalette(context)

    val grip = IconView(context, Glyph.GRIP, fill = 0, content = pal.muted)
    val status = TextView(context)
    val minimize = IconView(context, Glyph.MINIMIZE, fill = pal.chip, content = pal.text)
    val close = IconView(context, Glyph.CLOSE, fill = pal.chip, content = pal.text)
    val playPause = IconView(context, Glyph.PLAY, fill = pal.accent, content = pal.onAccent, label = labels.start)
    val stop = IconView(context, Glyph.STOP, fill = pal.dangerSoft, content = pal.danger, label = labels.stop)
    val addTap = IconView(context, Glyph.TAP, fill = pal.chip, content = pal.text)
    val addSwipe = IconView(context, Glyph.SWIPE, fill = pal.chip, content = pal.text)
    val removeLast = IconView(context, Glyph.TRASH, fill = pal.chip, content = pal.text)
    val save = IconView(context, Glyph.SAVE, fill = pal.chip, content = pal.text)
    val openApp = IconView(context, Glyph.APP, fill = pal.chip, content = pal.text)

    init {
        orientation = VERTICAL
        applyCard(this, pal, dp)

        val cell = dp(44)
        val gap = dp(6)
        val tools = listOf(addTap, addSwipe, removeLast, save, openApp)
        val inner = cell * tools.size + gap * (tools.size - 1)

        val header = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(grip, LayoutParams(dp(24), dp(34)))
        status.apply {
            setTextColor(pal.text)
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            // Two lines fit the fixed height, so a long translation never makes the panel jump.
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            minHeight = dp(34)
            setPadding(dp(4), 0, dp(4), 0)
        }
        header.addView(status, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        header.addView(minimize, LayoutParams(dp(32), dp(32)).apply { rightMargin = dp(4) })
        header.addView(close, LayoutParams(dp(32), dp(32)))
        addView(header, LayoutParams(inner, LayoutParams.WRAP_CONTENT))

        val run = LinearLayout(context)
        run.addView(playPause, LayoutParams(0, cell, 1f))
        run.addView(stop, LayoutParams(0, cell, 1f).apply { leftMargin = gap })
        addView(run, LayoutParams(inner, cell).apply { topMargin = dp(6) })

        val row = LinearLayout(context)
        tools.forEachIndexed { i, b -> row.addView(b, LayoutParams(cell, cell).apply { if (i > 0) leftMargin = gap }) }
        addView(row, LayoutParams(inner, cell).apply { topMargin = gap })
    }
}

/** Text shown on the two labelled run controls; resolved by the controller so it follows the app language. */
class PanelLabels(val start: String, val pause: String, val resume: String, val stop: String)

/**
 * Rounded card with a soft, symmetric shadow layered inside the view, because an overlay window is only
 * as big as its content and would clip a real shadow. Sets matching padding.
 */
private fun applyCard(view: LinearLayout, pal: OverlayPalette, dp: (Int) -> Int) {
    val radius = dp(16).toFloat()
    fun layer(color: Int, stroke: Int = 0) = GradientDrawable().apply {
        cornerRadius = radius
        setColor(color)
        if (stroke != 0) setStroke(dp(1), stroke)
    }
    view.background = LayerDrawable(
        arrayOf(layer(0x0F000000), layer(0x14000000), layer(0x1A000000), layer(pal.surface, pal.outline)),
    ).apply {
        setLayerInset(0, 0, 0, 0, 0)
        setLayerInset(1, dp(1), dp(1), dp(1), dp(1))
        setLayerInset(2, dp(2), dp(2), dp(2), dp(2))
        setLayerInset(3, dp(3), dp(3), dp(3), dp(3))
    }
    val pad = dp(13)
    view.setPadding(pad, pad, pad, pad)
}

/** Small popup shown next to a marker after a long press. */
class MenuView(context: Context, dp: (Int) -> Int, cloneLabel: String, deleteLabel: String) : LinearLayout(context) {
    private val pal = overlayPalette(context)
    val clone = IconView(context, Glyph.CLONE, fill = pal.chip, content = pal.text, label = cloneLabel)
    val delete = IconView(context, Glyph.TRASH, fill = pal.dangerSoft, content = pal.danger, label = deleteLabel)

    init {
        orientation = HORIZONTAL
        applyCard(this, pal, dp)
        addView(clone, LayoutParams(dp(112), dp(44)))
        addView(delete, LayoutParams(dp(112), dp(44)).apply { leftMargin = dp(6) })
    }
}

/** The minimized panel: one small round button that brings the panel back. A dot says a run is going. */
fun bubbleView(context: Context): IconView {
    val pal = overlayPalette(context)
    return IconView(context, Glyph.TAP, fill = pal.accent, content = pal.onAccent, cornerDp = 22f, badgeColor = RUNNING_DOT)
}
