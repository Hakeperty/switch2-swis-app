package app.swis.controller.core

/**
 * Buttons of a Pro Controller. [bit] is the position inside the 24-bit button field of the
 * standard input report: bits 0-7 are the first ("right") byte, 8-15 the shared byte and
 * 16-23 the last ("left") byte.
 *
 * Bit assignments match the Linux hid-nintendo driver and joycontrol.
 */
enum class Button(val bit: Int, val label: String) {
    Y(0, "Y"),
    X(1, "X"),
    B(2, "B"),
    A(3, "A"),
    R(6, "R"),
    ZR(7, "ZR"),

    MINUS(8, "-"),
    PLUS(9, "+"),
    R_STICK(10, "RS"),
    L_STICK(11, "LS"),
    HOME(12, "HOME"),
    CAPTURE(13, "CAP"),

    DPAD_DOWN(16, "DOWN"),
    DPAD_UP(17, "UP"),
    DPAD_RIGHT(18, "RIGHT"),
    DPAD_LEFT(19, "LEFT"),
    L(22, "L"),
    ZL(23, "ZL");

    val mask: Int get() = 1 shl bit

    companion object {
        /** Names accepted in macro text, including a few convenient aliases. */
        private val byName: Map<String, Button> = buildMap {
            for (b in Button.entries) put(b.name, b)
            put("UP", DPAD_UP); put("DOWN", DPAD_DOWN); put("LEFT", DPAD_LEFT); put("RIGHT", DPAD_RIGHT)
            put("L3", L_STICK); put("R3", R_STICK)
            put("LS", L_STICK); put("RS", R_STICK)
            put("START", PLUS); put("SELECT", MINUS)
        }

        fun parse(name: String): Button? = byName[name.uppercase()]
    }
}

/**
 * One immutable snapshot of everything the controller reports.
 * Stick axes are in [-1, 1]; +x is right and +y is up.
 */
data class PadState(
    val buttons: Int = 0,
    val lx: Float = 0f,
    val ly: Float = 0f,
    val rx: Float = 0f,
    val ry: Float = 0f,
) {
    fun isPressed(b: Button): Boolean = buttons and b.mask != 0

    fun with(b: Button, pressed: Boolean): PadState =
        copy(buttons = if (pressed) buttons or b.mask else buttons and b.mask.inv())

    fun pressedButtons(): List<Button> = Button.entries.filter { isPressed(it) }

    companion object {
        val NEUTRAL = PadState()

        fun of(vararg pressed: Button): PadState = PadState(buttons = pressed.fold(0) { acc, b -> acc or b.mask })

        /**
         * What actually goes to the console while a macro runs: macro buttons plus anything
         * held by hand, and the hand's stick wins whenever it is clearly deflected so the
         * player can still steer.
         */
        fun merge(manual: PadState, macro: PadState?): PadState {
            if (macro == null) return manual
            val manualLeft = manual.lx * manual.lx + manual.ly * manual.ly > STICK_OVERRIDE_SQ
            val manualRight = manual.rx * manual.rx + manual.ry * manual.ry > STICK_OVERRIDE_SQ
            return PadState(
                buttons = manual.buttons or macro.buttons,
                lx = if (manualLeft) manual.lx else macro.lx,
                ly = if (manualLeft) manual.ly else macro.ly,
                rx = if (manualRight) manual.rx else macro.rx,
                ry = if (manualRight) manual.ry else macro.ry,
            )
        }

        private const val STICK_OVERRIDE_SQ = 0.2f * 0.2f
    }
}
