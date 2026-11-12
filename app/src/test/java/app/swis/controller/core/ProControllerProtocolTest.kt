package app.swis.controller.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Expected bytes are written out independently from the implementation, taken from the NXBT
 * emulator's replies (shifted by two: these payloads carry neither the 0xA1 header nor the
 * report ID) and from the Linux hid-nintendo driver's button table.
 */
class ProControllerProtocolTest {
    private val address = byteArrayOf(0x11, 0x22, 0x33, 0x44, 0x55, 0x66)
    private var now = 0L

    private fun protocol() = ProControllerProtocol(address, clockMillis = { now }, pickVibratorByte = { 0xB0 })

    /** Output report 0x01 payload: counter, 8 rumble bytes, subcommand id, arguments. */
    private fun subcommand(id: Int, vararg args: Int): ByteArray {
        val data = ByteArray(48)
        data[0] = 0x05
        data[9] = id.toByte()
        args.forEachIndexed { i, v -> data[10 + i] = v.toByte() }
        return data
    }

    private fun ProControllerProtocol.ask(id: Int, vararg args: Int, state: PadState = PadState.NEUTRAL): ByteArray =
        handleOutputReport(0x01, subcommand(id, *args), state)!!.payload!!

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    @Test
    fun reportsCarryNoStateUntilTheConsoleIdentifiesTheController() {
        val p = protocol()
        val report = p.buildInputReport(PadState.of(Button.A).copy(lx = 1f))
        assertEquals(48, report.size)
        assertTrue(report.all { it == 0.toByte() })
    }

    @Test
    fun deviceInfoReply() {
        val p = protocol()
        val r = p.ask(0x02)
        assertEquals(48, r.size)
        assertEquals(0x90.toByte(), r[1])                       // battery full, Pro Controller
        assertArrayEquals(bytes(0, 0, 0), r.copyOfRange(2, 5))  // no buttons
        assertArrayEquals(bytes(0x6F, 0xC8, 0x77), r.copyOfRange(5, 8))   // left stick centred
        assertArrayEquals(bytes(0x16, 0xD8, 0x7D), r.copyOfRange(8, 11))  // right stick centred
        assertEquals(0xB0.toByte(), r[11])
        assertArrayEquals(
            bytes(0x82, 0x02, 0x03, 0x8B, 0x03, 0x02, 0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x01, 0x01),
            r.copyOfRange(12, 26),
        )
        assertTrue(r.copyOfRange(26, 48).all { it == 0.toByte() })
        assertTrue(p.deviceInfoQueried)
    }

    @Test
    fun simpleAcknowledgements() {
        val p = protocol()
        assertArrayEquals(bytes(0x80, 0x08), p.ask(0x08).copyOfRange(12, 14))
        assertArrayEquals(bytes(0x80, 0x03), p.ask(0x03, 0x30).copyOfRange(12, 14))
        assertEquals(0x30, p.inputMode)
        assertArrayEquals(bytes(0x83, 0x04), p.ask(0x04).copyOfRange(12, 14))
        assertArrayEquals(bytes(0x80, 0x40), p.ask(0x40, 0x01).copyOfRange(12, 14))
        assertTrue(p.imuEnabled)
        assertArrayEquals(bytes(0x82, 0x48), p.ask(0x48, 0x01).copyOfRange(12, 14))
        assertArrayEquals(bytes(0x80, 0x30), p.ask(0x30, 0x01).copyOfRange(12, 14))
        assertArrayEquals(bytes(0x80, 0x22), p.ask(0x22, 0x01).copyOfRange(12, 14))
    }

    @Test
    fun nfcIrConfigReply() {
        val r = protocol().ask(0x21, 0x21, 0x00, 0x00)
        assertArrayEquals(bytes(0xA0, 0x21, 0x01, 0x00, 0xFF, 0x00, 0x08, 0x00, 0x1B, 0x01), r.copyOfRange(12, 22))
        assertEquals(0xC8.toByte(), r[47])
    }

    @Test
    fun handshakeCompletesAfterVibrationAndPlayerLights() {
        val p = protocol()
        p.ask(0x02)
        assertFalse(p.handshakeComplete)
        p.ask(0x48, 0x01)
        assertFalse(p.handshakeComplete)
        p.ask(0x30, 0x03)
        assertTrue(p.handshakeComplete)
        assertEquals(2, p.playerNumber)
        p.reset()
        assertFalse(p.handshakeComplete)
        assertFalse(p.deviceInfoQueried)
    }

    @Test
    fun playerLightPatterns() {
        val expected = mapOf(0x01 to 1, 0x10 to 1, 0x03 to 2, 0x30 to 2, 0x07 to 3, 0x70 to 3, 0x0F to 4, 0xF0 to 4)
        for ((pattern, player) in expected) {
            val p = protocol()
            p.ask(0x30, pattern)
            assertEquals(player, p.playerNumber)
        }
    }

    private fun spi(p: ProControllerProtocol, offset: Int, length: Int): ByteArray {
        val r = p.ask(0x10, offset and 0xFF, offset shr 8 and 0xFF, offset shr 16 and 0xFF, 0, length)
        assertArrayEquals(bytes(0x90, 0x10, offset and 0xFF, offset shr 8 and 0xFF, offset shr 16 and 0xFF, 0, length), r.copyOfRange(12, 19))
        return r.copyOfRange(19, 19 + length)
    }

    @Test
    fun spiSerialNumberIsBlank() {
        assertTrue(spi(protocol(), 0x6000, 0x10).all { it == 0xFF.toByte() })
    }

    @Test
    fun spiColours() {
        assertArrayEquals(
            bytes(0x82, 0x82, 0x82, 0x0F, 0x0F, 0x0F, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF),
            spi(protocol(), 0x6050, 0x0D),
        )
    }

    @Test
    fun spiFactorySensorAndStickParameters() {
        assertArrayEquals(
            bytes(
                0x50, 0xFD, 0x00, 0x00, 0xC6, 0x0F,
                0x0F, 0x30, 0x61, 0x96, 0x30, 0xF3, 0xD4, 0x14, 0x54,
                0x41, 0x15, 0x54, 0xC7, 0x79, 0x9C, 0x33, 0x36, 0x63,
            ),
            spi(protocol(), 0x6080, 0x18),
        )
        assertArrayEquals(
            bytes(
                0x0F, 0x30, 0x61, 0x96, 0x30, 0xF3, 0xD4, 0x14, 0x54,
                0x41, 0x15, 0x54, 0xC7, 0x79, 0x9C, 0x33, 0x36, 0x63,
            ),
            spi(protocol(), 0x6098, 0x12),
        )
    }

    @Test
    fun spiUserCalibrationIsBlank() {
        assertTrue(spi(protocol(), 0x8010, 0x18).all { it == 0xFF.toByte() })
    }

    @Test
    fun spiFactoryStickCalibrationAndColours() {
        assertArrayEquals(
            bytes(
                0xBA, 0xF5, 0x62, 0x6F, 0xC8, 0x77, 0xED, 0x95, 0x5B,
                0x16, 0xD8, 0x7D, 0xF2, 0xB5, 0x5F, 0x86, 0x65, 0x5E,
                0xFF,
                0x82, 0x82, 0x82, 0x0F, 0x0F, 0x0F,
            ),
            spi(protocol(), 0x603D, 0x19),
        )
    }

    @Test
    fun spiMotionCalibration() {
        assertArrayEquals(
            bytes(
                0xD3, 0xFF, 0xD5, 0xFF, 0x55, 0x01, 0x00, 0x40, 0x00, 0x40, 0x00, 0x40,
                0x19, 0x00, 0xDD, 0xFF, 0xDC, 0xFF, 0x3B, 0x34, 0x3B, 0x34, 0x3B, 0x34,
            ),
            spi(protocol(), 0x6020, 0x18),
        )
    }

    @Test
    fun spiReadLengthIsCapped() {
        val r = protocol().ask(0x10, 0x00, 0x60, 0x00, 0x00, 0xFF)
        assertEquals(0x1D.toByte(), r[18])
    }

    @Test
    fun buttonBitsMatchTheLinuxDriver() {
        // byte index within the three button bytes, mask
        val table = mapOf(
            Button.Y to (0 to 0x01), Button.X to (0 to 0x02), Button.B to (0 to 0x04), Button.A to (0 to 0x08),
            Button.R to (0 to 0x40), Button.ZR to (0 to 0x80),
            Button.MINUS to (1 to 0x01), Button.PLUS to (1 to 0x02), Button.R_STICK to (1 to 0x04),
            Button.L_STICK to (1 to 0x08), Button.HOME to (1 to 0x10), Button.CAPTURE to (1 to 0x20),
            Button.DPAD_DOWN to (2 to 0x01), Button.DPAD_UP to (2 to 0x02), Button.DPAD_RIGHT to (2 to 0x04),
            Button.DPAD_LEFT to (2 to 0x08), Button.L to (2 to 0x40), Button.ZL to (2 to 0x80),
        )
        assertEquals(Button.entries.toSet(), table.keys)
        val p = protocol()
        p.ask(0x02)
        for ((button, where) in table) {
            val report = p.buildInputReport(PadState.of(button))
            val expected = ByteArray(3).also { it[where.first] = where.second.toByte() }
            assertArrayEquals(button.name, expected, report.copyOfRange(2, 5))
        }
    }

    @Test
    fun combinedButtonsAndSticksInInputReport() {
        val p = protocol()
        p.ask(0x02)
        val report = p.buildInputReport(PadState.of(Button.ZL, Button.A).copy(ly = 1f))
        assertEquals(0x90.toByte(), report[1])
        assertArrayEquals(bytes(0x08, 0x00, 0x80), report.copyOfRange(2, 5))
        // left stick: x at centre 2159 = 0x86F, y at centre + 1583 = 3499 = 0xDAB
        assertArrayEquals(bytes(0x6F, 0xB8, 0xDA), report.copyOfRange(5, 8))
        assertArrayEquals(bytes(0x16, 0xD8, 0x7D), report.copyOfRange(8, 11))
    }

    @Test
    fun sticksReachExactlyTheCalibratedExtremes() {
        fun unpack(b: ByteArray): Pair<Int, Int> {
            val x = (b[0].toInt() and 0xFF) or (b[1].toInt() and 0x0F shl 8)
            val y = (b[1].toInt() and 0xF0 shr 4) or (b[2].toInt() and 0xFF shl 4)
            return x to y
        }
        assertEquals(2159 to 1916, unpack(StickCodec.encodeLeft(0f, 0f)))
        assertEquals(2159 + 1466 to 1916 + 1583, unpack(StickCodec.encodeLeft(1f, 1f)))
        assertEquals(2159 - 1517 to 1916 - 1465, unpack(StickCodec.encodeLeft(-1f, -1f)))
        assertEquals(2070 to 2013, unpack(StickCodec.encodeRight(0f, 0f)))
        assertEquals(2070 + 1414 to 2013 + 1510, unpack(StickCodec.encodeRight(1f, 1f)))
        assertEquals(2070 - 1522 to 2013 - 1531, unpack(StickCodec.encodeRight(-1f, -1f)))
        // out-of-range input is clamped, never wrapped
        assertEquals(unpack(StickCodec.encodeLeft(1f, -1f)), unpack(StickCodec.encodeLeft(7f, -7f)))
        assertEquals(unpack(StickCodec.encodeLeft(0f, 0f)), unpack(StickCodec.encodeLeft(Float.NaN, Float.NaN)))
    }

    @Test
    fun motionDataOnlyWhenEnabled() {
        val p = protocol()
        p.ask(0x02)
        assertTrue(p.buildInputReport(PadState.NEUTRAL).copyOfRange(12, 48).all { it == 0.toByte() })
        p.ask(0x40, 0x01)
        val body = p.buildInputReport(PadState.NEUTRAL).copyOfRange(12, 48)
        assertArrayEquals(bytes(0x75, 0xFD, 0xFD, 0xFF, 0x09, 0x10, 0x21, 0x00, 0xD5, 0xFF, 0xE0, 0xFF), body.copyOfRange(0, 12))
        assertArrayEquals(bytes(0x76, 0xFD, 0xFC, 0xFF, 0x09, 0x10, 0x23, 0x00, 0xD5, 0xFF, 0xE0, 0xFF), body.copyOfRange(24, 36))
        p.ask(0x40, 0x00)
        assertTrue(p.buildInputReport(PadState.NEUTRAL).copyOfRange(12, 48).all { it == 0.toByte() })
    }

    @Test
    fun timerAdvancesOneTickPerFiveMilliseconds() {
        val p = protocol()
        assertEquals(0.toByte(), p.buildInputReport(PadState.NEUTRAL)[0])
        now += 15
        assertEquals(3.toByte(), p.buildInputReport(PadState.NEUTRAL)[0])
        now += 4
        assertEquals(3.toByte(), p.buildInputReport(PadState.NEUTRAL)[0])
        now += 1
        assertEquals(4.toByte(), p.buildInputReport(PadState.NEUTRAL)[0])
        now += 5 * 252
        assertEquals(0.toByte(), p.buildInputReport(PadState.NEUTRAL)[0]) // wraps at 256
    }

    @Test
    fun reportsThatNeedNoAnswer() {
        val p = protocol()
        assertNull(p.handleOutputReport(0x10, ByteArray(48), PadState.NEUTRAL))  // rumble only
        assertNull(p.handleOutputReport(0x01, ByteArray(5), PadState.NEUTRAL))   // truncated
        val unknown = p.handleOutputReport(0x01, subcommand(0x77), PadState.NEUTRAL)
        assertNotNull(unknown)
        assertNull(unknown!!.payload)
        assertEquals(0x77, unknown.subcommand)
    }

    @Test
    fun shortSubcommandReportsAreReadWithZeroArguments() {
        val p = protocol()
        val data = ByteArray(10).also { it[9] = 0x40 }
        val reply = p.handleOutputReport(0x01, data, PadState.NEUTRAL)!!
        assertArrayEquals(bytes(0x80, 0x40), reply.payload!!.copyOfRange(12, 14))
        assertFalse(p.imuEnabled)
    }

    /** Walks the HID descriptor and checks the reports the console relies on. */
    @Test
    fun hidDescriptorDeclaresTheReportsTheConsoleUses() {
        val d = ProControllerProtocol.HID_DESCRIPTOR
        assertEquals(0x05, d[0].toInt()); assertEquals(0x01, d[1].toInt())
        assertEquals(0xC0.toByte(), d.last())

        val inputBits = HashMap<Int, Int>()
        val outputBits = HashMap<Int, Int>()
        var reportId = 0
        var size = 0
        var count = 0
        var depth = 0
        var i = 0
        while (i < d.size) {
            val prefix = d[i].toInt() and 0xFF
            val length = intArrayOf(0, 1, 2, 4)[prefix and 0x03]
            var value = 0
            for (b in 0 until length) value = value or ((d[i + 1 + b].toInt() and 0xFF) shl (8 * b))
            when (prefix and 0xFC) {
                0x84 -> reportId = value
                0x74 -> size = value
                0x94 -> count = value
                0x80 -> inputBits.merge(reportId, size * count, Int::plus)
                0x90 -> outputBits.merge(reportId, size * count, Int::plus)
                0xA0 -> depth++
                0xC0 -> depth--
            }
            i += 1 + length
        }
        assertEquals(d.size, i)
        assertEquals(0, depth)
        assertEquals(48 * 8, inputBits[0x30])
        assertEquals(48 * 8, inputBits[0x21])
        assertEquals(48 * 8, outputBits[0x01])
        assertEquals(48 * 8, outputBits[0x10])
        assertEquals(setOf(0x21, 0x30, 0x31, 0x32, 0x33, 0x3F), inputBits.keys)
        assertEquals(setOf(0x01, 0x10, 0x11, 0x12), outputBits.keys)
    }

    @Test
    fun addressParsing() {
        assertArrayEquals(bytes(0x7C, 0xBB, 0x8A, 0x12, 0x34, 0x56), ProControllerProtocol.parseAddress("7c:bb:8a:12:34:56"))
        assertArrayEquals(bytes(0x7C, 0xBB, 0x8A, 0x12, 0x34, 0x56), ProControllerProtocol.parseAddress(" 7C-BB-8A-12-34-56 "))
        assertNull(ProControllerProtocol.parseAddress("7C:BB:8A:12:34"))
        assertNull(ProControllerProtocol.parseAddress("7C:BB:8A:12:34:5G"))
        assertNull(ProControllerProtocol.parseAddress(""))
    }
}
