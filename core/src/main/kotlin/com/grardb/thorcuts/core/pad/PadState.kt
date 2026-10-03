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

    /** Only the Thor's own pad. An external controller is not ours to renumber. */
    fun thorPads(all: List<PadDevice>): List<PadDevice> =
        all.filter { it.vendorId == AYN_VENDOR && it.productId in AYN_PRODUCTS }

    fun assess(all: List<PadDevice>): PadAssessment {
        val pads = thorPads(all).sortedBy { it.controllerNumber }
        val numbers = pads.map { it.controllerNumber }
        return when {
            pads.isEmpty() ->
                PadAssessment(PadAction.REPORT_ONLY, "no Thor pad is registered")

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
