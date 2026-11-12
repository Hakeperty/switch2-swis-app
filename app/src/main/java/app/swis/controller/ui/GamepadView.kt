package app.swis.controller.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import app.swis.controller.core.Button
import app.swis.controller.core.ControllerHub
import app.swis.controller.core.ControllerHub.Link
import app.swis.controller.core.PadState
import kotlin.math.hypot
import kotlin.math.min

/**
 * The on-screen Pro Controller. Handles any number of fingers at once, lets a finger slide
 * from one button to the next, and feeds the result straight into [ControllerHub].
 */
class GamepadView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    interface Actions {
        fun onMacroToggle()
        fun onMacroPick()
        fun onRecordToggle()
        fun onMenu()
    }

    var actions: Actions? = null

    /** Shown on the macro button. */
    var macroName: String = ""
        set(value) {
            field = value
            invalidate()
        }

    private enum class Shape { CIRCLE, PILL }
    private enum class Action { MACRO, PICK, RECORD, MENU }

    /** A control placed in fractions of the view: x across the width, y and size of the height. */
    private class Key(
        val button: Button?,
        val action: Action?,
        val label: String,
        val fx: Float,
        val fy: Float,
        val fw: Float,
        val fh: Float,
        val shape: Shape,
    ) {
        val bounds = RectF()
        fun contains(x: Float, y: Float, slop: Float): Boolean =
            if (shape == Shape.CIRCLE) {
                hypot(x - bounds.centerX(), y - bounds.centerY()) <= bounds.width() / 2f + slop
            } else {
                x >= bounds.left - slop && x <= bounds.right + slop && y >= bounds.top - slop && y <= bounds.bottom + slop
            }
    }

    private class Stick(val left: Boolean, val fx: Float, val fy: Float, val fr: Float) {
        var cx = 0f
        var cy = 0f
        var radius = 0f
        var x = 0f
        var y = 0f
        var pointer = -1
    }

    private val keys = listOf(
        key(Button.ZL, "ZL", 0.085f, 0.10f, 0.26f, 0.14f, Shape.PILL),
        key(Button.L, "L", 0.225f, 0.10f, 0.26f, 0.14f, Shape.PILL),
        key(Button.R, "R", 0.775f, 0.10f, 0.26f, 0.14f, Shape.PILL),
        key(Button.ZR, "ZR", 0.915f, 0.10f, 0.26f, 0.14f, Shape.PILL),

        key(Button.MINUS, "−", 0.365f, 0.10f, 0.12f, 0.12f, Shape.CIRCLE),
        key(Button.CAPTURE, "CAP", 0.435f, 0.10f, 0.12f, 0.12f, Shape.CIRCLE),
        key(Button.HOME, "HOME", 0.565f, 0.10f, 0.12f, 0.12f, Shape.CIRCLE),
        key(Button.PLUS, "+", 0.635f, 0.10f, 0.12f, 0.12f, Shape.CIRCLE),

        key(Button.L_STICK, "LS", 0.405f, 0.33f, 0.12f, 0.12f, Shape.CIRCLE),
        key(Button.R_STICK, "RS", 0.595f, 0.33f, 0.12f, 0.12f, Shape.CIRCLE),

        key(Button.X, "X", 0.845f, 0.31f, 0.19f, 0.19f, Shape.CIRCLE),
        key(Button.Y, "Y", 0.775f, 0.47f, 0.19f, 0.19f, Shape.CIRCLE),
        key(Button.A, "A", 0.915f, 0.47f, 0.19f, 0.19f, Shape.CIRCLE),
        key(Button.B, "B", 0.845f, 0.63f, 0.19f, 0.19f, Shape.CIRCLE),

        key(Button.DPAD_UP, "▲", 0.300f, 0.60f, 0.15f, 0.15f, Shape.PILL),
        key(Button.DPAD_LEFT, "◀", 0.232f, 0.75f, 0.15f, 0.15f, Shape.PILL),
        key(Button.DPAD_RIGHT, "▶", 0.368f, 0.75f, 0.15f, 0.15f, Shape.PILL),
        key(Button.DPAD_DOWN, "▼", 0.300f, 0.90f, 0.15f, 0.15f, Shape.PILL),

        action(Action.MACRO, "", 0.47f, 0.56f, 0.40f, 0.15f),
        action(Action.PICK, "⋯", 0.595f, 0.56f, 0.11f, 0.15f),
        action(Action.RECORD, "REC", 0.455f, 0.78f, 0.17f, 0.13f),
        action(Action.MENU, "MENU", 0.545f, 0.78f, 0.17f, 0.13f),
    )

    private val sticks = listOf(
        Stick(left = true, fx = 0.105f, fy = 0.50f, fr = 0.21f),
        Stick(left = false, fx = 0.70f, fy = 0.80f, fr = 0.17f),
    )

    /** Which control each finger currently holds. */
    private val pointerKeys = HashMap<Int, Key>()

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }
    private var slop = 0f

    private val refresh = object : Runnable {
        override fun run() {
            invalidate()
            postDelayed(this, REFRESH_MS)
        }
    }

    init {
        isHapticFeedbackEnabled = true
        setBackgroundColor(COLOR_BACKGROUND)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // Macro-driven presses and link status change without touch events, so repaint steadily.
        post(refresh)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(refresh)
        releaseAll()
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val unit = min(h.toFloat(), w / 2f)
        for (k in keys) {
            val cx = k.fx * w
            val cy = k.fy * h
            val halfW = k.fw * unit / 2f
            val halfH = k.fh * unit / 2f
            k.bounds.set(cx - halfW, cy - halfH, cx + halfW, cy + halfH)
        }
        for (s in sticks) {
            s.cx = s.fx * w
            s.cy = s.fy * h
            s.radius = s.fr * unit
        }
        slop = unit * 0.015f
        stroke.strokeWidth = unit * 0.006f
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // The sticks sit at the screen edges; keep edge swipes there from meaning "back".
            val band = (200 * resources.displayMetrics.density).toInt()
            val top = ((h - band) / 2).coerceAtLeast(0)
            systemGestureExclusionRects = listOf(Rect(0, top, w, (top + band).coerceAtMost(h)))
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = event.actionIndex
                pointerDown(event.getPointerId(i), event.getX(i), event.getY(i))
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until event.pointerCount) {
                    pointerMove(event.getPointerId(i), event.getX(i), event.getY(i))
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> pointerUp(event.getPointerId(event.actionIndex))
            MotionEvent.ACTION_CANCEL -> releaseAll()
        }
        publish()
        invalidate()
        return true
    }

    private fun pointerDown(id: Int, x: Float, y: Float) {
        for (s in sticks) {
            if (s.pointer < 0 && hypot(x - s.cx, y - s.cy) <= s.radius * STICK_GRAB) {
                s.pointer = id
                moveStick(s, x, y)
                return
            }
        }
        val k = keyAt(x, y, includeActions = true) ?: return
        if (k.action != null) {
            performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            when (k.action) {
                Action.MACRO -> actions?.onMacroToggle()
                Action.PICK -> actions?.onMacroPick()
                Action.RECORD -> actions?.onRecordToggle()
                Action.MENU -> actions?.onMenu()
            }
            return
        }
        pointerKeys[id] = k
        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
    }

    private fun pointerMove(id: Int, x: Float, y: Float) {
        sticks.firstOrNull { it.pointer == id }?.let {
            moveStick(it, x, y)
            return
        }
        val held = pointerKeys[id] ?: return
        // Still on (or near) the same button: keep it, so a resting thumb never flickers.
        if (held.contains(x, y, slop * 3f)) return
        val next = keyAt(x, y, includeActions = false)
        if (next != null && next !== held) {
            pointerKeys[id] = next
            performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        } else if (next == null) {
            pointerKeys.remove(id)
        }
    }

    private fun pointerUp(id: Int) {
        sticks.firstOrNull { it.pointer == id }?.let {
            it.pointer = -1
            it.x = 0f
            it.y = 0f
        }
        pointerKeys.remove(id)
    }

    /** Lets go of every button and centres both sticks. */
    fun releaseAll() {
        pointerKeys.clear()
        for (s in sticks) {
            s.pointer = -1
            s.x = 0f
            s.y = 0f
        }
        publish()
    }

    private fun keyAt(x: Float, y: Float, includeActions: Boolean): Key? =
        keys.firstOrNull { (includeActions || it.action == null) && it.contains(x, y, slop) }

    private fun moveStick(s: Stick, x: Float, y: Float) {
        // Full deflection is reached a little inside the rim so it is easy to hold.
        val travel = s.radius * STICK_TRAVEL
        var dx = (x - s.cx) / travel
        var dy = (s.cy - y) / travel
        val magnitude = hypot(dx, dy)
        if (magnitude > 1f) {
            dx /= magnitude
            dy /= magnitude
        }
        if (magnitude < STICK_DEADZONE) {
            dx = 0f
            dy = 0f
        }
        s.x = dx
        s.y = dy
    }

    private fun publish() {
        var buttons = 0
        for (k in pointerKeys.values) k.button?.let { buttons = buttons or it.mask }
        val l = sticks[0]
        val r = sticks[1]
        ControllerHub.setManual(PadState(buttons, l.x, l.y, r.x, r.y))
    }

    override fun onDraw(canvas: Canvas) {
        val out = ControllerHub.output
        val held = pointerKeys.values
        val unit = min(height.toFloat(), width / 2f)

        drawStatus(canvas, unit)

        for (s in sticks) {
            val vx = if (s.pointer >= 0) s.x else if (s.left) out.lx else out.rx
            val vy = if (s.pointer >= 0) s.y else if (s.left) out.ly else out.ry
            fill.color = COLOR_KEY
            canvas.drawCircle(s.cx, s.cy, s.radius, fill)
            stroke.color = COLOR_OUTLINE
            canvas.drawCircle(s.cx, s.cy, s.radius, stroke)
            val travel = s.radius * STICK_TRAVEL * 0.6f
            fill.color = when {
                s.pointer >= 0 -> COLOR_PRESSED
                vx != 0f || vy != 0f -> COLOR_MACRO
                else -> COLOR_KNOB
            }
            canvas.drawCircle(s.cx + vx * travel, s.cy - vy * travel, s.radius * 0.45f, fill)
        }

        for (k in keys) {
            val byHand = held.contains(k)
            val byMacro = !byHand && k.button != null && out.isPressed(k.button)
            val label: String
            fill.color = when (k.action) {
                Action.MACRO -> {
                    val playing = ControllerHub.playingMacroId != null
                    label = (if (playing) "■ " else "▶ ") + macroName
                    if (playing) COLOR_MACRO else COLOR_ACTION
                }
                Action.RECORD -> {
                    val recording = ControllerHub.isRecording
                    label = if (recording) "■ REC" else "● REC"
                    if (recording) COLOR_RECORD else COLOR_ACTION
                }
                Action.PICK, Action.MENU -> {
                    label = k.label
                    COLOR_ACTION
                }
                null -> {
                    label = k.label
                    if (byHand) COLOR_PRESSED else if (byMacro) COLOR_MACRO else COLOR_KEY
                }
            }
            val corner = if (k.shape == Shape.CIRCLE) k.bounds.height() / 2f else k.bounds.height() * 0.28f
            canvas.drawRoundRect(k.bounds, corner, corner, fill)
            stroke.color = COLOR_OUTLINE
            canvas.drawRoundRect(k.bounds, corner, corner, stroke)

            text.color = COLOR_TEXT
            text.textSize = fitTextSize(label, k.bounds, unit)
            canvas.drawText(label, k.bounds.centerX(), k.bounds.centerY() - (text.descent() + text.ascent()) / 2f, text)
        }
    }

    private fun drawStatus(canvas: Canvas, unit: Float) {
        val status = ControllerHub.status
        val message = when (status.link) {
            Link.READY -> status.detail
            Link.HANDSHAKE -> "Console connected, setting up…"
            Link.WAITING -> "Waiting for the console"
            Link.STARTING -> "Starting…"
            Link.STOPPED -> "Controller is off — open MENU and start it"
            Link.ERROR -> "Error — open MENU for details"
        }
        text.color = if (status.link == Link.READY) COLOR_OK else COLOR_WARN
        text.textSize = unit * 0.045f
        canvas.drawText(message, width / 2f, height * 0.225f, text)
    }

    /** Largest size (up to a share of the key height) at which [label] fits inside [bounds]. */
    private fun fitTextSize(label: String, bounds: RectF, unit: Float): Float {
        var size = min(bounds.height() * 0.42f, unit * 0.075f)
        text.textSize = size
        val available = bounds.width() * 0.88f
        val needed = text.measureText(label)
        if (needed > available) size *= available / needed
        return size
    }

    private companion object {
        const val REFRESH_MS = 33L
        const val STICK_GRAB = 1.35f
        const val STICK_TRAVEL = 0.8f
        const val STICK_DEADZONE = 0.1f

        const val COLOR_BACKGROUND = 0xFF14161A.toInt()
        const val COLOR_KEY = 0xFF2A2E36.toInt()
        const val COLOR_KNOB = 0xFF4A505C.toInt()
        const val COLOR_OUTLINE = 0xFF5A6170.toInt()
        const val COLOR_PRESSED = 0xFFE53935.toInt()
        const val COLOR_MACRO = 0xFF1E88E5.toInt()
        const val COLOR_ACTION = 0xFF37474F.toInt()
        const val COLOR_RECORD = 0xFFC62828.toInt()
        const val COLOR_TEXT = 0xFFF2F4F8.toInt()
        const val COLOR_OK = 0xFF66BB6A.toInt()
        const val COLOR_WARN = 0xFFFFB74D.toInt()

        fun key(b: Button, label: String, fx: Float, fy: Float, fw: Float, fh: Float, shape: Shape) =
            Key(b, null, label, fx, fy, fw, fh, shape)

        fun action(a: Action, label: String, fx: Float, fy: Float, fw: Float, fh: Float) =
            Key(null, a, label, fx, fy, fw, fh, Shape.PILL)
    }
}
