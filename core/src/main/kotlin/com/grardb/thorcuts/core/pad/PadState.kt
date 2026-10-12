package com.grardb.thorcuts.core.pad

/**
 * One gamepad as Android currently reports it.
 *
 * [controllerNumber] is what emulators key their saved mappings on: Dolphin stores
 * "Android/<controllerNumber>/<name>" verbatim (measured). Android hands the number
 * out lowest-free when a pad appears, and frees it when the pad goes.
 */
data class PadDevice(
    val controllerNumber: Int,
    val vendorId: Int,
    val productId: Int,
    val name: String,
    /**
     * Whether Android reports this device as a gamepad or joystick.
     *
     * Load-bearing, not descriptive. AYN ships an "ODIN Station Virtual Mouse"
     * carrying the pad's vendor AND product verbatim (measured: both are
     * 0x2020/0x0111), so ids alone match two devices and the numbering reads as
     * [0, 1] — an arrangement [PadState.assess] rightly refuses to act on, which
     * silently disabled the takeover entirely. The helper has always matched the
     * pad by capability for this exact reason; this is the same test on this side
     * of the process boundary.
     *
     * Computed by the platform adapter from InputDevice.getSources() rather than
     * named here, so :core stays free of framework constants.
     */
    val isGamepad: Boolean,
)

/** What, if anything, the controller numbering needs. */
enum class PadAction {
    /** A twin already holds 1. Leave everything alone. */
    NONE,

    /** AYN's pad holds 1 and no twin exists, so a takeover would succeed. */
    TAKE_OVER,

    /** The user's pad is renumbered with no twin to justify it — this breaks Dolphin. */
    RESTORE,

    /** Something we do not model. Say so; never act on a guess. */
    REPORT_ONLY,
}

data class PadAssessment(val action: PadAction, val why: String)

/**
 * The numbering decision, as a pure function so it is testable without a device.
 *
 * It describes what the NUMBERING needs; it does not decide intent. The caller
 * supplies that: the helper launch path acts on [PadAction.TAKE_OVER], while the
 * self-heal path acts only on [PadAction.RESTORE] — otherwise opening the app
 * would take the controller over with no helper to use it.
 */
object PadState {

    /** AYN's vendor id, measured on the Thor. */
    const val AYN_VENDOR = 0x2020

    /**
     * The two layouts AYN's controller-style switch produces. 0x0111 is the Odin
     * layout and 0x0112 the Xbox layout; both were enumerated on the device, and
     * both are the same physical pad, so both are ours to take over.
     */
    val AYN_PRODUCTS = setOf(0x0111, 0x0112)

    /**
     * Only the Thor's own pad. An external controller is not ours to renumber,
     * and neither is AYN's virtual mouse — which carries the pad's vendor and
     * product verbatim, so [PadDevice.isGamepad] is what tells them apart.
     */
    fun thorPads(all: List<PadDevice>): List<PadDevice> =
        all.filter { it.isGamepad && it.vendorId == AYN_VENDOR && it.productId in AYN_PRODUCTS }

    fun assess(all: List<PadDevice>): PadAssessment {
        val pads = thorPads(all).sortedBy { it.controllerNumber }
        val numbers = pads.map { it.controllerNumber }
        return when {
            // RESTORE, not REPORT_ONLY. This is precisely the state a crash or a
            // SIGKILL midway through the helper's takeover leaves behind: the
            // trigger key is still 1, so AYN has deregistered its pad and Android
            // reports no gamepad at all. It is the worst state this project can
            // create, and teardown layer 3 only covers the SIGKILL case if the
            // decision here says RESTORE -- reporting it would leave the owner
            // with a dead controller and nothing but AYN's Quick Settings tile or
            // a reboot to get out of it.
            //
            // It cannot race our own takeover: the self-heal runs in
            // HelperSupervisor.start() BEFORE the helper is launched, so the
            // window in which the pad is legitimately absent has not opened yet.
            // And if the pad is absent for some other reason, cycling the key is
            // harmless -- the cycle ends with the key at 0, which is the state in
            // which the pad exists.
            pads.isEmpty() ->
                PadAssessment(PadAction.RESTORE, "no Thor pad is registered at all")

            pads.size == 1 && numbers[0] == 1 ->
                PadAssessment(PadAction.TAKE_OVER, "AYN's pad holds 1 and no twin exists")

            // Bounded deliberately. Controller number 0 means "not participating
            // in the numbering scheme" — it is what every non-gamepad on this
            // device reports — so a 0 here is an anomaly, not a renumbering, and
            // cycling the user's controller over it would be a guess.
            pads.size == 1 && numbers[0] > 1 ->
                PadAssessment(PadAction.RESTORE, "the pad is stranded at ${numbers[0]}")

            pads.size == 2 && numbers == listOf(1, 2) ->
                PadAssessment(PadAction.NONE, "a twin already holds 1")

            else ->
                PadAssessment(PadAction.REPORT_ONLY, "unexpected numbering: $numbers")
        }
    }
}
