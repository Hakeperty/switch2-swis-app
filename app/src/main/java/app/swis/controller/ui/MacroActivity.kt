package app.swis.controller.ui

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import app.swis.controller.R
import app.swis.controller.core.ControllerHub
import app.swis.controller.core.ControllerHub.Link
import app.swis.controller.core.Macro
import app.swis.controller.core.MacroLibrary
import app.swis.controller.core.MacroSyntaxException
import app.swis.controller.core.MacroText
import app.swis.controller.data.MacroStore
import app.swis.controller.data.Prefs
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/** Pick a macro, edit its text, and try it out. */
class MacroActivity : AppCompatActivity(), ControllerHub.Listener {

    private lateinit var store: MacroStore
    private lateinit var picker: Spinner
    private lateinit var editor: EditText
    private lateinit var info: TextView
    private lateinit var play: MaterialButton
    private lateinit var remove: MaterialButton

    private var entries: List<MacroStore.Entry> = emptyList()
    private var currentId: String = MacroLibrary.INFINITE_JUMP_SLASH_ID

    /** True while code (not the user) is changing the spinner or the editor. */
    private var loading = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_macro)
        findViewById<View>(R.id.root).padForSystemBars()

        store = MacroStore(this)
        picker = findViewById(R.id.picker)
        editor = findViewById(R.id.editor)
        info = findViewById(R.id.info)
        play = findViewById(R.id.play)
        remove = findViewById(R.id.remove)

        currentId = savedInstanceState?.getString(STATE_ID)
            ?: Prefs.selectedMacro(this)?.takeIf { store.get(it) != null }
            ?: MacroLibrary.INFINITE_JUMP_SLASH_ID

        picker.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (loading || entries[position].id == currentId) return
                saveQuietly()
                currentId = entries[position].id
                Prefs.saveSelectedMacro(this@MacroActivity, currentId)
                load()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        editor.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                if (!loading) renderInfo()
            }
        })

        play.setOnClickListener { togglePlay() }
        findViewById<View>(R.id.save).setOnClickListener {
            if (parseOrNull() == null) {
                toast("Fix the error shown below the editor first.")
            } else {
                store.save(currentId, editor.text.toString())
                load()
                toast("Saved.")
            }
        }
        remove.setOnClickListener { confirmRemove() }
        findViewById<View>(R.id.create).setOnClickListener {
            saveQuietly()
            currentId = store.create(MacroStore.NEW_MACRO_TEMPLATE)
            Prefs.saveSelectedMacro(this, currentId)
            load()
        }

        load()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_ID, currentId)
    }

    override fun onStart() {
        super.onStart()
        ControllerHub.addListener(this)
        renderPlayButton()
    }

    override fun onStop() {
        ControllerHub.removeListener(this)
        saveQuietly()
        super.onStop()
    }

    override fun onMacroChanged() = runOnUiThread { renderPlayButton() }

    /** Reloads the list and shows [currentId] in the editor. */
    private fun load() {
        loading = true
        entries = store.all()
        val index = entries.indexOfFirst { it.id == currentId }.coerceAtLeast(0)
        val entry = entries[index]
        currentId = entry.id
        picker.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            entries.map { if (it.isBuiltIn && it.isModified) "${it.name} (edited)" else it.name },
        )
        picker.setSelection(index)
        editor.setText(entry.text)
        remove.setText(if (entry.isBuiltIn) R.string.macro_reset else R.string.macro_delete)
        remove.isEnabled = !entry.isBuiltIn || entry.isModified
        loading = false
        renderInfo()
    }

    /** Keeps edits when leaving, but never overwrites a working macro with a broken one. */
    private fun saveQuietly() {
        val text = editor.text.toString()
        val stored = store.get(currentId) ?: return
        if (text != stored.text && parseOrNull() != null) store.save(currentId, text)
    }

    private fun parseOrNull(): Macro? = try {
        MacroText.parse(editor.text.toString())
    } catch (e: MacroSyntaxException) {
        null
    }

    private fun renderInfo() {
        try {
            val macro = MacroText.parse(editor.text.toString())
            val repeats = if (macro.isInfinite) "repeats until stopped" else "repeats ${macro.loops}×"
            val frames = Math.round(macro.cycleMs * 60 / 1000f)
            info.text = "${macro.body.size} steps per loop · ${macro.cycleMs} ms (≈$frames frames) · $repeats"
            info.setTextColor(ContextCompat.getColor(this, R.color.status_ok))
        } catch (e: MacroSyntaxException) {
            info.text = e.message
            info.setTextColor(ContextCompat.getColor(this, R.color.status_error))
        }
    }

    private fun renderPlayButton() {
        play.setText(if (ControllerHub.playingMacroId != null) R.string.macro_stop else R.string.macro_play)
    }

    private fun togglePlay() {
        if (ControllerHub.playingMacroId != null) {
            ControllerHub.stopMacro()
            return
        }
        val macro = parseOrNull()
        if (macro == null) {
            toast("Fix the error shown below the editor first.")
            return
        }
        store.save(currentId, editor.text.toString())
        if (ControllerHub.status.link != Link.READY) {
            toast("No console is connected, so nothing will receive these inputs.")
        }
        ControllerHub.playMacro(currentId, macro)
    }

    private fun confirmRemove() {
        val entry = store.get(currentId) ?: return
        val builtIn = entry.isBuiltIn
        MaterialAlertDialogBuilder(this)
            .setTitle(if (builtIn) "Reset “${entry.name}”?" else "Delete “${entry.name}”?")
            .setMessage(if (builtIn) "Your edits to this macro will be replaced by the original." else "This macro will be removed.")
            .setPositiveButton(if (builtIn) R.string.macro_reset else R.string.macro_delete) { _, _ ->
                store.remove(currentId)
                if (!builtIn) currentId = MacroLibrary.INFINITE_JUMP_SLASH_ID
                load()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    private companion object {
        const val STATE_ID = "macro_id"
    }
}
