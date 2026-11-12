package app.swis.controller.core

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

class ReportPumpTest {
    private class Sent(val atNanos: Long, val id: Int, val payload: ByteArray) {
        val pressesA: Boolean get() = payload[2].toInt() and 0x08 != 0
        val pressesB: Boolean get() = payload[2].toInt() and 0x04 != 0
    }

    private val protocol = ProControllerProtocol(ByteArray(6))
    private val sent = CopyOnWriteArrayList<Sent>()

    @Volatile
    private var state = PadState.NEUTRAL
    private var pump: ReportPump? = null

    private fun pump(idleMs: Long = 150, keepAliveMs: Long = 50, minGapMs: Long = 8): ReportPump =
        ReportPump(
            protocol, state = { state },
            send = { id, payload -> sent.add(Sent(System.nanoTime(), id, payload)) },
            idleNanos = idleMs * 1_000_000, keepAliveNanos = keepAliveMs * 1_000_000, minGapNanos = minGapMs * 1_000_000,
        ).also { pump = it }

    /** The console asks for device info; from then on reports carry input. */
    private fun identify() {
        val data = ByteArray(48).also { it[9] = 0x02 }
        protocol.handleOutputReport(0x01, data, PadState.NEUTRAL)
        assertTrue(protocol.deviceInfoQueried)
    }

    private fun inputs() = sent.filter { it.id == ProControllerProtocol.INPUT_FULL }

    @After
    fun tearDown() {
        pump?.shutdown()
    }

    @Test
    fun sendsAtOnceThenSlowlyUntilTheConsoleSpeaks() {
        pump().start()
        Thread.sleep(30)
        assertEquals(1, inputs().size)          // the report that prompts the console
        Thread.sleep(350)
        assertEquals(3, inputs().size)          // at 0, 150 and 300 ms
    }

    @Test
    fun steadyKeepAliveOnceTheConsoleSpeaks() {
        val p = pump()
        p.onConsoleSpoke()
        p.start()
        Thread.sleep(500)
        assertTrue("sent ${inputs().size}", inputs().size in 9..11)   // every 50 ms
    }

    @Test
    fun inputChangesAreNotRushedBeforeTheConsoleIdentifiesTheController() {
        val p = pump(keepAliveMs = 200)
        p.onConsoleSpoke()
        p.start()
        Thread.sleep(40)
        state = PadState.of(Button.A)
        p.wake()
        Thread.sleep(60)
        assertEquals(1, inputs().size)          // nothing extra: such reports carry no input yet
    }

    @Test
    fun inputChangeGoesOutImmediatelyOnceIdentified() {
        identify()
        val p = pump(keepAliveMs = 300)
        p.onConsoleSpoke()
        p.start()
        Thread.sleep(60)
        val pressedAt = System.nanoTime()
        state = PadState.of(Button.A)
        p.wake()
        Thread.sleep(40)
        val report = inputs().first { it.pressesA }
        val delayMs = (report.atNanos - pressedAt) / 1e6
        assertTrue("press took $delayMs ms to go out", delayMs < 5.0)
    }

    @Test
    fun rapidChangesAreSpacedAndTheLastOneWins() {
        identify()
        val p = pump(keepAliveMs = 300, minGapMs = 20)
        p.onConsoleSpoke()
        p.start()
        Thread.sleep(60)
        state = PadState.of(Button.A)
        p.wake()
        Thread.sleep(2)
        state = PadState.of(Button.B)
        p.wake()
        Thread.sleep(80)
        val a = inputs().first { it.pressesA }
        val b = inputs().first { it.pressesB }
        val gapMs = (b.atNanos - a.atNanos) / 1e6
        assertTrue("gap was $gapMs ms", gapMs >= 19.5 && gapMs < 30.0)
        assertTrue(inputs().last().pressesB)
    }

    @Test
    fun repliesKeepTheirOrderAndGoBeforeInput() {
        val p = pump(idleMs = 1000)
        p.start()
        Thread.sleep(30)
        sent.clear()
        p.onConsoleSpoke()
        repeat(3) { i -> p.queueReply(ByteArray(48).also { it[13] = i.toByte() }) }
        Thread.sleep(40)
        val replies = sent.filter { it.id == ProControllerProtocol.INPUT_SUBCOMMAND_REPLY }
        assertEquals(listOf(0, 1, 2), replies.map { it.payload[13].toInt() })
    }

    @Test
    fun shutdownStopsSending() {
        val p = pump()
        p.onConsoleSpoke()
        p.start()
        Thread.sleep(80)
        p.shutdown()
        Thread.sleep(30)
        val count = sent.size
        Thread.sleep(150)
        assertEquals(count, sent.size)
    }
}
