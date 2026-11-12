package app.swis.controller.data

import android.content.Context
import app.swis.controller.core.Macro
import app.swis.controller.core.MacroLibrary
import app.swis.controller.core.MacroSyntaxException
import app.swis.controller.core.MacroText

/**
 * Macros as editable text. Built-in macros always exist; editing one stores an override and
 * resetting removes it. Everything else is a user macro.
 */
class MacroStore(context: Context) {
    class Entry(val id: String, val text: String, val isBuiltIn: Boolean, val isModified: Boolean) {
        /** The macro's own name, or a fallback when the text does not currently parse. */
        val name: String
            get() = try {
                MacroText.parse(text).name
            } catch (e: MacroSyntaxException) {
                Regex("""(?m)^\s*name\s*=\s*(.+)$""").find(text)?.groupValues?.get(1)?.trim() ?: id
            }

        fun parse(): Macro = MacroText.parse(text)
    }

    private val prefs = context.applicationContext.getSharedPreferences("macros", Context.MODE_PRIVATE)

    fun all(): List<Entry> {
        val builtIns = MacroLibrary.builtIns.map { get(it.id)!! }
        val users = prefs.all.keys
            .filter { it.startsWith(USER_PREFIX) }
            .sorted()
            .mapNotNull { get(it) }
        return builtIns + users
    }

    fun get(id: String): Entry? {
        val stored = prefs.getString(id, null)
        val builtIn = MacroLibrary.builtIn(id)
        return when {
            builtIn != null -> Entry(id, stored ?: builtIn.defaultText, isBuiltIn = true, isModified = stored != null)
            stored != null -> Entry(id, stored, isBuiltIn = false, isModified = false)
            else -> null
        }
    }

    fun save(id: String, text: String) {
        prefs.edit().putString(id, text).apply()
    }

    /** Creates a user macro and returns its id. */
    fun create(text: String): String {
        val id = USER_PREFIX + System.currentTimeMillis()
        save(id, text)
        return id
    }

    /** Deletes a user macro, or restores a built-in one to its shipped text. */
    fun remove(id: String) {
        prefs.edit().remove(id).apply()
    }

    companion object {
        private const val USER_PREFIX = "user_"

        const val NEW_MACRO_TEMPLATE = "name = New macro\nloops = 1\nscale = 1.0\n\n100  A\n100  -\n"
    }
}
