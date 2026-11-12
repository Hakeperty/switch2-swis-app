package app.swis.controller.bt

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.swis.controller.R
import app.swis.controller.core.ControllerHub
import app.swis.controller.core.ControllerHub.Link
import app.swis.controller.core.ProControllerProtocol
import app.swis.controller.core.ReportPump
import app.swis.controller.data.Prefs
import app.swis.controller.ui.MainActivity
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Presents the phone to the console as a Bluetooth HID "Pro Controller".
 *
 * Runs as a foreground service because Android unregisters a HID device app the moment its
 * process stops being foreground, which would drop the controller whenever the screen turns
 * off or another app is opened.
 */
@SuppressLint("MissingPermission") // every entry point checks hasBluetoothPermission() first
class SwitchHidService : Service() {

    private var adapter: BluetoothAdapter? = null

    // Bluetooth callbacks arrive on callbackExecutor, start/stop requests on the main thread,
    // so everything both sides touch is volatile.
    @Volatile
    private var hid: BluetoothHidDevice? = null

    @Volatile
    private var registered = false

    @Volatile
    private var host: BluetoothDevice? = null

    @Volatile
    private var pump: ReportPump? = null

    @Volatile
    private var running = false
    private lateinit var protocol: ProControllerProtocol

    /**
     * One thread for every Bluetooth callback keeps them in order: the console's first
     * question can never be handled before the "connected" event that precedes it.
     */
    private val callbackExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var pendingConnectAddress: String? = null
    private var sendFailureLogged = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> shutdown()
            ACTION_CONNECT -> {
                pendingConnectAddress = intent.getStringExtra(EXTRA_ADDRESS)
                if (running) connectPending() else startController()
            }
            else -> if (!running) startController()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        teardown()
        callbackExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun startController() {
        try {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, buildNotification(),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0,
            )
        } catch (e: Exception) {
            fail("Could not start in the foreground: ${e.message}")
            stopSelf()
            return
        }

        if (!hasBluetoothPermission(this)) {
            fail("Bluetooth permission was not granted.")
            stopSelf()
            return
        }
        val bt = getSystemService(BluetoothManager::class.java)?.adapter
        if (bt == null || !bt.isEnabled) {
            fail("Bluetooth is turned off. Turn it on and start again.")
            stopSelf()
            return
        }
        adapter = bt
        running = true
        protocol = ProControllerProtocol(Prefs.bluetoothAddress(this) ?: PLACEHOLDER_ADDRESS)
        if (Prefs.bluetoothAddress(this) == null) {
            ControllerHub.log("No phone Bluetooth address saved; reporting a placeholder to the console.")
        }

        ControllerHub.setStatus(Link.STARTING, "Registering with Bluetooth")
        ControllerHub.log("Starting. Phone: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}")
        val ok = bt.getProfileProxy(this, profileListener, BluetoothProfile.HID_DEVICE)
        if (!ok) {
            fail("This phone's Bluetooth does not offer the HID Device profile, so it cannot act as a controller.")
            shutdown()
        }
    }

    private val profileListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            if (profile != BluetoothProfile.HID_DEVICE || !running) return
            val device = proxy as BluetoothHidDevice
            hid = device
            val sdp = BluetoothHidDeviceAppSdpSettings(
                "Wireless Gamepad", "Gamepad", "Nintendo",
                HID_SUBCLASS_GAMEPAD,
                ProControllerProtocol.HID_DESCRIPTOR,
            )
            val accepted = try {
                device.registerApp(sdp, null, null, callbackExecutor, hidCallback)
            } catch (e: Exception) {
                ControllerHub.log("registerApp threw: $e")
                false
            }
            if (!accepted) {
                fail("Android refused to register the controller profile. Close other \"phone as keyboard/gamepad\" apps and try again.")
                shutdown()
            }
        }

        override fun onServiceDisconnected(profile: Int) {
            if (profile != BluetoothProfile.HID_DEVICE) return
            ControllerHub.log("Bluetooth HID service went away (Bluetooth turned off?)")
            hid = null
            registered = false
            stopPump()
            if (running) {
                fail("Bluetooth stopped. Start again once it is back on.")
                shutdown()
            }
        }
    }

    private val hidCallback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, isRegistered: Boolean) {
            registered = isRegistered
            if (!running) return
            if (isRegistered) {
                ControllerHub.log("Registered as a HID gamepad.")
                applyControllerName()
                logDeviceClass()
                ControllerHub.setStatus(Link.WAITING, "Waiting for the console")
                connectPending()
            } else {
                ControllerHub.log("Android unregistered the controller profile.")
                stopPump()
                fail("The controller profile was unregistered by Android. Start again.")
                mainHandler.post { shutdown() }
            }
        }

        override fun onConnectionStateChanged(device: BluetoothDevice, state: Int) {
            val name = safeName(device)
            when (state) {
                BluetoothProfile.STATE_CONNECTING -> ControllerHub.log("Connecting to $name…")
                BluetoothProfile.STATE_CONNECTED -> {
                    ControllerHub.log("Connected to $name. Waiting for it to identify the controller.")
                    host = device
                    Prefs.saveLastHost(this@SwitchHidService, device.address)
                    synchronized(protocol) { protocol.reset() }
                    ControllerHub.setStatus(Link.HANDSHAKE, "Console connected, setting up", name)
                    startPump(device)
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    if (host == device || host == null) {
                        ControllerHub.log("Disconnected from $name.")
                        host = null
                        stopPump()
                        if (running) ControllerHub.setStatus(Link.WAITING, "Disconnected. Waiting for the console")
                    }
                }
            }
        }

        override fun onInterruptData(device: BluetoothDevice, reportId: Byte, data: ByteArray) {
            handleOutputReport(reportId.toInt() and 0xFF, data)
        }

        override fun onSetReport(device: BluetoothDevice, type: Byte, id: Byte, data: ByteArray) {
            handleOutputReport(id.toInt() and 0xFF, data)
            hid?.reportError(device, BluetoothHidDevice.ERROR_RSP_SUCCESS)
        }

        override fun onGetReport(device: BluetoothDevice, type: Byte, id: Byte, bufferSize: Int) {
            val h = hid ?: return
            if (type == BluetoothHidDevice.REPORT_TYPE_INPUT && (id.toInt() and 0xFF) == ProControllerProtocol.INPUT_FULL) {
                val payload = synchronized(protocol) { protocol.buildInputReport(ControllerHub.output) }
                h.replyReport(device, type, id, payload)
            } else {
                h.reportError(device, BluetoothHidDevice.ERROR_RSP_INVALID_RPT_ID)
            }
        }

        override fun onVirtualCableUnplug(device: BluetoothDevice) {
            ControllerHub.log("${safeName(device)} removed this controller from its list (virtual cable unplug).")
        }
    }

    private fun handleOutputReport(reportId: Int, data: ByteArray) {
        val p = pump ?: return
        val reply = synchronized(protocol) {
            protocol.handleOutputReport(reportId, data, ControllerHub.output)
        } ?: return
        p.onConsoleSpoke()
        ControllerHub.log("Console → ${reply.description}")
        reply.payload?.let { p.queueReply(it) }

        val ready = synchronized(protocol) { protocol.handshakeComplete }
        if (ready && ControllerHub.status.link != Link.READY) {
            val player = synchronized(protocol) { protocol.playerNumber }
            ControllerHub.log("Console accepted the controller as player $player.")
            ControllerHub.setStatus(Link.READY, "Connected as player $player", ControllerHub.status.host)
        }
    }

    private fun connectPending() {
        val address = pendingConnectAddress ?: return
        val h = hid ?: return
        if (!registered) return
        pendingConnectAddress = null
        val device = try {
            adapter?.getRemoteDevice(address)
        } catch (e: IllegalArgumentException) {
            null
        } ?: return
        ControllerHub.log("Asking ${safeName(device)} to reconnect…")
        if (!h.connect(device)) ControllerHub.log("Android refused to start the connection.")
    }

    /**
     * The console looks for a device named exactly "Pro Controller". The phone's previous
     * name is remembered so it can be put back when the controller stops.
     */
    private fun applyControllerName() {
        val bt = adapter ?: return
        try {
            val current = bt.name
            if (current != CONTROLLER_NAME) {
                if (current != null) Prefs.saveOriginalName(this, current)
                if (bt.setName(CONTROLLER_NAME)) {
                    ControllerHub.log("Bluetooth name changed from \"$current\" to \"$CONTROLLER_NAME\".")
                } else {
                    ControllerHub.log("Could not rename Bluetooth. Rename the phone to \"$CONTROLLER_NAME\" in Settings by hand.")
                }
            }
        } catch (e: Exception) {
            ControllerHub.log("Could not rename Bluetooth ($e). Rename the phone to \"$CONTROLLER_NAME\" in Settings by hand.")
        }
    }

    /**
     * Writes the phone's Bluetooth device class to the log. It is the one part of a Pro
     * Controller's identity that Android sets itself and gives apps no way to change, so
     * when a console ignores the phone this line shows whether that is the likely reason.
     */
    private fun logDeviceClass() {
        val deviceClass = try {
            val getter = BluetoothAdapter::class.java.getMethod("getBluetoothClass")
            (getter.invoke(adapter) as? BluetoothClass)?.deviceClass
        } catch (e: Throwable) {
            null // not readable on this Android version; nothing to report
        } ?: return
        if (deviceClass == PRO_CONTROLLER_DEVICE_CLASS) {
            ControllerHub.log("Bluetooth device class is 0x%04X (gamepad), the same as a real Pro Controller.".format(deviceClass))
        } else {
            ControllerHub.log(
                "Bluetooth device class is 0x%04X; a real Pro Controller reports 0x%04X (gamepad). Android does not let apps change it."
                    .format(deviceClass, PRO_CONTROLLER_DEVICE_CLASS)
            )
        }
    }

    private fun restoreName() {
        val bt = adapter ?: return
        val original = Prefs.originalName(this) ?: return
        try {
            if (bt.name == CONTROLLER_NAME && bt.setName(original)) {
                ControllerHub.log("Bluetooth name restored to \"$original\".")
            }
            Prefs.clearOriginalName(this)
        } catch (e: Exception) {
            ControllerHub.log("Could not restore the Bluetooth name: $e")
        }
    }

    private fun startPump(device: BluetoothDevice) {
        stopPump()
        sendFailureLogged = false
        pump = ReportPump(protocol, state = { ControllerHub.output }, send = { id, payload -> send(device, id, payload) }).also {
            ControllerHub.outputListener = it::wake
            it.start()
        }
    }

    private fun send(device: BluetoothDevice, reportId: Int, payload: ByteArray) {
        val h = hid ?: return
        val ok = try {
            h.sendReport(device, reportId, payload)
        } catch (e: Exception) {
            false
        }
        if (!ok && !sendFailureLogged) {
            sendFailureLogged = true
            ControllerHub.log("Android rejected a report (id 0x%02X). The link may have dropped.".format(reportId))
        }
    }

    private fun stopPump() {
        ControllerHub.outputListener = null
        pump?.shutdown()
        pump = null
    }

    private fun fail(message: String) {
        ControllerHub.log("ERROR: $message")
        ControllerHub.setStatus(Link.ERROR, message)
    }

    private fun shutdown() {
        val wasError = ControllerHub.status.link == Link.ERROR
        teardown()
        if (!wasError) ControllerHub.setStatus(Link.STOPPED)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun teardown() {
        if (!running && hid == null) return
        running = false
        ControllerHub.stopMacro()
        stopPump()
        val h = hid
        val bt = adapter
        if (h != null && hasBluetoothPermission(this)) {
            try {
                host?.let { h.disconnect(it) }
                if (registered) h.unregisterApp()
            } catch (e: Exception) {
                ControllerHub.log("While stopping: $e")
            }
            restoreName()
            bt?.closeProfileProxy(BluetoothProfile.HID_DEVICE, h)
        }
        hid = null
        host = null
        registered = false
        ControllerHub.log("Controller stopped.")
    }

    private fun safeName(device: BluetoothDevice): String =
        try {
            device.name ?: device.address
        } catch (e: SecurityException) {
            device.address
        }

    private fun buildNotification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW)
            )
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, SwitchHidService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, getString(R.string.action_stop), stop)
            .build()
    }

    companion object {
        const val ACTION_START = "app.swis.controller.START"
        const val ACTION_STOP = "app.swis.controller.STOP"
        const val ACTION_CONNECT = "app.swis.controller.CONNECT"
        const val EXTRA_ADDRESS = "address"

        const val CONTROLLER_NAME = "Pro Controller"

        /**
         * HIDDeviceSubclass exactly as a real Pro Controller publishes it. Android's own
         * SUBCLASS2_GAMEPAD constant (0x02) is the un-shifted minor class and is not what
         * the controller's SDP record contains.
         */
        private const val HID_SUBCLASS_GAMEPAD: Byte = 0x08

        private const val CHANNEL_ID = "controller"
        private const val NOTIFICATION_ID = 1

        /** Major + minor device class of a real Pro Controller: peripheral, gamepad. */
        private const val PRO_CONTROLLER_DEVICE_CLASS = 0x0508

        /** Used only when the user has not entered the phone's real address. */
        private val PLACEHOLDER_ADDRESS = byteArrayOf(0x7C, 0xBB.toByte(), 0x8A.toByte(), 0x4E, 0x21, 0x5D)

        fun hasBluetoothPermission(context: Context): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
            return ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED
        }

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context, Intent(context, SwitchHidService::class.java).setAction(ACTION_START)
            )
        }

        fun connect(context: Context, address: String) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, SwitchHidService::class.java).setAction(ACTION_CONNECT).putExtra(EXTRA_ADDRESS, address),
            )
        }

        fun stop(context: Context) {
            context.startService(Intent(context, SwitchHidService::class.java).setAction(ACTION_STOP))
        }
    }
}
