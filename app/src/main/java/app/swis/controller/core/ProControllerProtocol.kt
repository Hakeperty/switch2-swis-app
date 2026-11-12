package app.swis.controller.core

/**
 * The Nintendo Switch Pro Controller (first generation) Bluetooth HID protocol, controller side.
 *
 * The console drives the conversation: it sends output report 0x01 carrying a "subcommand"
 * and expects input report 0x21 as the answer. In between, the controller streams input
 * report 0x30 with buttons and sticks.
 *
 * Byte values follow NXBT and joycontrol, the two open-source emulators with field reports
 * of consoles accepting them, and dekuNukem's reverse-engineering notes.
 *
 * All payloads here EXCLUDE the HID transaction header (0xA1 / 0xA2) and the report ID,
 * because Android's BluetoothHidDevice adds and strips those itself. So index 0 of a payload
 * is the byte right after the report ID.
 *
 * Not thread-safe; callers serialise access.
 */
class ProControllerProtocol(
    bluetoothAddress: ByteArray,
    private val clockMillis: () -> Long = { System.nanoTime() / 1_000_000 },
    private val pickVibratorByte: () -> Int = { VIBRATOR_BYTES[(System.nanoTime() and 3).toInt()] },
) {
    init {
        require(bluetoothAddress.size == 6) { "Bluetooth address must be 6 bytes" }
    }

    private val address = bluetoothAddress.copyOf()
    private val flash = SpiFlash()

    /** True once the console asked for device info; before that reports carry no state. */
    var deviceInfoQueried = false
        private set
    var vibrationEnabled = false
        private set
    var imuEnabled = false
        private set

    /** 1-4 once the console assigned a player slot, else null. */
    var playerNumber: Int? = null
        private set
    var inputMode: Int = 0
        private set

    /** The console finished its setup conversation and is listening for input. */
    val handshakeComplete: Boolean get() = vibrationEnabled && playerNumber != null

    private var timer = 0
    private var lastTimerAt = -1L
    private var vibratorByte = 0xA0

    /** Payload (48 bytes) of input report 0x30 for [state]. */
    fun buildInputReport(state: PadState): ByteArray {
        val r = ByteArray(REPORT_PAYLOAD_SIZE)
        fillStandard(r, state)
        if (imuEnabled) IMU_IDLE.copyInto(r, OFFSET_BODY)
        return r
    }

    /**
     * Handles one output report from the console.
     *
     * @param reportId the HID report ID (0x01 = rumble + subcommand, 0x10 = rumble only)
     * @param data payload after the report ID
     * @return the reply, or null when the report needs none
     */
    fun handleOutputReport(reportId: Int, data: ByteArray, state: PadState): Reply? {
        if (reportId != OUTPUT_RUMBLE_AND_SUBCOMMAND) return null
        // counter(1) + rumble(8) + subcommand id(1)
        if (data.size < OFFSET_SUBCOMMAND_IN + 1) return null

        val id = data[OFFSET_SUBCOMMAND_IN].toInt() and 0xFF
        fun arg(i: Int): Int = data.getOrElse(OFFSET_SUBCOMMAND_IN + 1 + i) { 0 }.toInt() and 0xFF

        // Unknown subcommands are deliberately left unanswered: a NACK makes the console
        // retry forever, silence makes it move on.
        if (id !in KNOWN_SUBCOMMANDS) return Reply(id, null, describe(id, known = false))

        if (id == SUB_DEVICE_INFO) deviceInfoQueried = true
        vibratorByte = pickVibratorByte() and 0xFF

        val r = ByteArray(REPORT_PAYLOAD_SIZE)
        fillStandard(r, state)
        r[OFFSET_BODY + 1] = id.toByte()

        when (id) {
            SUB_DEVICE_INFO -> {
                r[OFFSET_BODY] = 0x82.toByte()
                r[14] = 0x03                    // firmware 3.139
                r[15] = 0x8B.toByte()
                r[16] = 0x03                    // 3 = Pro Controller
                r[17] = 0x02
                address.copyInto(r, 18)         // big-endian, as printed
                r[24] = 0x01
                r[25] = 0x01                    // colours live in SPI flash
            }
            SUB_SET_SHIPMENT -> r[OFFSET_BODY] = 0x80.toByte()
            SUB_SPI_READ -> {
                val offset = arg(0) or (arg(1) shl 8) or (arg(2) shl 16) or (arg(3) shl 24)
                val length = minOf(arg(4), MAX_SPI_READ)
                r[OFFSET_BODY] = 0x90.toByte()
                r[14] = arg(0).toByte()
                r[15] = arg(1).toByte()
                r[16] = arg(2).toByte()
                r[17] = arg(3).toByte()
                r[18] = length.toByte()
                flash.read(offset, length).copyInto(r, 19)
            }
            SUB_SET_INPUT_MODE -> {
                inputMode = arg(0)
                r[OFFSET_BODY] = 0x80.toByte()
            }
            SUB_TRIGGER_BUTTONS -> r[OFFSET_BODY] = 0x83.toByte()
            SUB_ENABLE_IMU -> {
                imuEnabled = arg(0) == 0x01
                r[OFFSET_BODY] = 0x80.toByte()
            }
            SUB_ENABLE_VIBRATION -> {
                vibrationEnabled = true
                r[OFFSET_BODY] = 0x82.toByte()
            }
            SUB_SET_PLAYER_LIGHTS -> {
                playerNumber = when (arg(0)) {
                    0x01, 0x10 -> 1
                    0x03, 0x30 -> 2
                    0x07, 0x70 -> 3
                    0x0F, 0xF0 -> 4
                    else -> playerNumber
                }
                r[OFFSET_BODY] = 0x80.toByte()
            }
            SUB_SET_NFC_IR_STATE -> r[OFFSET_BODY] = 0x80.toByte()
            SUB_SET_NFC_IR_CONFIG -> {
                r[OFFSET_BODY] = 0xA0.toByte()
                NFC_IR_CONFIG_REPLY.copyInto(r, 14)
                r[47] = 0xC8.toByte()
            }
        }
        return Reply(id, r, describe(id, known = true))
    }

    /** Forgets everything learned from the console; call when the link drops. */
    fun reset() {
        deviceInfoQueried = false
        vibrationEnabled = false
        imuEnabled = false
        playerNumber = null
        inputMode = 0
        timer = 0
        lastTimerAt = -1L
    }

    private fun fillStandard(r: ByteArray, state: PadState) {
        r[0] = nextTimer().toByte()
        // A real controller reports nothing meaningful until the console has identified it.
        if (!deviceInfoQueried) return
        r[1] = BATTERY_FULL_PRO.toByte()
        r[2] = (state.buttons and 0xFF).toByte()
        r[3] = (state.buttons shr 8 and 0xFF).toByte()
        r[4] = (state.buttons shr 16 and 0xFF).toByte()
        StickCodec.encodeLeft(state.lx, state.ly).copyInto(r, 5)
        StickCodec.encodeRight(state.rx, state.ry).copyInto(r, 8)
        r[11] = vibratorByte.toByte()
    }

    /** The controller's free-running timer ticks roughly every 5 ms. */
    private fun nextTimer(): Int {
        val now = clockMillis()
        if (lastTimerAt < 0) {
            lastTimerAt = now
            return timer
        }
        val ticks = (now - lastTimerAt) / 5
        if (ticks > 0) {
            timer = ((timer + ticks) and 0xFF).toInt()
            lastTimerAt += ticks * 5
        }
        return timer
    }

    /**
     * @property subcommand the subcommand the console sent
     * @property payload 48-byte payload of input report 0x21, or null if nothing is to be sent
     * @property description human-readable name for the on-screen log
     */
    class Reply(val subcommand: Int, val payload: ByteArray?, val description: String)

    companion object {
        const val INPUT_FULL = 0x30
        const val INPUT_SUBCOMMAND_REPLY = 0x21
        const val OUTPUT_RUMBLE_AND_SUBCOMMAND = 0x01

        /** Size of an input report without its report ID. */
        const val REPORT_PAYLOAD_SIZE = 48

        const val SUB_DEVICE_INFO = 0x02
        const val SUB_SET_INPUT_MODE = 0x03
        const val SUB_TRIGGER_BUTTONS = 0x04
        const val SUB_SET_SHIPMENT = 0x08
        const val SUB_SPI_READ = 0x10
        const val SUB_SET_NFC_IR_CONFIG = 0x21
        const val SUB_SET_NFC_IR_STATE = 0x22
        const val SUB_SET_PLAYER_LIGHTS = 0x30
        const val SUB_ENABLE_IMU = 0x40
        const val SUB_ENABLE_VIBRATION = 0x48

        private val KNOWN_SUBCOMMANDS = setOf(
            SUB_DEVICE_INFO, SUB_SET_INPUT_MODE, SUB_TRIGGER_BUTTONS, SUB_SET_SHIPMENT, SUB_SPI_READ,
            SUB_SET_NFC_IR_CONFIG, SUB_SET_NFC_IR_STATE, SUB_SET_PLAYER_LIGHTS, SUB_ENABLE_IMU,
            SUB_ENABLE_VIBRATION,
        )

        /** Where ACK byte, echoed subcommand id and reply data start in an input payload. */
        private const val OFFSET_BODY = 12

        /** Subcommand id position in an output payload: counter(1) + rumble(8). */
        private const val OFFSET_SUBCOMMAND_IN = 9

        private const val MAX_SPI_READ = 0x1D
        private const val BATTERY_FULL_PRO = 0x90
        private val VIBRATOR_BYTES = intArrayOf(0xA0, 0xB0, 0xC0, 0x90)

        private val NFC_IR_CONFIG_REPLY = byteArrayOf(0x01, 0x00, 0xFF.toByte(), 0x00, 0x08, 0x00, 0x1B, 0x01)

        /** Three motion samples of a controller lying still on a table. */
        private val IMU_IDLE = intArrayOf(
            0x75, 0xFD, 0xFD, 0xFF, 0x09, 0x10, 0x21, 0x00, 0xD5, 0xFF, 0xE0, 0xFF,
            0x72, 0xFD, 0xF9, 0xFF, 0x0A, 0x10, 0x22, 0x00, 0xD5, 0xFF, 0xE0, 0xFF,
            0x76, 0xFD, 0xFC, 0xFF, 0x09, 0x10, 0x23, 0x00, 0xD5, 0xFF, 0xE0, 0xFF,
        ).map { it.toByte() }.toByteArray()

        private fun describe(id: Int, known: Boolean): String {
            val name = when (id) {
                SUB_DEVICE_INFO -> "request device info"
                SUB_SET_INPUT_MODE -> "set input report mode"
                SUB_TRIGGER_BUTTONS -> "trigger buttons elapsed time"
                SUB_SET_SHIPMENT -> "set shipment state"
                SUB_SPI_READ -> "SPI flash read"
                SUB_SET_NFC_IR_CONFIG -> "set NFC/IR config"
                SUB_SET_NFC_IR_STATE -> "set NFC/IR state"
                SUB_SET_PLAYER_LIGHTS -> "set player lights"
                SUB_ENABLE_IMU -> "enable motion sensor"
                SUB_ENABLE_VIBRATION -> "enable vibration"
                else -> "unknown"
            }
            return "0x%02X %s%s".format(id, name, if (known) "" else " (ignored)")
        }

        /**
         * The HID report descriptor of a Pro Controller, as published in the SDP record used
         * by NXBT and joycontrol.
         */
        val HID_DESCRIPTOR: ByteArray = hex(
            "05010905a1010601ff8521092175089530810285300930750895308102853109317508966901" +
                "810285320932750896690181028533093375089669018102853f0509190129101500250175" +
                "0195108102050109391500250775049501814205097504950181010501093009310933093416" +
                "000027ffff00007510950481020601ff850109017508953091028510091075089530910285" +
                "11091175089530910285120912750895309102c0"
        )

        /** Parses "AA:BB:CC:DD:EE:FF" (any separator, any case) into 6 bytes, or null. */
        fun parseAddress(text: String): ByteArray? {
            val digits = text.filter { it.isLetterOrDigit() }
            if (digits.length != 12 || digits.any { Character.digit(it, 16) < 0 }) return null
            return hex(digits)
        }

        private fun hex(s: String): ByteArray =
            ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }
}

/**
 * Converts stick positions to the packed 12-bit-per-axis format, using the same calibration
 * the emulated SPI flash reports to the console, so +-1.0 lands exactly on full deflection.
 */
object StickCodec {
    private const val L_CENTER_X = 2159
    private const val L_CENTER_Y = 1916
    private const val L_ABOVE_X = 1466
    private const val L_ABOVE_Y = 1583
    private const val L_BELOW_X = 1517
    private const val L_BELOW_Y = 1465

    private const val R_CENTER_X = 2070
    private const val R_CENTER_Y = 2013
    private const val R_ABOVE_X = 1414
    private const val R_ABOVE_Y = 1510
    private const val R_BELOW_X = 1522
    private const val R_BELOW_Y = 1531

    fun encodeLeft(x: Float, y: Float): ByteArray = pack(
        axis(x, L_CENTER_X, L_ABOVE_X, L_BELOW_X),
        axis(y, L_CENTER_Y, L_ABOVE_Y, L_BELOW_Y),
    )

    fun encodeRight(x: Float, y: Float): ByteArray = pack(
        axis(x, R_CENTER_X, R_ABOVE_X, R_BELOW_X),
        axis(y, R_CENTER_Y, R_ABOVE_Y, R_BELOW_Y),
    )

    private fun axis(v: Float, center: Int, above: Int, below: Int): Int {
        val c = if (v.isNaN()) 0f else v.coerceIn(-1f, 1f)
        val raw = center + Math.round(c * if (c >= 0) above else below)
        return raw.coerceIn(0, 0xFFF)
    }

    /** Two 12-bit values in three bytes, little-endian nibble order. */
    fun pack(x: Int, y: Int): ByteArray = byteArrayOf(
        (x and 0xFF).toByte(),
        ((y and 0xF shl 4) or (x shr 8 and 0xF)).toByte(),
        (y shr 4 and 0xFF).toByte(),
    )
}

/**
 * The slice of the controller's SPI flash the console reads while setting up: serial number,
 * calibration and colours. Everything else reads as erased flash (0xFF).
 */
class SpiFlash {
    private val regions = sortedMapOf<Int, ByteArray>()

    init {
        // 0x6000 serial number stays erased, which the console treats as "none".
        put(
            0x6020, // motion sensor factory calibration
            0xD3, 0xFF, 0xD5, 0xFF, 0x55, 0x01,
            0x00, 0x40, 0x00, 0x40, 0x00, 0x40,
            0x19, 0x00, 0xDD, 0xFF, 0xDC, 0xFF,
            0x3B, 0x34, 0x3B, 0x34, 0x3B, 0x34,
        )
        put(
            0x603D, // left stick factory calibration: max above centre, centre, min below centre
            0xBA, 0xF5, 0x62, 0x6F, 0xC8, 0x77, 0xED, 0x95, 0x5B,
        )
        put(
            0x6046, // right stick factory calibration: centre, min below centre, max above centre
            0x16, 0xD8, 0x7D, 0xF2, 0xB5, 0x5F, 0x86, 0x65, 0x5E,
        )
        put(0x6050, 0x82, 0x82, 0x82, 0x0F, 0x0F, 0x0F) // body colour, button colour
        put(0x6080, 0x50, 0xFD, 0x00, 0x00, 0xC6, 0x0F) // motion sensor horizontal offsets
        put(0x6086, *STICK_PARAMS)
        put(0x6098, *STICK_PARAMS)
        // 0x8010 user calibration stays erased, so the factory values above apply.
    }

    fun read(offset: Int, length: Int): ByteArray {
        val out = ByteArray(length) { 0xFF.toByte() }
        for ((start, bytes) in regions) {
            for (i in bytes.indices) {
                val at = start + i - offset
                if (at in 0 until length) out[at] = bytes[i]
            }
        }
        return out
    }

    private fun put(offset: Int, vararg bytes: Int) {
        regions[offset] = ByteArray(bytes.size) { bytes[it].toByte() }
    }

    private companion object {
        val STICK_PARAMS = intArrayOf(
            0x0F, 0x30, 0x61, 0x96, 0x30, 0xF3, 0xD4, 0x14, 0x54,
            0x41, 0x15, 0x54, 0xC7, 0x79, 0x9C, 0x33, 0x36, 0x63,
        )
    }
}
