package app.swis.controller.ui

import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import app.swis.controller.core.ControllerHub
import app.swis.controller.core.MacroLibrary
import app.swis.controller.core.MacroSyntaxException
import app.swis.controller.core.MacroText
import app.swis.controller.data.MacroStore
import app.swis.controller.data.Prefs
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Full-screen landscape controller with one-tap macro playback and recording. */
class GamepadActivity : AppCompatActivity(), GamepadView.Actions {

    private lateinit var pad: GamepadView
    private lateinit var store: MacroStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = MacroStore(this)
        pad = GamepadView(this)
        pad.actions = this
        setContentView(pad)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Hidden bars also keep edge swipes from being read as "back" while playing.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, pad).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        pad.macroName = selectedEntry().name
    }

    override fun onPause() {
        // Nothing may stay held on the console once the pad is no longer under the fingers.
        pad.releaseAll()
        super.onPause()
    }

    private fun selectedEntry(): MacroStore.Entry =
        Prefs.selectedMacro(this)?.let(store::get) ?: store.get(MacroLibrary.INFINITE_JUMP_SLASH_ID)!!

    override fun onMacroToggle() {
        if (ControllerHub.playingMacroId != null) {
            ControllerHub.stopMacro()
            return
        }
        val entry = selectedEntry()
        try {
            ControllerHub.playMacro(entry.id, entry.parse())
        } catch (e: MacroSyntaxException) {
            toast("${entry.name} has an error. ${e.message}")
        }
    }

    override fun onMacroPick() {
        val entries = store.all()
        MaterialAlertDialogBuilder(this)
            .setTitle("Macro on the play button")
            .setItems(entries.map { it.name }.toTypedArray()) { _, which ->
                Prefs.saveSelectedMacro(this, entries[which].id)
                pad.macroName = entries[which].name
            }
            .show()
    }

    override fun onRecordToggle() {
        if (!ControllerHub.isRecording) {
            ControllerHub.startRecording()
            toast("Recording. It starts with your first press; tap REC again to finish.")
            return
        }
        val name = "Recorded " + SimpleDateFormat("MMM d HH:mm:ss", Locale.getDefault()).format(Date())
        val macro = ControllerHub.stopRecording(name)
        if (macro == null) {
            toast("Nothing was pressed, so nothing was saved.")
            return
        }
        val id = store.create(MacroText.format(macro))
        Prefs.saveSelectedMacro(this, id)
        pad.macroName = name
        toast("Saved “$name” (${macro.body.size} steps) and put it on the play button.")
    }

    override fun onMenu() = finish()

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
}
