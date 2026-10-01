package com.grardb.thorcuts.platform.privilege

import kotlinx.coroutines.CoroutineDispatcher
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
}
