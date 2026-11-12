package app.swis.controller.ui

import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import app.swis.controller.R
import app.swis.controller.bt.SwitchHidService
import app.swis.controller.core.Button
import app.swis.controller.core.ControllerHub
import app.swis.controller.core.ControllerHub.Link
import app.swis.controller.core.MacroLibrary
import app.swis.controller.core.MacroText
import app.swis.controller.core.PadState
import app.swis.controller.data.MacroStore
import app.swis.controller.data.Prefs
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

/** Launches the real screens with the real layouts, theme and stored data. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScreensTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun reset() {
        ControllerHub.stopMacro()
        ControllerHub.setManual(PadState.NEUTRAL)
        ControllerHub.setStatus(Link.STOPPED)
        ControllerHub.clearLog()
    }

    @After
    fun stopMacros() {
        ControllerHub.stopMacro()
        if (ControllerHub.isRecording) ControllerHub.stopRecording("x")
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun waitUntil(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 3000
        while (!condition()) {
            assertTrue("timed out waiting for $what", System.currentTimeMillis() < deadline)
            Thread.sleep(5)
            idle()
        }
    }

    @Test
    fun mainScreenShowsStatusLogAndValidatesTheAddress() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val status = activity.findViewById<TextView>(R.id.status)
        assertEquals("Controller is off", status.text.toString())

        ControllerHub.setStatus(Link.READY, "Connected as player 1", "Nintendo Switch 2")
        ControllerHub.log("hello log")
        idle()
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(200))
        assertEquals("Connected", status.text.toString())
        assertEquals("Nintendo Switch 2 · Connected as player 1", activity.findViewById<TextView>(R.id.status_detail).text.toString())
        assertTrue(activity.findViewById<TextView>(R.id.log).text.contains("hello log"))

        val address = activity.findViewById<EditText>(R.id.address)
        address.setText("not an address")
        activity.findViewById<View>(R.id.save_address).performClick()
        assertNotNull(address.error)
        assertNull(Prefs.bluetoothAddress(activity))

        address.setText("7c:bb:8a:12:34:56")
        activity.findViewById<View>(R.id.save_address).performClick()
        assertEquals(6, Prefs.bluetoothAddress(activity)!!.size)
    }

    @Test
    fun macroScreenEditsValidatesAndPlays() {
        val activity = Robolectric.buildActivity(MacroActivity::class.java).setup().get()
        val editor = activity.findViewById<EditText>(R.id.editor)
        val info = activity.findViewById<TextView>(R.id.info)
        assertTrue(editor.text.contains("OoT: infinite jump slash"))
        assertTrue(info.text.toString(), info.text.startsWith("6 steps per loop · 500 ms"))

        editor.setText("name = broken\n50 JUMP\n")
        assertEquals("Line 2: \"JUMP\" is not a button or stick", info.text.toString())
        activity.findViewById<View>(R.id.play).performClick()
        assertNull(ControllerHub.playingMacroId)                      // a broken macro never plays

        editor.setText("name = edited\nloops = 0\n40 A\n40 -\n")
        activity.findViewById<View>(R.id.play).performClick()
        assertEquals(MacroLibrary.INFINITE_JUMP_SLASH_ID, ControllerHub.playingMacroId)
        waitUntil("the macro to press A") { ControllerHub.output.isPressed(Button.A) }
        activity.findViewById<View>(R.id.play).performClick()         // now "Stop"
        waitUntil("the macro to stop") { ControllerHub.playingMacroId == null }
        assertEquals(PadState.NEUTRAL, ControllerHub.output)

        // Playing saved the edit; reset brings the shipped macro back.
        val store = MacroStore(activity)
        assertTrue(store.get(MacroLibrary.INFINITE_JUMP_SLASH_ID)!!.isModified)
        store.remove(MacroLibrary.INFINITE_JUMP_SLASH_ID)
        assertFalse(store.get(MacroLibrary.INFINITE_JUMP_SLASH_ID)!!.isModified)
    }

    private fun layOut(pad: GamepadView, w: Int = 3120, h: Int = 1440) {
        pad.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        pad.layout(0, 0, w, h)
    }

    private class Finger(val id: Int, var x: Float, var y: Float)

    /** Sends one multi-touch event with every finger currently on the glass. */
    private fun touch(pad: GamepadView, action: Int, fingers: List<Finger>, index: Int = 0) {
        val props = Array(fingers.size) { MotionEvent.PointerProperties().apply { id = fingers[it].id } }
        val coords = Array(fingers.size) { MotionEvent.PointerCoords().apply { x = fingers[it].x; y = fingers[it].y; pressure = 1f; size = 1f } }
        val masked = if (action == MotionEvent.ACTION_POINTER_DOWN || action == MotionEvent.ACTION_POINTER_UP) {
            action or (index shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
        } else action
        val now = SystemClock.uptimeMillis()
        val event = MotionEvent.obtain(now, now, masked, fingers.size, props, coords, 0, 0, 1f, 1f, 0, 0, 0, 0)
        pad.dispatchTouchEvent(event)
        event.recycle()
    }

    @Test
    fun gamepadTouchesBecomeControllerState() {
        val w = 3120f
        val h = 1440f
        val pad = GamepadView(context)
        layOut(pad)

        // One finger on A.
        val a = Finger(0, 0.915f * w, 0.47f * h)
        touch(pad, MotionEvent.ACTION_DOWN, listOf(a))
        assertEquals(PadState.of(Button.A), ControllerHub.output)

        // A second finger on ZL while A stays down.
        val zl = Finger(1, 0.085f * w, 0.10f * h)
        touch(pad, MotionEvent.ACTION_POINTER_DOWN, listOf(a, zl), index = 1)
        assertEquals(PadState.of(Button.A, Button.ZL), ControllerHub.output)

        // A third finger pushes the left stick fully up.
        val stick = Finger(2, 0.105f * w, 0.50f * h)
        touch(pad, MotionEvent.ACTION_POINTER_DOWN, listOf(a, zl, stick), index = 2)
        stick.y -= 0.21f * h
        touch(pad, MotionEvent.ACTION_MOVE, listOf(a, zl, stick))
        assertEquals(PadState.of(Button.A, Button.ZL).copy(lx = 0f, ly = 1f), ControllerHub.output)

        // The first finger slides from A to B without lifting.
        a.x = 0.845f * w
        a.y = 0.63f * h
        touch(pad, MotionEvent.ACTION_MOVE, listOf(a, zl, stick))
        assertEquals(PadState.of(Button.B, Button.ZL).copy(ly = 1f), ControllerHub.output)

        // Lifting fingers one by one releases exactly what they held.
        touch(pad, MotionEvent.ACTION_POINTER_UP, listOf(a, zl, stick), index = 2)
        assertEquals(PadState.of(Button.B, Button.ZL), ControllerHub.output)
        touch(pad, MotionEvent.ACTION_POINTER_UP, listOf(a, zl), index = 0)
        assertEquals(PadState.of(Button.ZL), ControllerHub.output)
        touch(pad, MotionEvent.ACTION_UP, listOf(zl))
        assertEquals(PadState.NEUTRAL, ControllerHub.output)
    }

    @Test
    fun everyButtonHasItsOwnSpotOnCommonScreens() {
        // (x, y) fractions of the centre of each button, as laid out in GamepadView.
        val spots = mapOf(
            Button.ZL to (0.085f to 0.10f), Button.L to (0.225f to 0.10f), Button.R to (0.775f to 0.10f), Button.ZR to (0.915f to 0.10f),
            Button.MINUS to (0.365f to 0.10f), Button.CAPTURE to (0.435f to 0.10f), Button.HOME to (0.565f to 0.10f), Button.PLUS to (0.635f to 0.10f),
            Button.L_STICK to (0.405f to 0.33f), Button.R_STICK to (0.595f to 0.33f),
            Button.X to (0.845f to 0.31f), Button.Y to (0.775f to 0.47f), Button.A to (0.915f to 0.47f), Button.B to (0.845f to 0.63f),
            Button.DPAD_UP to (0.300f to 0.60f), Button.DPAD_LEFT to (0.232f to 0.75f), Button.DPAD_RIGHT to (0.368f to 0.75f), Button.DPAD_DOWN to (0.300f to 0.90f),
        )
        assertEquals(Button.entries.toSet(), spots.keys)
        for ((w, h) in listOf(3120 to 1440, 2400 to 1080, 1920 to 1080, 2340 to 1080, 1280 to 720)) {
            val pad = GamepadView(context)
            layOut(pad, w, h)
            for ((button, spot) in spots) {
                val finger = Finger(0, spot.first * w, spot.second * h)
                touch(pad, MotionEvent.ACTION_DOWN, listOf(finger))
                assertEquals("$button on ${w}x$h", PadState.of(button), ControllerHub.output)
                touch(pad, MotionEvent.ACTION_UP, listOf(finger))
                assertEquals(PadState.NEUTRAL, ControllerHub.output)
            }
        }
    }

    @Test
    fun rightStickAndCancel() {
        val w = 3120f
        val h = 1440f
        val pad = GamepadView(context)
        layOut(pad)
        val finger = Finger(0, 0.70f * w, 0.80f * h)
        touch(pad, MotionEvent.ACTION_DOWN, listOf(finger))
        assertEquals(PadState.NEUTRAL, ControllerHub.output)      // inside the dead zone
        finger.x += 0.17f * h                                     // far right
        touch(pad, MotionEvent.ACTION_MOVE, listOf(finger))
        assertEquals(PadState(rx = 1f, ry = 0f), ControllerHub.output)
        touch(pad, MotionEvent.ACTION_CANCEL, listOf(finger))
        assertEquals(PadState.NEUTRAL, ControllerHub.output)
    }

    @Test
    fun gamepadScreenPlaysRecordsAndReleasesOnPause() {
        val controller = Robolectric.buildActivity(GamepadActivity::class.java).setup()
        val activity = controller.get()
        val pad = activity.window.decorView.findViewById<View>(android.R.id.content).let { (it as android.view.ViewGroup).getChildAt(0) as GamepadView }
        layOut(pad)
        assertEquals("OoT: infinite jump slash", pad.macroName)

        // Play button starts the jump slash macro; tapping it again stops it.
        activity.onMacroToggle()
        assertEquals(MacroLibrary.INFINITE_JUMP_SLASH_ID, ControllerHub.playingMacroId)
        waitUntil("ZL + forward") { ControllerHub.output.isPressed(Button.ZL) && ControllerHub.output.ly == 1f }
        activity.onMacroToggle()
        waitUntil("the macro to stop") { ControllerHub.playingMacroId == null }
        assertEquals(PadState.NEUTRAL, ControllerHub.output)

        // Record two presses and get them back as a saved macro on the play button.
        activity.onRecordToggle()
        assertTrue(ControllerHub.isRecording)
        val finger = Finger(0, 0.915f * 3120, 0.47f * 1440)
        touch(pad, MotionEvent.ACTION_DOWN, listOf(finger))
        Thread.sleep(60)
        touch(pad, MotionEvent.ACTION_UP, listOf(finger))
        Thread.sleep(40)
        touch(pad, MotionEvent.ACTION_DOWN, listOf(finger))
        Thread.sleep(60)
        touch(pad, MotionEvent.ACTION_UP, listOf(finger))
        activity.onRecordToggle()
        assertFalse(ControllerHub.isRecording)
        val saved = MacroStore(activity).get(Prefs.selectedMacro(activity)!!)!!
        assertFalse(saved.isBuiltIn)
        val macro = MacroText.parse(saved.text)
        assertEquals(listOf(PadState.of(Button.A), PadState.NEUTRAL, PadState.of(Button.A)), macro.body.map { it.state })
        assertTrue(macro.body.all { it.durationMs in 30..200 })

        // Leaving the screen with a finger down must not leave the button held on the console.
        touch(pad, MotionEvent.ACTION_DOWN, listOf(finger))
        assertEquals(PadState.of(Button.A), ControllerHub.output)
        controller.pause()
        assertEquals(PadState.NEUTRAL, ControllerHub.output)
    }

    private fun startService(): String {
        val service = Robolectric.buildService(SwitchHidService::class.java, Intent(SwitchHidService.ACTION_START)).create()
        service.startCommand(0, 1)
        val status = ControllerHub.status
        service.destroy()
        assertEquals(Link.ERROR, status.link)
        return status.detail
    }

    @Test
    fun serviceExplainsEachReasonItCannotStart() {
        val app = shadowOf(context as android.app.Application)
        val adapter = shadowOf(BluetoothAdapter.getDefaultAdapter())

        app.denyPermissions(android.Manifest.permission.BLUETOOTH_CONNECT)
        assertEquals("Bluetooth permission was not granted.", startService())

        app.grantPermissions(android.Manifest.permission.BLUETOOTH_CONNECT)
        adapter.setEnabled(false)
        assertEquals("Bluetooth is turned off. Turn it on and start again.", startService())

        // Bluetooth on: the test environment hands out a HID Device proxy with no Bluetooth
        // stack behind it, so registering the controller profile is refused.
        adapter.setEnabled(true)
        assertEquals(
            "Android refused to register the controller profile. Close other \"phone as keyboard/gamepad\" apps and try again.",
            startService(),
        )
    }

    @Test
    fun rendersTheGamepad() {
        ControllerHub.setStatus(Link.READY, "Connected as player 1", "Nintendo Switch 2")
        val pad = GamepadView(context)
        pad.macroName = "OoT: infinite jump slash"
        layOut(pad)
        // Hold A by hand while a macro holds ZL + forward, to see all three colours.
        val finger = Finger(0, 0.915f * 3120, 0.47f * 1440)
        touch(pad, MotionEvent.ACTION_DOWN, listOf(finger))
        ControllerHub.playMacro("render", MacroText.parse("loops = 0\n5000 ZL LS(0,1)\n"))
        waitUntil("macro state") { ControllerHub.output.isPressed(Button.ZL) }

        val bitmap = Bitmap.createBitmap(3120, 1440, Bitmap.Config.ARGB_8888)
        pad.draw(Canvas(bitmap))
        val out = File("build/gamepad-render.png")
        FileOutputStream(out).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue(out.length() > 10_000)
        touch(pad, MotionEvent.ACTION_UP, listOf(finger))
    }
}
