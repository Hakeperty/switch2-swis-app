package app.swis.controller.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class MacroTest {

    private fun errorLine(text: String): Int = try {
        MacroText.parse(text)
        fail("expected a syntax error")
        -1
    } catch (e: MacroSyntaxException) {
        e.line
    }

    @Test
    fun parsesSettingsIntroAndLoop() {
        val m = MacroText.parse(
            """
            name = Demo macro
            loops = 0
            scale = 1.5
            # a comment line
            400 ZL LS(0,1)      # hold
            loop:
            70  ZL + A LS( 0 , 1 )
            3f  -
            0.1s zr rs_left
            """.trimIndent()
        )
        assertEquals("Demo macro", m.name)
        assertTrue(m.isInfinite)
        assertEquals(1.5f, m.scale)
        assertEquals(listOf(MacroStep(400, PadState.of(Button.ZL).copy(ly = 1f), "hold")), m.intro)
        assertEquals(3, m.body.size)
        assertEquals(PadState.of(Button.ZL, Button.A).copy(ly = 1f), m.body[0].state)
        assertEquals(MacroStep(50, PadState.NEUTRAL), m.body[1])
        assertEquals(MacroStep(100, PadState.of(Button.ZR).copy(rx = -1f)), m.body[2])
        assertEquals(Math.round(70 * 1.5f) + Math.round(50 * 1.5f) + Math.round(100 * 1.5f), m.cycleMs)
    }

    @Test
    fun withoutLoopMarkerEverythingRepeats() {
        val m = MacroText.parse("50 A\n50 -\n")
        assertTrue(m.intro.isEmpty())
        assertEquals(2, m.body.size)
        assertEquals(1, m.loops)
    }

    @Test
    fun buttonAliases() {
        val m = MacroText.parse("100 UP DOWN LEFT RIGHT L3 R3 START SELECT")
        assertEquals(
            PadState.of(
                Button.DPAD_UP, Button.DPAD_DOWN, Button.DPAD_LEFT, Button.DPAD_RIGHT,
                Button.L_STICK, Button.R_STICK, Button.PLUS, Button.MINUS,
            ),
            m.body[0].state,
        )
    }

    @Test
    fun reportsTheLineOfAnError() {
        assertEquals(2, errorLine("50 A\n50 JUMP\n"))
        assertEquals(1, errorLine("A 50\n"))
        assertEquals(3, errorLine("name = x\n50 A\n10 B\n"))          // shorter than the minimum
        assertEquals(1, errorLine("50 LS(2,0)\n"))
        assertEquals(1, errorLine("loops = -1\n50 A\n"))
        assertEquals(1, errorLine("speed = 2\n50 A\n"))
        assertEquals(3, errorLine("loop:\n50 A\nloop:\n"))
        assertTrue(errorLine("name = empty\n# nothing\n") >= 1)
        assertTrue(errorLine("50 A\nloop:\n") >= 1)                    // nothing to repeat
    }

    @Test
    fun formatRoundTrips() {
        for (builtIn in MacroLibrary.builtIns) {
            val original = MacroText.parse(builtIn.defaultText)
            val again = MacroText.parse(MacroText.format(original))
            assertEquals(builtIn.id, original, again)
        }
        val odd = Macro(
            "Odd", loops = 3, scale = 0.75f,
            intro = listOf(MacroStep(120, PadState.NEUTRAL)),
            body = listOf(MacroStep(33, PadState.of(Button.HOME).copy(lx = -0.5f, ly = 0.25f, rx = 1f, ry = -1f), "note")),
        )
        assertEquals(odd, MacroText.parse(MacroText.format(odd)))
    }

    @Test
    fun infiniteJumpSlashMacroShape() {
        val m = MacroText.parse(MacroLibrary.builtIn(MacroLibrary.INFINITE_JUMP_SLASH_ID)!!.defaultText)
        assertTrue(m.isInfinite)
        assertEquals(1f, m.scale)
        // Lock-on and forward are held through every step, intro included.
        for (step in m.intro + m.body) {
            assertTrue(step.state.isPressed(Button.ZL))
            assertEquals(1f, step.state.ly)
            assertEquals(0f, step.state.lx)
        }
        // The loop presses A, then R, then ZR, each followed by a release.
        val pressed = m.body.map { it.state.pressedButtons().toSet() - Button.ZL }
        assertEquals(
            listOf(setOf(Button.A), emptySet(), setOf(Button.R), emptySet(), setOf(Button.ZR), emptySet()),
            pressed,
        )
        assertTrue(m.body.all { it.durationMs >= 34 }) // at least two 60 fps frames each
    }

    @Test
    fun recorderTurnsStateChangesIntoSteps() {
        var now = 0L
        val r = MacroRecorder { now }
        r.onState(PadState.NEUTRAL)                 // idle before the first press is ignored
        now += 500_000_000
        r.onState(PadState.of(Button.A))
        now += 100_000_000
        r.onState(PadState.of(Button.A).copy(lx = 0.98f))
        now += 5_000_000
        r.onState(PadState.of(Button.A).copy(lx = 1.0f))   // same after snapping: no new step
        now += 45_000_000
        r.onState(PadState.NEUTRAL)
        now += 300_000_000
        r.onState(PadState.of(Button.B))
        now += 80_000_000
        r.onState(PadState.NEUTRAL)
        now += 900_000_000                          // trailing idle is dropped
        val m = r.finish("rec")!!
        assertEquals(
            listOf(
                MacroStep(100, PadState.of(Button.A)),
                MacroStep(50, PadState.of(Button.A).copy(lx = 1f)),
                MacroStep(300, PadState.NEUTRAL),
                MacroStep(80, PadState.of(Button.B)),
            ),
            m.body,
        )
        assertEquals(m, MacroText.parse(MacroText.format(m)))
    }

    @Test
    fun recorderWithNoInputGivesNothing() {
        val r = MacroRecorder { 0L }
        r.onState(PadState.NEUTRAL)
        assertNull(r.finish("rec"))
    }

    @Test
    fun mergeKeepsManualInputAlive() {
        val macro = PadState.of(Button.ZL).copy(ly = 1f)
        assertEquals(PadState.of(Button.B), PadState.merge(PadState.of(Button.B), null))
        assertEquals(PadState.of(Button.ZL, Button.B).copy(ly = 1f), PadState.merge(PadState.of(Button.B), macro))
        // a deflected thumb takes the stick over, a resting one does not
        assertEquals(PadState.of(Button.ZL).copy(lx = -1f, ly = 0f), PadState.merge(PadState(lx = -1f), macro))
        assertEquals(macro, PadState.merge(PadState(lx = 0.1f), macro))
    }

    @Test
    fun playerHoldsEachStepForItsDuration() {
        val macro = MacroText.parse("loops = 3\n60 A\nloop:\n40 B\n30 -\n")
        val events = ArrayList<Pair<Long, PadState?>>()
        val done = CountDownLatch(1)
        var completed = false
        val start = System.nanoTime()
        val player = MacroPlayer(
            macro,
            output = { synchronized(events) { events.add((System.nanoTime() - start) / 1000 to it) } },
            onFinished = { completed = it; done.countDown() },
        )
        player.start()
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertTrue(completed)
        assertEquals(3, player.cyclesDone)

        val states = events.map { it.second }
        val a = PadState.of(Button.A)
        val b = PadState.of(Button.B)
        assertEquals(listOf(a, b, PadState.NEUTRAL, b, PadState.NEUTRAL, b, PadState.NEUTRAL, null), states)

        // Step edges, in microseconds from the first step, must not drift.
        val expected = listOf(0L, 60, 100, 130, 170, 200, 240, 270).map { it * 1000 }
        val first = events[0].first
        events.forEachIndexed { i, (at, _) ->
            val error = at - first - expected[i]
            assertTrue("step $i was off by $error µs", error in -500..8000)
        }
    }

    @Test
    fun playerAppliesScale() {
        val macro = MacroText.parse("scale = 2\n50 A\n")
        val done = CountDownLatch(1)
        val start = System.nanoTime()
        MacroPlayer(macro, output = {}, onFinished = { done.countDown() }).start()
        assertTrue(done.await(5, TimeUnit.SECONDS))
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        assertTrue("took $elapsedMs ms", elapsedMs in 100..160)
    }

    @Test
    fun playerStopsPromptlyAndReleasesEverything() {
        val macro = MacroText.parse("loops = 0\n10000 A\n")
        val events = ArrayList<PadState?>()
        val done = CountDownLatch(1)
        var completed = true
        val player = MacroPlayer(
            macro,
            output = { synchronized(events) { events.add(it) } },
            onFinished = { completed = it; done.countDown() },
        )
        player.start()
        Thread.sleep(50)
        val stopAt = System.nanoTime()
        player.stop()
        assertTrue(done.await(2, TimeUnit.SECONDS))
        assertTrue((System.nanoTime() - stopAt) / 1_000_000 < 200)
        assertFalse(completed)
        assertEquals(listOf(PadState.of(Button.A), null), events)
    }

    @Test
    fun hubMergesMacroAndManualAndCleansUp() {
        val seen = ArrayList<PadState>()
        ControllerHub.outputListener = { synchronized(seen) { seen.add(ControllerHub.output) } }
        try {
            ControllerHub.setManual(PadState.of(Button.B))
            ControllerHub.playMacro("t", MacroText.parse("loops = 2\n40 A\n40 -\n"))
            assertNotNull(ControllerHub.playingMacroId)
            val deadline = System.currentTimeMillis() + 3000
            while (ControllerHub.playingMacroId != null && System.currentTimeMillis() < deadline) Thread.sleep(5)
            assertNull(ControllerHub.playingMacroId)
            ControllerHub.setManual(PadState.NEUTRAL)
            val b = PadState.of(Button.B)
            val ab = PadState.of(Button.A, Button.B)
            assertEquals(listOf(b, ab, b, ab, b, PadState.NEUTRAL), synchronized(seen) { seen.toList() })
            assertEquals(PadState.NEUTRAL, ControllerHub.output)
        } finally {
            ControllerHub.outputListener = null
            ControllerHub.setManual(PadState.NEUTRAL)
        }
    }
}
