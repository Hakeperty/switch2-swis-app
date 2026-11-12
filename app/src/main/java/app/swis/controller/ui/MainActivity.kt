package app.swis.controller.ui

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import app.swis.controller.R
import app.swis.controller.bt.SwitchHidService
import app.swis.controller.core.ControllerHub
import app.swis.controller.core.ControllerHub.Link
import app.swis.controller.core.ProControllerProtocol
import app.swis.controller.data.Prefs
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/** Connection screen: start the controller, pair or reconnect, and read what the console said. */
class MainActivity : AppCompatActivity(), ControllerHub.Listener {

    private enum class Pending { NONE, START, RECONNECT }

    private lateinit var statusView: TextView
    private lateinit var detailView: TextView
    private lateinit var logView: TextView
    private lateinit var addressView: EditText

    private var pending = Pending.NONE

    /** Set when the user asked to pair: become visible as soon as the profile is registered. */
    private var wantDiscoverable = false
    private var logRefreshQueued = false
    private var askedOptionalPermissions = false

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            if (SwitchHidService.hasBluetoothPermission(this)) {
                proceed()
            } else {
                pending = Pending.NONE
                toast("Bluetooth permission is needed to act as a controller.")
            }
        }

    private val enableBluetoothLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (bluetoothAdapter()?.isEnabled == true) {
                proceed()
            } else {
                pending = Pending.NONE
                toast("Bluetooth is still off.")
            }
        }

    private val discoverableLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_CANCELED) {
                ControllerHub.log("Visibility was declined, so the console cannot find the phone to pair.")
            } else {
                ControllerHub.log("Phone is visible for ${result.resultCode} s. Open Change Grip/Order on the console now.")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        findViewById<android.view.View>(R.id.root).padForSystemBars()

        statusView = findViewById(R.id.status)
        detailView = findViewById(R.id.status_detail)
        logView = findViewById(R.id.log)
        addressView = findViewById(R.id.address)
        addressView.setText(Prefs.bluetoothAddressText(this))

        findViewById<android.view.View>(R.id.start).setOnClickListener {
            pending = Pending.START
            proceed()
        }
        findViewById<android.view.View>(R.id.reconnect).setOnClickListener {
            pending = Pending.RECONNECT
            proceed()
        }
        findViewById<android.view.View>(R.id.stop).setOnClickListener {
            wantDiscoverable = false
            SwitchHidService.stop(this)
        }
        findViewById<android.view.View>(R.id.gamepad).setOnClickListener {
            startActivity(Intent(this, GamepadActivity::class.java))
        }
        findViewById<android.view.View>(R.id.macros).setOnClickListener {
            startActivity(Intent(this, MacroActivity::class.java))
        }
        findViewById<android.view.View>(R.id.save_address).setOnClickListener { saveAddress() }
        findViewById<android.view.View>(R.id.copy_log).setOnClickListener {
            getSystemService(ClipboardManager::class.java)
                .setPrimaryClip(ClipData.newPlainText("SWIS Controller log", ControllerHub.logText()))
            toast("Log copied.")
        }
        findViewById<android.view.View>(R.id.clear_log).setOnClickListener { ControllerHub.clearLog() }
    }

    override fun onStart() {
        super.onStart()
        ControllerHub.addListener(this)
        renderStatus()
        renderLog()
    }

    override fun onStop() {
        ControllerHub.removeListener(this)
        super.onStop()
    }

    override fun onStatusChanged() = runOnUiThread {
        renderStatus()
        if (wantDiscoverable && ControllerHub.status.link == Link.WAITING) {
            wantDiscoverable = false
            requestDiscoverable()
        }
    }

    override fun onLogChanged() {
        // Many log lines can arrive at once during setup; repaint once for the batch.
        runOnUiThread {
            if (logRefreshQueued) return@runOnUiThread
            logRefreshQueued = true
            logView.postDelayed({
                logRefreshQueued = false
                renderLog()
            }, 100)
        }
    }

    /** Walks through whatever is still missing, then carries out the pending request. */
    private fun proceed() {
        val missing = requiredPermissions().filter {
            ContextCompat.checkSelfPermission(this, it) != android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        // Bluetooth is mandatory; the notification permission is asked for once and then
        // respected either way.
        val bluetoothMissing = !SwitchHidService.hasBluetoothPermission(this)
        if (missing.isNotEmpty() && (bluetoothMissing || !askedOptionalPermissions)) {
            askedOptionalPermissions = true
            permissionLauncher.launch(missing.toTypedArray())
            return
        }
        val adapter = bluetoothAdapter()
        if (adapter == null) {
            pending = Pending.NONE
            toast("This device has no Bluetooth.")
            return
        }
        if (!adapter.isEnabled) {
            enableBluetoothLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            return
        }
        val request = pending
        pending = Pending.NONE
        when (request) {
            Pending.START -> confirmAddressThenStart()
            Pending.RECONNECT -> pickHostAndReconnect(adapter)
            Pending.NONE -> Unit
        }
    }

    private fun requiredPermissions(): List<String> = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(Manifest.permission.BLUETOOTH_CONNECT)
            add(Manifest.permission.BLUETOOTH_ADVERTISE)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun confirmAddressThenStart() {
        if (Prefs.bluetoothAddress(this) != null || ControllerHub.status.link !in setOf(Link.STOPPED, Link.ERROR)) {
            startAndPair()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Bluetooth address not set")
            .setMessage(
                "During setup the console asks the controller for its Bluetooth address. " +
                    "Android does not let apps read it, so enter it on this screen first " +
                    "(Settings → About phone → Status information → Bluetooth address).\n\n" +
                    "You can try without it, but pairing may fail."
            )
            .setPositiveButton("Enter address") { _, _ -> addressView.requestFocus() }
            .setNegativeButton("Try without") { _, _ -> startAndPair() }
            .show()
    }

    private fun startAndPair() {
        when (ControllerHub.status.link) {
            Link.READY, Link.HANDSHAKE -> toast("A console is already connected.")
            Link.WAITING -> requestDiscoverable()
            else -> {
                wantDiscoverable = true
                SwitchHidService.start(this)
            }
        }
    }

    private fun requestDiscoverable() {
        try {
            discoverableLauncher.launch(
                Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
                    .putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, DISCOVERABLE_SECONDS)
            )
        } catch (e: Exception) {
            ControllerHub.log("Could not ask to become visible: $e")
        }
    }

    @SuppressLint("MissingPermission") // proceed() only gets here with BLUETOOTH_CONNECT granted
    private fun pickHostAndReconnect(adapter: BluetoothAdapter) {
        val last = Prefs.lastHost(this)
        val bonded = try {
            adapter.bondedDevices.orEmpty().sortedByDescending { it.address == last }
        } catch (e: SecurityException) {
            emptyList()
        }
        if (bonded.isEmpty()) {
            toast("No paired devices yet. Use “Start controller and pair” first.")
            return
        }
        val labels = bonded.map { "${it.name ?: "Unknown"}\n${it.address}" }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle("Reconnect to which console?")
            .setItems(labels) { _, which -> SwitchHidService.connect(this, bonded[which].address) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun saveAddress() {
        val text = addressView.text.toString().trim()
        if (text.isNotEmpty() && ProControllerProtocol.parseAddress(text) == null) {
            addressView.error = "Expected 12 hex digits, like 7C:BB:8A:12:34:56"
            return
        }
        Prefs.saveBluetoothAddress(this, text)
        toast(if (text.isEmpty()) "Address cleared." else "Address saved. It is used the next time the controller starts.")
    }

    private fun renderStatus() {
        val status = ControllerHub.status
        val (title, color) = when (status.link) {
            Link.STOPPED -> "Controller is off" to R.color.status_off
            Link.STARTING -> "Starting…" to R.color.status_wait
            Link.WAITING -> "Waiting for the console" to R.color.status_wait
            Link.HANDSHAKE -> "Console connected, setting up…" to R.color.status_wait
            Link.READY -> "Connected" to R.color.status_ok
            Link.ERROR -> "Something went wrong" to R.color.status_error
        }
        statusView.text = title
        statusView.setTextColor(ContextCompat.getColor(this, color))
        detailView.text = listOfNotNull(status.host, status.detail.takeIf { it.isNotEmpty() }).joinToString(" · ")
    }

    private fun renderLog() {
        logView.text = ControllerHub.logText().ifEmpty { "Nothing yet." }
    }

    private fun bluetoothAdapter(): BluetoothAdapter? = getSystemService(BluetoothManager::class.java)?.adapter

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    private companion object {
        const val DISCOVERABLE_SECONDS = 300
    }
}
