package app.swis.controller.core

import java.util.concurrent.locks.LockSupport

/**
 * Plays a [Macro] on its own thread. Step boundaries are computed from the start time, not
 * from the previous step, so timing errors never accumulate over a long loop.
 *
 * @param output receives each state to hold, and null once when playback ends
 * @param onFinished called once when playback ends for any reason
 */
class MacroPlayer(
    private val macro: Macro,
    private val output: (PadState?) -> Unit,
    private val onFinished: (completed: Boolean) -> Unit = {},
    private val clockNanos: () -> Long = System::nanoTime,
) {
    @Volatile
    private var stopRequested = false
    private var thread: Thread? = null

    /** Completed passes through the loop body. */
    @Volatile
    var cyclesDone = 0
        private set

    val isRunning: Boolean get() = thread?.isAlive == true

    @Synchronized
    fun start() {
        check(thread == null) { "A MacroPlayer can only be started once" }
        thread = Thread({ run() }, "macro-player").apply {
            priority = Thread.MAX_PRIORITY
            isDaemon = true
            start()
        }
    }

    /** Asks playback to end; the neutral/null output follows from the player thread. */
    fun stop() {
        stopRequested = true
        thread?.let { LockSupport.unpark(it) }
    }

    private fun run() {
        var completed = false
        try {
            var deadline = clockNanos()
            fun play(steps: List<MacroStep>): Boolean {
                for (step in steps) {
                    if (stopRequested) return false
                    output(step.state)
                    deadline += macro.scaled(step.durationMs) * 1_000_000L
                    if (!sleepUntil(deadline)) return false
                }
                return true
            }

            if (play(macro.intro)) {
                var cycle = 0
                while (macro.isInfinite || cycle < macro.loops) {
                    if (!play(macro.body)) break
                    cycle++
                    cyclesDone = cycle
                }
                completed = !stopRequested
            }
        } finally {
            output(null)
            onFinished(completed)
        }
    }

    /** Sleeps coarsely, then spins through the last stretch for sub-millisecond accuracy. */
    private fun sleepUntil(deadline: Long): Boolean {
        while (true) {
            if (stopRequested) return false
            val remaining = deadline - clockNanos()
            if (remaining <= 0) return true
            // Below SPIN_NANOS the loop simply re-reads the clock: a busy wait of at most
            // 1.5 ms per step, which is what keeps step edges accurate.
            if (remaining > SPIN_NANOS) LockSupport.parkNanos(remaining - SPIN_NANOS)
        }
    }

    private companion object {
        const val SPIN_NANOS = 1_500_000L
    }
}
