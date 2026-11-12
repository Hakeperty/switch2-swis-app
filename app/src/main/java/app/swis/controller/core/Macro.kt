package app.swis.controller.core

import java.util.Locale

/** One held controller state and how long to hold it. */
data class MacroStep(val durationMs: Int, val state: PadState, val comment: String = "")

/**
 * A timed input sequence. [intro] plays once, then [body] repeats [loops] times
 * (0 = until stopped). Every duration is multiplied by [scale] at play time, which gives one
 * knob to slow a whole macro down or speed it up.
 */
data class Macro(
    val name: String,
    val loops: Int,
    val scale: Float,
    val intro: List<MacroStep>,
    val body: List<MacroStep>,
) {
    val isInfinite: Boolean get() = loops == 0

    /** Length of one pass through [body] in milliseconds, after scaling. */
    val cycleMs: Int get() = body.sumOf { scaled(it.durationMs) }

    fun scaled(durationMs: Int): Int = Math.round(durationMs * scale).coerceAtLeast(1)
}

class MacroSyntaxException(val line: Int, message: String) : Exception("Line $line: $message")

/**
 * Macros are stored and edited as plain text:
 *
 * ```
 * name = Example
 * loops = 0            # 0 repeats until stopped
 * scale = 1.0          # multiplies every duration
 * 400 ZL LS(0,1)       # milliseconds, then everything held during that time
 * loop:                # steps above run once, steps below repeat
 * 70  ZL LS(0,1) A
 * 3f  ZL LS(0,1)       # "f" = frames at 60 fps
 * 50  -                # nothing held
 * ```
 *
 * Sticks are `LS(x,y)` / `RS(x,y)` with x and y in -1..1 (+x right, +y up), or the shortcuts
 * `LS_UP`, `LS_DOWN`, `LS_LEFT`, `LS_RIGHT` and the same for `RS`.
 */
object MacroText {
    /** Shorter holds can fall between two Bluetooth reports and never reach the console. */
    const val MIN_STEP_MS = 20
    const val MAX_STEP_MS = 600_000
    private const val FRAME_MS = 1000.0 / 60.0

    private val stickRegex = Regex("""^(LS|RS)\((-?\d*\.?\d+),(-?\d*\.?\d+)\)$""", RegexOption.IGNORE_CASE)

    fun parse(text: String): Macro {
        var name = "Macro"
        var loops: Int? = null
        var scale = 1f
        val intro = ArrayList<MacroStep>()
        val body = ArrayList<MacroStep>()
        var sawLoopMarker = false

        text.lineSequence().forEachIndexed { index, raw ->
            val lineNo = index + 1
            val hash = raw.indexOf('#')
            val comment = if (hash >= 0) raw.substring(hash + 1).trim() else ""
            val line = (if (hash >= 0) raw.substring(0, hash) else raw).trim()
            if (line.isEmpty()) return@forEachIndexed

            if (line.equals("loop:", ignoreCase = true)) {
                if (sawLoopMarker) throw MacroSyntaxException(lineNo, "only one \"loop:\" line is allowed")
                sawLoopMarker = true
                return@forEachIndexed
            }

            val eq = line.indexOf('=')
            if (eq > 0 && !line[0].isDigit()) {
                val key = line.substring(0, eq).trim().lowercase(Locale.ROOT)
                val value = line.substring(eq + 1).trim()
                when (key) {
                    "name" -> name = value.ifEmpty { "Macro" }
                    "loops" -> loops = value.toIntOrNull()?.takeIf { it >= 0 }
                        ?: throw MacroSyntaxException(lineNo, "loops must be 0 (forever) or a positive number")
                    "scale" -> scale = value.toFloatOrNull()?.takeIf { it in 0.1f..10f }
                        ?: throw MacroSyntaxException(lineNo, "scale must be between 0.1 and 10")
                    else -> throw MacroSyntaxException(lineNo, "unknown setting \"$key\"")
                }
                return@forEachIndexed
            }

            val step = parseStep(lineNo, line, comment)
            (if (sawLoopMarker) body else intro).add(step)
        }

        // Without a "loop:" line the whole macro is the repeating part.
        if (!sawLoopMarker) {
            body.addAll(intro)
            intro.clear()
        }
        if (body.isEmpty()) {
            throw MacroSyntaxException(text.lines().size.coerceAtLeast(1), "the macro has no steps to repeat")
        }
        return Macro(name, loops ?: 1, scale, intro, body)
    }

    private fun parseStep(lineNo: Int, line: String, comment: String): MacroStep {
        // Allow "LS(0, 1)" by removing spaces inside parentheses before splitting.
        val compact = StringBuilder()
        var depth = 0
        for (c in line) {
            if (c == '(') depth++
            if (c == ')') depth--
            if (depth > 0 && c.isWhitespace()) continue
            compact.append(c)
        }
        val tokens = compact.toString().split(Regex("""[\s+]+""")).filter { it.isNotEmpty() }
        val duration = parseDuration(lineNo, tokens[0])

        var state = PadState.NEUTRAL
        for (token in tokens.drop(1)) {
            if (token == "-") continue
            val upper = token.uppercase(Locale.ROOT)
            val stick = stickRegex.matchEntire(token)
            val button = Button.parse(upper)
            state = when {
                stick != null -> {
                    val x = stick.groupValues[2].toFloat()
                    val y = stick.groupValues[3].toFloat()
                    if (x !in -1f..1f || y !in -1f..1f) {
                        throw MacroSyntaxException(lineNo, "stick values must be between -1 and 1")
                    }
                    if (stick.groupValues[1].equals("LS", ignoreCase = true)) state.copy(lx = x, ly = y)
                    else state.copy(rx = x, ry = y)
                }
                upper == "LS_UP" -> state.copy(lx = 0f, ly = 1f)
                upper == "LS_DOWN" -> state.copy(lx = 0f, ly = -1f)
                upper == "LS_LEFT" -> state.copy(lx = -1f, ly = 0f)
                upper == "LS_RIGHT" -> state.copy(lx = 1f, ly = 0f)
                upper == "RS_UP" -> state.copy(rx = 0f, ry = 1f)
                upper == "RS_DOWN" -> state.copy(rx = 0f, ry = -1f)
                upper == "RS_LEFT" -> state.copy(rx = -1f, ry = 0f)
                upper == "RS_RIGHT" -> state.copy(rx = 1f, ry = 0f)
                button != null -> state.with(button, true)
                else -> throw MacroSyntaxException(lineNo, "\"$token\" is not a button or stick")
            }
        }
        return MacroStep(duration, state, comment)
    }

    private fun parseDuration(lineNo: Int, token: String): Int {
        val lower = token.lowercase(Locale.ROOT)
        val ms = when {
            lower.endsWith("ms") -> lower.dropLast(2).toDoubleOrNull()
            lower.endsWith("f") -> lower.dropLast(1).toDoubleOrNull()?.times(FRAME_MS)
            lower.endsWith("s") -> lower.dropLast(1).toDoubleOrNull()?.times(1000.0)
            else -> lower.toDoubleOrNull()
        } ?: throw MacroSyntaxException(lineNo, "a step starts with its duration, e.g. \"80\" (ms) or \"5f\" (frames)")
        val rounded = Math.round(ms).toInt()
        if (rounded < MIN_STEP_MS) {
            throw MacroSyntaxException(lineNo, "steps shorter than $MIN_STEP_MS ms can be missed by the console")
        }
        if (rounded > MAX_STEP_MS) throw MacroSyntaxException(lineNo, "step is longer than 10 minutes")
        return rounded
    }

    fun format(macro: Macro): String = buildString {
        append("name = ").append(macro.name).append('\n')
        append("loops = ").append(macro.loops).append('\n')
        append("scale = ").append(formatNumber(macro.scale)).append('\n')
        append('\n')
        macro.intro.forEach { appendStep(it) }
        if (macro.intro.isNotEmpty()) append("loop:\n")
        macro.body.forEach { appendStep(it) }
    }

    private fun StringBuilder.appendStep(step: MacroStep) {
        append(step.durationMs.toString().padEnd(5))
        val parts = ArrayList<String>()
        step.state.pressedButtons().forEach { parts.add(it.name) }
        if (step.state.lx != 0f || step.state.ly != 0f) {
            parts.add("LS(${formatNumber(step.state.lx)},${formatNumber(step.state.ly)})")
        }
        if (step.state.rx != 0f || step.state.ry != 0f) {
            parts.add("RS(${formatNumber(step.state.rx)},${formatNumber(step.state.ry)})")
        }
        append(if (parts.isEmpty()) "-" else parts.joinToString(" "))
        if (step.comment.isNotEmpty()) append("   # ").append(step.comment)
        append('\n')
    }

    private fun formatNumber(v: Float): String {
        val s = String.format(Locale.ROOT, "%.2f", v).trimEnd('0').trimEnd('.')
        return if (s == "-0") "0" else s
    }
}

/**
 * Turns live inputs into macro steps: every change of state closes the previous step with the
 * time it was held.
 */
class MacroRecorder(private val clockNanos: () -> Long = System::nanoTime) {
    private val steps = ArrayList<MacroStep>()
    private var current: PadState? = null
    private var since = 0L

    @Synchronized
    fun onState(state: PadState) {
        val now = clockNanos()
        val quantised = quantise(state)
        val previous = current
        if (previous == null) {
            // Recording starts with the first real input, not with the idle time before it.
            if (quantised == PadState.NEUTRAL) return
        } else {
            if (previous == quantised) return
            close(previous, now)
        }
        current = quantised
        since = now
    }

    /** Ends the recording and returns it, or null if nothing was pressed. */
    @Synchronized
    fun finish(name: String): Macro? {
        val last = current ?: return null
        // Trailing idle time is not part of the performance.
        if (last != PadState.NEUTRAL) close(last, clockNanos())
        current = null
        if (steps.isEmpty()) return null
        return Macro(name, loops = 1, scale = 1f, intro = emptyList(), body = ArrayList(steps))
    }

    private fun close(state: PadState, now: Long) {
        val ms = ((now - since) / 1_000_000).toInt().coerceIn(MacroText.MIN_STEP_MS, MacroText.MAX_STEP_MS)
        steps.add(MacroStep(ms, state))
    }

    /** Snap sticks to a coarse grid so a wobbling thumb does not create hundreds of steps. */
    private fun quantise(s: PadState) = s.copy(lx = snap(s.lx), ly = snap(s.ly), rx = snap(s.rx), ry = snap(s.ry))

    private fun snap(v: Float): Float = Math.round(v * 4f) / 4f
}
