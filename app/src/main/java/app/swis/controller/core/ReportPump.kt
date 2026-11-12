package app.swis.controller.core

import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Sends reports to the console from one thread, so replies and input never interleave.
 *
 * Pacing follows NXBT, which consoles are known to accept: one report per [idleNanos] until
 * the console first speaks, then a steady report every [keepAliveNanos]. Once the console has
 * identified the controller, a report also goes out the moment the input changes, but never
 * closer than [minGapNanos] to the previous one: the Bluetooth link drains at a fixed rate,
 * and anything faster only queues up and arrives late.
 *
 * @param protocol shared with the receiving side; every use is synchronised on it
 * @param state the input to report right now
 * @param send delivers one report (report ID, payload) to the console
 */
class ReportPump(
    private val protocol: ProControllerProtocol,
    private val state: () -> PadState,
    private val send: (reportId: Int, payload: ByteArray) -> Unit,
    private val idleNanos: Long = 1_000_000_000L,
    private val keepAliveNanos: Long = 1_000_000_000L / 15,
    private val minGapNanos: Long = 8_000_000L,
) {
    private val replies = ConcurrentLinkedQueue<ByteArray>()
    private val monitor = Object()
    private var signalled = false

    @Volatile
    private var alive = true

    @Volatile
    private var consoleSpoke = false

    private val thread = Thread({ run() }, "hid-report-pump").apply {
        priority = Thread.MAX_PRIORITY
        isDaemon = true
    }

    fun start() = thread.start()

    /** The input changed, or there is something new to send. */
    fun wake() {
        synchronized(monitor) {
            signalled = true
            monitor.notifyAll()
        }
    }

    /** Queues a subcommand reply; replies always go out before the next input report. */
    fun queueReply(payload: ByteArray) {
        replies.add(payload)
        wake()
    }

    /** The console sent its first report: switch from the slow idle pace to the working pace. */
    fun onConsoleSpoke() {
        consoleSpoke = true
    }

    fun shutdown() {
        alive = false
        wake()
    }

    private fun run() {
        var lastSent: PadState? = null
        // Start one idle interval in the past so the very first report goes out immediately;
        // that first report is what prompts the console to start talking.
        var lastSentAt = System.nanoTime() - idleNanos
        while (alive) {
            while (true) {
                val reply = replies.poll() ?: break
                send(ProControllerProtocol.INPUT_SUBCOMMAND_REPLY, reply)
            }

            // Until the console has asked who we are, reports carry no input at all, so
            // there is nothing a change could tell it.
            val live = synchronized(protocol) { protocol.deviceInfoQueried }
            val interval = if (consoleSpoke) keepAliveNanos else idleNanos
            val current = state()
            val now = System.nanoTime()
            val sinceLast = now - lastSentAt
            val changed = live && current != lastSent

            val waitNanos: Long
            if (sinceLast >= interval || (changed && sinceLast >= minGapNanos)) {
                val payload = synchronized(protocol) { protocol.buildInputReport(current) }
                send(ProControllerProtocol.INPUT_FULL, payload)
                lastSent = current
                lastSentAt = now
                waitNanos = interval
            } else if (changed) {
                waitNanos = minGapNanos - sinceLast
            } else {
                waitNanos = interval - sinceLast
            }

            synchronized(monitor) {
                if (!signalled && alive) {
                    try {
                        monitor.wait(waitNanos / 1_000_000, (waitNanos % 1_000_000).toInt())
                    } catch (e: InterruptedException) {
                        return
                    }
                }
                signalled = false
            }
        }
    }
}
