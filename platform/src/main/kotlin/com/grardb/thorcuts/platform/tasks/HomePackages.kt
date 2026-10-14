package com.grardb.thorcuts.platform.tasks

import android.content.Intent
import android.content.pm.PackageManager
import com.grardb.thorcuts.core.safety.CommandSafety

/**
 * The packages that may be a screen's launcher: every activity answering MAIN + HOME or
 * MAIN + SECONDARY_HOME (two packages register the latter on this device, M1).
 *
 * `close` refuses them by name, because a launcher relaunched as an ordinary task passes the
 * tool's app predicate (Cocoon, measured as type=standard on the bottom screen, 2026-10-13)
 * and `removeTask` would remove it. Public API, no privilege; the manifest's `<queries>`
 * make the two intents visible under package visibility (API 30+). Sorted, so the command
 * built from it is deterministic; filtered, so nothing that is not a package name reaches a
 * root shell even here — PackageManager is trusted, the rule is not conditional.
 *
 * A match whose filter priority is below zero is NOT a launcher: that is AOSP's convention
 * for a fallback home — `com.android.settings.FallbackHome` answers MAIN + HOME at -1000 as
 * the stand-in for the first seconds of boot, and MEASURED on the Thor (2026-10-14) it made
 * `close` refuse to close Settings. A real launcher declares HOME at 0 or above.
 *
 * `queryIntentActivities(Intent, Int)` is deprecated from API 33 in favour of the
 * `ResolveInfoFlags` form, which does not exist below 33; minSdk is 30, so the old form is
 * the one that runs everywhere, suppressed rather than branched.
 */
object HomePackages {

    @Suppress("DEPRECATION")
    fun resolve(pm: PackageManager): Set<String> =
        listOf(Intent.CATEGORY_HOME, Intent.CATEGORY_SECONDARY_HOME)
            .flatMap { category ->
                pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(category), 0)
            }
            .filter { it.priority >= 0 }
            .map { it.activityInfo.packageName }
            .filter { CommandSafety.isPackage(it) }
            .toSortedSet()
}
