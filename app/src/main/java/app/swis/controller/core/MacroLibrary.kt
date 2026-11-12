package app.swis.controller.core

/** A macro that ships with the app. Users can edit it; [defaultText] is what "reset" restores. */
class BuiltInMacro(val id: String, val defaultText: String)

object MacroLibrary {
    const val INFINITE_JUMP_SLASH_ID = "oot_infinite_jump_slash"

    /**
     * Infinite jump slash ("Jumpslash Cancel") for the Ocarina of Time remake on Switch 2.
     *
     * Reconstructed from the published descriptions of the glitch: lock on and hold forward,
     * jump attack with a two-handed weapon (ZL + forward + A), swap the item in mid-air with
     * the quick menu (R), attack (ZR), repeat. The descriptions give the order of inputs but
     * not the frame timing or the exact input that picks the other item in the quick menu,
     * so those are marked below and are meant to be tuned on the console.
     */
    private val INFINITE_JUMP_SLASH = """
        name = OoT: infinite jump slash
        loops = 0
        scale = 1.0

        # BEFORE PRESSING PLAY
        #  - Child Link: Deku Stick in hand. Adult Link: Megaton Hammer in hand.
        #  - A second item must be reachable from the quick menu (R).
        #  - Stand on flat ground with room in front of you.
        #
        # TUNING
        #  - "scale" stretches or squeezes every step at once (1.1 = 10% slower).
        #  - 1 frame at 60 fps is about 17 ms. Change one step at a time.
        #  - While the macro runs you can still steer with the left stick.

        500  ZL LS(0,1)              # lock on and walk forward
        loop:
        70   ZL LS(0,1) A            # jump attack: Link leaves the ground
        130  ZL LS(0,1)              # rise
        70   ZL LS(0,1) R            # quick menu: swap away from the two-handed weapon
        50   ZL LS(0,1)              # if the swap needs a direction, add it to the R step
        70   ZL LS(0,1) ZR           # attack with the swapped item: cancels the fall
        110  ZL LS(0,1)              # settle before the next jump attack
    """.trimIndent()

    private val MASH_A = """
        name = Mash A
        loops = 0
        scale = 1.0

        50  A
        50  -
    """.trimIndent()

    private val PRESS_L_R = """
        name = Press L + R (join as controller)
        loops = 1
        scale = 1.0

        300  L R
        200  -
        120  A
        100  -
    """.trimIndent()

    val builtIns: List<BuiltInMacro> = listOf(
        BuiltInMacro(INFINITE_JUMP_SLASH_ID, INFINITE_JUMP_SLASH),
        BuiltInMacro("mash_a", MASH_A),
        BuiltInMacro("press_l_r", PRESS_L_R),
    )

    fun builtIn(id: String): BuiltInMacro? = builtIns.firstOrNull { it.id == id }
}
