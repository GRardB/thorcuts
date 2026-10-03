package com.grardb.thorcuts.platform.pad

import android.util.Log
import android.view.InputDevice
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

    fun assess(): PadAssessment = PadState.assess(snapshot())

    /**
     * Cycle the trigger key so AYN's pad is destroyed and recreated, reclaiming
     * the lowest free controller number — which is 1 once our twin is gone.
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

    private const val TAG = "ThorcutsPad"
}
