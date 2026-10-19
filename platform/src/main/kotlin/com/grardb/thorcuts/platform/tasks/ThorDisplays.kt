package com.grardb.thorcuts.platform.tasks

/**
 * The Thor's two display ids, MEASURED 2026-10-01 and confirmed visually: an
 * activity launched with `--display 4` appeared on the bottom screen.
 *
 * Full detail: docs/superpowers/findings/2026-09-30-m1-device-recon.md. If that
 * file ever disagrees with these numbers, change these — never the findings.
 */
object ThorDisplays {
    /** The top screen: "Built-in Screen", 1080x1920, port 131. */
    const val TOP = 0

    /** The bottom screen: "Screen-2", 1080x1240, port 132. NOT 1 — ids 1-3 do
     *  not exist on this device. */
    const val BOTTOM = 4

    /**
     * The stable identities behind those ids, for M2.
     *
     * Display ids here are DYNAMIC: 1-3 are absent, the bottom screen landed on
     * 4, and the gamepad shows the same renumbering (input20 on event9). So the
     * integers above are a measured snapshot, not a guarantee. M2 should resolve
     * the displays by uniqueId (or physical port) through DisplayManager and
     * treat these as a fallback only.
     */
    const val TOP_UNIQUE_ID = "local:4630946441858561667"
    const val BOTTOM_UNIQUE_ID = "local:4630946482288158084"

    /**
     * The bottom screen as `screencap -d` and `screenrecord --display-id` name it: the
     * SurfaceFlinger id behind [BOTTOM_UNIQUE_ID]. `-d 0` / `-d 4` give an EMPTY file; this id
     * gives the bottom screen's content (M7 capture recon, 2026-10-19 — the M1 "black frame"
     * was a sleeping device; see `findings/2026-10-19-m7-capture-recon.md`).
     */
    val BOTTOM_PHYSICAL_ID: String = BOTTOM_UNIQUE_ID.removePrefix("local:")
}
