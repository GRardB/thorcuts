package com.grardb.thorcuts.platform.pad

import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import com.grardb.thorcuts.core.pad.PadAction
import com.grardb.thorcuts.core.pad.PadAssessment
import com.grardb.thorcuts.core.pad.PadDevice
import com.grardb.thorcuts.core.pad.PadState
import com.grardb.thorcuts.platform.privilege.PserviceGateway

/**
 * What Android currently thinks about controller numbering, and the one write
 * that changes it.
 *
 * Reading is a PUBLIC API — no shell, no root, no parsing — which is why the
 * decision lives on this side and the dangerous sequence lives in the helper.
 */
object ControllerNumbers {

    /**
     * The single setting AYN's "Controller style" tile writes, and the only one
     * that has any effect. MEASURED 2026-10-02: writing 1 deregisters AYN's pad
     * entirely (Android then reports no gamepad at all, so controller number 1
     * becomes free); writing 0 brings it back.
     *
     * `persist.sys.gamepad.type` and `temp_abxy_layout_mode` change alongside it
     * when the tile is used, and were each tested alone: NEITHER has any effect.
     * Do not write them.
     */
    const val TRIGGER_KEY = "no_create_gamepad_button_layout"

    fun snapshot(): List<PadDevice> =
        // IntArray (what getDeviceIds() returns) has no mapNotNull of its own in
        // the Kotlin stdlib -- only Array<T>, Iterable, Map, Sequence and
        // CharSequence get one. toList() first (-> List<Int>) picks up the
        // Iterable overload.
        InputDevice.getDeviceIds().toList().mapNotNull { InputDevice.getDevice(it) }.map {
            PadDevice(
                controllerNumber = it.controllerNumber,
                vendorId = it.vendorId,
                productId = it.productId,
                name = it.name,
            )
        }

    /**
     * The numbering as Android reports it RIGHT NOW.
     *
     * Only correct when no trigger write of ours is outstanding — the self-heal
     * at the top of HelperSupervisor.start(), which runs before this process has
     * written anything. Everywhere else use [assessAfterSettle]; see its comment
     * for what sampling too early reads instead.
     */
    fun assess(): PadAssessment = PadState.assess(snapshot())

    /**
     * [assess], but only once Android has re-enumerated the pad — or the settle
     * window has expired.
     *
     * A trigger write (`settings put system no_create_gamepad_button_layout …`)
     * returns as soon as the value is STORED. AYN's daemon then destroys and
     * recreates the gamepad, and Android then re-enumerates it. For seconds
     * afterwards [snapshot] reports no gamepad at all, and [PadState.assess] maps
     * an empty list to [PadAction.RESTORE] — correctly, because that is also the
     * state a crash midway through a takeover leaves behind. Sampling immediately
     * after a write therefore reads a transient absence as a durable fault:
     *
     *  - the teardown backstop fires a gratuitous restore after EVERY normal
     *    takeover, blinking the user's controller out again for ~3 s and, because
     *    DiagnosticsViewModel keeps a single message slot, overwriting the
     *    helper's "RESTORE FAILED …" warning with "pad left renumbered";
     *  - the launch decision reads RESTORE instead of TAKE_OVER right after the
     *    self-heal's own restore, so the run that needed healing launches the
     *    helper WITHOUT the takeover argument and the feature silently does not
     *    happen.
     *
     * So poll until a Thor pad is back, then decide. DO NOT DELETE THIS AS
     * REDUNDANT: with no write of ours outstanding the very first snapshot
     * already has the pad and this returns on its first turn, which is precisely
     * why its absence is invisible in the common case and wrong in the one that
     * matters.
     *
     * Blocking for up to [SETTLE_WAIT_MS]; call on
     * [com.grardb.thorcuts.platform.privilege.Privilege.dispatcher].
     */
    fun assessAfterSettle(): PadAssessment {
        val deadline = SystemClock.elapsedRealtime() + SETTLE_WAIT_MS
        while (true) {
            val devices = snapshot()
            if (PadState.thorPads(devices).isNotEmpty()) return PadState.assess(devices)

            if (SystemClock.elapsedRealtime() >= deadline) {
                // Still nothing after the whole window, so this is no longer the
                // transient absence the wait absorbs — it is the genuine fault the
                // self-heal exists to repair. Let the pure decision speak (it says
                // RESTORE for an empty list) and name the window in the reason, so
                // the log tells a real fault apart from a sample taken too early.
                Log.w(TAG, "PAD_SETTLE no Thor pad after ${SETTLE_WAIT_MS}ms")
                val timedOut = PadState.assess(devices)
                return timedOut.copy(why = "${timedOut.why} after a ${SETTLE_WAIT_MS}ms settle wait")
            }

            try {
                Thread.sleep(SETTLE_POLL_MS)
            } catch (_: InterruptedException) {
                // Cut short, so we never learned whether the pad came back. Acting
                // on half a wait would be exactly the guess REPORT_ONLY exists to
                // refuse: neither caller acts on it, so both degrade safely.
                Thread.currentThread().interrupt()
                return PadAssessment(PadAction.REPORT_ONLY, "the settle wait was interrupted")
            }
        }
    }

    /**
     * Cycle the trigger key so AYN's pad is destroyed and recreated, reclaiming
     * the lowest free controller number — which is 1 once our twin is gone.
     *
     * Returns false when pservice gave no reply, i.e. the cycle never ran. The
     * caller must say so: a silent failure here leaves the user with a pad at the
     * wrong number, or none at all, and nothing to connect it to.
     *
     * Blocking; call on [com.grardb.thorcuts.platform.privilege.Privilege.dispatcher].
     * Uses execScript rather than exec because the pause between the two writes
     * matters and pservice does not reliably interpret inline shell operators —
     * execScript writes a file and runs `sh <file>`, where they work normally.
     */
    fun restoreNumbering(gateway: PserviceGateway): Boolean {
        val out = gateway.execScript(
            listOf(
                "settings put system $TRIGGER_KEY 1",
                // 3 s, matching native/src/takeover.c's TC_RESTORE_GAP_MS and the
                // only measurement anyone has taken of this cycle
                // (scratch/m2-recon/run-single-key.sh STEP 5). It was 1 s here and
                // 300 ms there; both were guesses. If the 0 write arrives before
                // AYN's daemon has destroyed the pad, it can coalesce the pair and
                // never rebuild, so the restore silently does nothing.
                "sleep 3",
                "settings put system $TRIGGER_KEY 0",
            )
        )
        Log.i(TAG, "PAD_RESTORE requested reply=${out?.trim()}")
        return out != null
    }

    /**
     * How long [assessAfterSettle] waits for a Thor pad to come back.
     *
     * 6 s. The only measurements anyone has of this cycle are the recon's
     * (scratch/m2-recon/run-single-key.sh): it waited 3 s after the deregister
     * write and 5 s after the re-register write, and both were enough.
     * native/src/takeover.c's header records the conclusion — 3 s is
     * "comfortable for the disappearance and at the edge for the reappearance".
     * Reappearance is what this waits for, so the bound has to be at least the
     * 5 s that was actually seen to work; 6 s is that plus a second of headroom,
     * because the 5 s was chosen conservatively and never measured as a minimum.
     *
     * Erring long costs nothing on the normal path — the loop returns as soon as
     * the pad is there, and only a real fault pays the full window. Erring short
     * brings back the false RESTORE this whole wait exists to prevent.
     */
    const val SETTLE_WAIT_MS = 6_000L

    /** ~60 turns over the full window, each one a cheap public-API read. */
    const val SETTLE_POLL_MS = 100L

    private const val TAG = "ThorcutsPad"
}
