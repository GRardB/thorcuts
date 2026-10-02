package com.grardb.thorcuts.platform.privilege

import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors

/**
 * The single thread every privileged call runs on.
 *
 * Serialising is a CORRECTNESS requirement, not tidiness: pservice returns empty
 * results for overlapping transactions, so two concurrent calls make each other
 * fail silently. Every call also blocks, so none of them may touch the main thread.
 */
object Privilege {

    val dispatcher: CoroutineDispatcher =
        Executors.newSingleThreadExecutor { r ->
            Thread(r, "thorcuts-privilege").apply { isDaemon = true }
        }.asCoroutineDispatcher()

    /**
     * An application-lifetime scope for privileged work that must happen whether
     * or not any UI is alive.
     *
     * Needed because a shortcut is not a screen. The input helper is launched by
     * the diagnostics screen but deliberately keeps running after it closes (its
     * watchdog is fed for as long as the PROCESS lives), it holds the physical
     * controller grabbed, and it swallows the combo so no game sees it. Work
     * posted on a `viewModelScope` is silently dropped once that ViewModel is
     * cleared, so a combo arriving after the Activity finished was consumed and
     * then did nothing at all. Posting it here instead makes the shortcut work
     * whenever the process is alive, which is the behaviour a shortcut is
     * supposed to have.
     *
     * Built on [dispatcher] on purpose, NOT on a scope of its own: that is what
     * keeps the serialisation guarantee above intact across both callers. A
     * UI-driven call hopping onto [dispatcher] and a combo-driven coroutine
     * launched here run on the same single thread, so they queue instead of
     * overlapping. This adds no second thread and no second dispatcher.
     *
     * [SupervisorJob] so one failed swap does not cancel the scope and kill every
     * later one, and a [CoroutineExceptionHandler] so a failure is logged rather
     * than reaching the thread's default handler (which, in a scope with no UI
     * watching, would take the process — and the helper channel with it — down
     * over one bad swap).
     *
     * Never cancelled. Its lifetime is the process's, by design.
     */
    val scope: CoroutineScope = CoroutineScope(
        SupervisorJob() +
            dispatcher +
            CoroutineExceptionHandler { _, t ->
                Log.e(TAG, "privileged work failed", t)
            }
    )

    private const val TAG = "ThorcutsPrivilege"
}
