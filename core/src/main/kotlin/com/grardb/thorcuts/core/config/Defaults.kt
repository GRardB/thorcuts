package com.grardb.thorcuts.core.config

/**
 * The first-run config: what a user has before they have edited anything. EMPTY (owner,
 * 2026-10-14; shipped by M5's first piece): a release ships no shortcut nobody asked for,
 * and onboarding (M5) explains adding the first one. Carried finding F1 holds trivially —
 * with no rule on AYN, a bare AYN tap keeps its system job, the quick-settings overlay.
 *
 * Until M5 this was one shortcut, HOLD 400 · AYN + X → swap the screens (M1's rule); that
 * value is now test material, `ConfigFixtures.SWAP_SCREENS`, and still pins the rule-table
 * fixture `m1-swap.bin` from the tests that encode it.
 */
object Defaults {
    val CONFIG = ThorcutsConfig(emptyList())
}
