package app.swis.controller.data

import android.content.Context
import android.content.SharedPreferences
import app.swis.controller.core.ProControllerProtocol

/** Small settings that outlive the process. */
object Prefs {
    private const val FILE = "settings"
    private const val KEY_ADDRESS = "bluetooth_address"
    private const val KEY_ORIGINAL_NAME = "original_bluetooth_name"
    private const val KEY_SELECTED_MACRO = "selected_macro"
    private const val KEY_LAST_HOST = "last_host_address"

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** The phone's own Bluetooth address as typed in by the user, or null. */
    fun bluetoothAddress(context: Context): ByteArray? =
        prefs(context).getString(KEY_ADDRESS, null)?.let(ProControllerProtocol::parseAddress)

    fun bluetoothAddressText(context: Context): String = prefs(context).getString(KEY_ADDRESS, "") ?: ""

    fun saveBluetoothAddress(context: Context, text: String) {
        prefs(context).edit().putString(KEY_ADDRESS, text.trim()).apply()
    }

    fun originalName(context: Context): String? = prefs(context).getString(KEY_ORIGINAL_NAME, null)

    fun saveOriginalName(context: Context, name: String) {
        prefs(context).edit().putString(KEY_ORIGINAL_NAME, name).apply()
    }

    fun clearOriginalName(context: Context) {
        prefs(context).edit().remove(KEY_ORIGINAL_NAME).apply()
    }

    fun selectedMacro(context: Context): String? = prefs(context).getString(KEY_SELECTED_MACRO, null)

    fun saveSelectedMacro(context: Context, id: String) {
        prefs(context).edit().putString(KEY_SELECTED_MACRO, id).apply()
    }

    fun lastHost(context: Context): String? = prefs(context).getString(KEY_LAST_HOST, null)

    fun saveLastHost(context: Context, address: String) {
        prefs(context).edit().putString(KEY_LAST_HOST, address).apply()
    }
}
