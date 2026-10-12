package com.grardb.thorcuts.core.pad

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PadStateTest {

    private fun ayn(number: Int, product: Int = 0x0111, name: String = "Odin Controller") =
        PadDevice(
            controllerNumber = number,
            vendorId = 0x2020,
            productId = product,
            name = name,
            isGamepad = true,
        )

    @Test
    fun `AYN's pad alone at 1 means a takeover is available`() {
        assertEquals(PadAction.TAKE_OVER, PadState.assess(listOf(ayn(1))).action)
    }

    @Test
    fun `a twin at 1 and AYN's pad at 2 means nothing to do`() {
        assertEquals(PadAction.NONE, PadState.assess(listOf(ayn(1), ayn(2))).action)
    }

    @Test
    fun `a lone pad at 2 is stranded and must be restored`() {
        assertEquals(PadAction.RESTORE, PadState.assess(listOf(ayn(2))).action)
    }

    @Test
    fun `a lone pad above 2 is also stranded`() {
        assertEquals(PadAction.RESTORE, PadState.assess(listOf(ayn(5))).action)
    }

    @Test
    fun `a lone pad at 0 is reported, not restored`() {
        // 0 means "not in the numbering scheme at all", not "renumbered".
        assertEquals(PadAction.REPORT_ONLY, PadState.assess(listOf(ayn(0))).action)
    }

    @Test
    fun `three Thor pads are reported rather than guessed at`() {
        assertEquals(PadAction.REPORT_ONLY, PadState.assess(listOf(ayn(1), ayn(2), ayn(3))).action)
    }

    @Test
    fun `no Thor pad at all is the crash state and must be restored`() {
        // This is exactly what a crash or a SIGKILL midway through the takeover
        // leaves behind: the trigger key still says 1, so Android reports no
        // gamepad at all. Teardown layer 3 claims to cover that case, and it only
        // does if this says RESTORE -- REPORT_ONLY would leave the owner with a
        // dead controller and only AYN's tile or a reboot to recover.
        assertEquals(PadAction.RESTORE, PadState.assess(emptyList()).action)
    }

    @Test
    fun `other devices present but no Thor pad is still the crash state`() {
        // Deliberately not emptyList(): a keyboard and an external controller are
        // present and correctly filtered out, so it is the absence of a THOR pad
        // that drives the decision. The filter, not an empty input, must produce
        // RESTORE -- otherwise a user with an external pad plugged in would be
        // left unhealed in precisely the state this exists to heal.
        val keyboard = PadDevice(0, 0x1234, 0x5678, "kbd", isGamepad = false)
        val external = PadDevice(1, 0x045e, 0x02ea, "Xbox One", isGamepad = true)
        assertEquals(PadAction.RESTORE, PadState.assess(listOf(keyboard, external)).action)
    }

    @Test
    fun `the Xbox layout counts as a Thor pad`() {
        assertEquals(PadAction.TAKE_OVER, PadState.assess(listOf(ayn(1, product = 0x0112))).action)
    }

    @Test
    fun `AYN's virtual mouse shares the pad's ids and must not count as a pad`() {
        // MEASURED on the device: "ODIN Station Virtual Mouse" reports
        // vendor=0x2020 product=0x0111, exactly the pad's, at ControllerNumber 0.
        // Filtering on ids alone saw [0, 1], called it unexpected, and refused to
        // take over at all -- the takeover silently never ran.
        val mouse = PadDevice(
            controllerNumber = 0,
            vendorId = 0x2020,
            productId = 0x0111,
            name = "ODIN Station Virtual Mouse",
            isGamepad = false,
        )
        assertEquals(PadAction.TAKE_OVER, PadState.assess(listOf(mouse, ayn(1))).action)
        assertEquals(1, PadState.thorPads(listOf(mouse, ayn(1))).size)
    }

    @Test
    fun `devices that are not the Thor pad are ignored entirely`() {
        val keyboard = PadDevice(0, 0x1234, 0x5678, "kbd", isGamepad = false)
        val external = PadDevice(3, 0x045e, 0x02ea, "Xbox One", isGamepad = true)
        assertEquals(PadAction.TAKE_OVER, PadState.assess(listOf(keyboard, external, ayn(1))).action)
    }

    @Test
    fun `an unexpected arrangement is reported rather than guessed at`() {
        assertEquals(PadAction.REPORT_ONLY, PadState.assess(listOf(ayn(2), ayn(3))).action)
    }

    @Test
    fun `the reason is always populated`() {
        // assertTrue, NOT Kotlin's `assert`: the latter is an intrinsic that is
        // disabled on the JVM unless -ea is passed, so a test written with it
        // would pass silently whatever the code did.
        for (devices in listOf(listOf(ayn(1)), listOf(ayn(2)), listOf(ayn(1), ayn(2)), emptyList())) {
            assertTrue(PadState.assess(devices).why.isNotBlank())
        }
    }
}
