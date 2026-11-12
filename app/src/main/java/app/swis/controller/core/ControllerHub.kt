package app.swis.controller.core

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Process-wide meeting point between the touch UI, the macro player and the Bluetooth link.
 * The UI writes what the player's hands are doing, macros write what they hold, and the link
 * reads the merged result.
 */
object ControllerHub {
    enum class Link {
        /** Nothing running. */
        STOPPED,

        /** Registering with the phone's Bluetooth stack. */
        STARTING,

        /** Registered and waiting for a console to connect. */
        WAITING,

        /** A console connected and is running its setup conversation. */
        HANDSHAKE,

        /** The console accepted the controller; inputs are live. */
        READY,
        ERROR,
    }

    data class Status(val link: Link, val detail: String = "", val host: String? = null)

    interface Listener {
        fun onStatusChanged() {}
        fun onLogChanged() {}
        fun onMacroChanged() {}
    }

    private val listeners = CopyOnWriteArraySet<Listener>()
    private val lock = Any()

    @Volatile
    var status = Status(Link.STOPPED)
        private set

    private var manual = PadState.NEUTRAL
    private var macroState: PadState? = null

    /** What the console should currently see. */
    @Volatile
    var output: PadState = PadState.NEUTRAL
        private set

    /** Called (from any thread) whenever [output] changes. */
    @Volatile
    var outputListener: (() -> Unit)? = null

    private var player: MacroPlayer? = null

    @Volatile
    var playingMacroId: String? = null
        private set

    private var recorder: MacroRecorder? = null
    val isRecording: Boolean get() = synchronized(lock) { recorder != null }

    private val log = ArrayDeque<String>()
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.ROOT)

    fun addListener(l: Listener) = listeners.add(l)
    fun removeListener(l: Listener) = listeners.remove(l)

    fun setStatus(link: Link, detail: String = "", host: String? = null) {
        status = Status(link, detail, host)
        listeners.forEach { it.onStatusChanged() }
    }

    /** The state of the on-screen controls. */
    fun setManual(state: PadState) {
        synchronized(lock) {
            manual = state
            recorder?.onState(state)
            publishLocked()
        }
    }

    private fun setMacroState(owner: MacroPlayer, state: PadState?) {
        synchronized(lock) {
            if (player !== owner) return
            macroState = state
            publishLocked()
        }
    }

    private fun publishLocked() {
        val merged = PadState.merge(manual, macroState)
        if (merged == output) return
        output = merged
        outputListener?.invoke()
    }

    /** Starts [macro], replacing any macro that is already playing. */
    fun playMacro(id: String, macro: Macro) {
        val previous: MacroPlayer?
        val next: MacroPlayer
        synchronized(lock) {
            previous = player
            lateinit var created: MacroPlayer
            created = MacroPlayer(
                macro,
                output = { state -> setMacroState(created, state) },
                onFinished = { completed -> onPlayerFinished(created, macro, completed) },
            )
            next = created
            player = next
            macroState = null
            playingMacroId = id
            publishLocked()
        }
        previous?.stop()
        log("Macro started: ${macro.name}")
        next.start()
        listeners.forEach { it.onMacroChanged() }
    }

    fun stopMacro() {
        val current = synchronized(lock) { player }
        current?.stop()
    }

    private fun onPlayerFinished(owner: MacroPlayer, macro: Macro, completed: Boolean) {
        synchronized(lock) {
            if (player !== owner) return
            player = null
            macroState = null
            playingMacroId = null
            publishLocked()
        }
        log("Macro ${if (completed) "finished" else "stopped"}: ${macro.name} (${owner.cyclesDone} loops)")
        listeners.forEach { it.onMacroChanged() }
    }

    fun startRecording() {
        synchronized(lock) { recorder = MacroRecorder() }
        listeners.forEach { it.onMacroChanged() }
    }

    /** Ends the recording; null if nothing was pressed. */
    fun stopRecording(name: String): Macro? {
        val finished = synchronized(lock) {
            val r = recorder
            recorder = null
            r
        }
        listeners.forEach { it.onMacroChanged() }
        return finished?.finish(name)
    }

    fun log(message: String) {
        synchronized(log) {
            log.addLast("${timeFormat.format(Date())}  $message")
            while (log.size > MAX_LOG_LINES) log.removeFirst()
        }
        listeners.forEach { it.onLogChanged() }
    }

    fun logText(): String = synchronized(log) { log.joinToString("\n") }

    fun clearLog() {
        synchronized(log) { log.clear() }
        listeners.forEach { it.onLogChanged() }
    }

    private const val MAX_LOG_LINES = 400
}
