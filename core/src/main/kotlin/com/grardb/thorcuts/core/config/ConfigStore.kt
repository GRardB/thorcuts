package com.grardb.thorcuts.core.config

import com.grardb.thorcuts.core.rules.CompiledTable
import com.grardb.thorcuts.core.rules.FireResolution
import com.grardb.thorcuts.core.rules.RetainedTables
import com.grardb.thorcuts.core.rules.RuleCompiler
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.Executors

/**
 * Parent spec §8's serialising actor for the config: the one owner of the current
 * [ThorcutsConfig], the generation counter, the retained tables, recompile-on-write, and
 * the Ready / ack / fire bookkeeping around the helper's table.
 *
 * One thread, in order. Everything this class owns is touched only on [dispatcher] — a
 * single daemon thread of its own by default (deliberately not the privileged thread: a
 * config write must not wait behind a `stop()` that can block for seconds). [load] and
 * [update] are `suspend` and hop there; the four helper-side calls post there and return
 * at once, so the supervisor's accept thread never blocks. Calls land in the order made.
 * [onEvent] is invoked on that thread too.
 *
 * The store never sees a socket, an Android class, or the protocol: the file is behind
 * [ConfigFile], the push behind [push], and the helper's messages arrive as four plain calls.
 */
class ConfigStore(
    private val file: ConfigFile,
    /** `HelperSupervisor::pushTable` in the app. Offers; the helper's ack is the proof. */
    private val push: (CompiledTable) -> Unit,
    /** Called ON THE STORE'S THREAD. Handlers must be quick and thread-safe. */
    private val onEvent: (Event) -> Unit,
    private val default: ThorcutsConfig = Defaults.CONFIG,
    private val dispatcher: CoroutineDispatcher = singleThread(),
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    private val _config = MutableStateFlow(ThorcutsConfig(emptyList()))

    /** §8: an immutable snapshot behind a StateFlow; the UI reads this. Empty until [load]. */
    val config: StateFlow<ThorcutsConfig> = _config.asStateFlow()

    /** One per store instance, starting at 0, consumed per attempted push — so what reaches
     *  the helper is strictly increasing for the life of the process, which is the one
     *  property the retention rule needs. Never reset, not even by [helperGone]. */
    private var generation = 0

    /** Every table pushed since the helper last reported one `installed`, plus that one
     *  (the matcher spec's retention rule). Cleared when the helper goes: its table died
     *  with its socket, so nothing retained can fire again. */
    private var tables = RetainedTables()

    /** True between [helperReady] and [helperGone]: a write pushes only while this holds. */
    private var helperLive = false

    /** Set at the end of [load]. An [update] before it would persist the empty placeholder
     *  over a file nobody has read yet — the one way memory could get ahead of the file. */
    private var loaded = false

    sealed interface Event {
        /** [created] means the default was written: an absent file, or one set aside.
         *  [failure] is why the file was unusable or why a write failed; null when neither. */
        data class Loaded(
            val config: ThorcutsConfig,
            val created: Boolean,
            val dropped: List<Dropped>,
            val setAside: String?,
            val failure: String?,
        ) : Event

        data class Written(val config: ThorcutsConfig) : Event

        /** Offered to the socket at [table].generation; the helper's ack is the proof. */
        data class TablePushed(val table: CompiledTable) : Event

        /** The current config does not compile (more than 64 applicable rules on disk). */
        data class PushFailed(val generation: Int, val reason: String) : Event

        /** An ack for a generation the store never pushed: a helper or supervisor fault. */
        data class UnexpectedAck(val generation: Int) : Event

        data class Fire(val resolution: FireResolution) : Event
    }

    sealed interface UpdateOutcome {
        data class Applied(val config: ThorcutsConfig) : UpdateOutcome
        data class Rejected(val reason: String) : UpdateOutcome
    }

    /**
     * Once, at start. Reads the file, or writes the default when there is none; sets an
     * unusable file aside (never overwrites it) and writes the default in its place. A file
     * that loads with some shortcuts dropped is NOT rewritten: the dropped entries stay on
     * disk, with the warning on screen, until the user's next write.
     */
    suspend fun load(): Unit = withContext(dispatcher) {
        val read = runCatching { file.read() }
        val text = read.getOrNull()
        val decoded: DecodeResult? = when {
            read.isFailure ->
                DecodeResult.Failed("could not read: ${read.exceptionOrNull()?.message}")
            text == null -> null
            else -> ConfigCodec.decode(text)
        }
        when (decoded) {
            null -> {
                val failure = writeDefault()
                publish(default)
                val created = failure == null
                onEvent(Event.Loaded(default, created, emptyList(), null, failure))
            }
            is DecodeResult.Ok -> {
                publish(decoded.config)
                onEvent(Event.Loaded(decoded.config, created = false, decoded.dropped, null, null))
            }
            is DecodeResult.Failed -> {
                // Set aside FIRST, and write the default only once that succeeded: if the
                // move fails the user's text stays where it is, and memory alone changes.
                val aside = runCatching { file.setAside() }
                val failure: String
                val created: Boolean
                if (aside.isFailure) {
                    failure = "${decoded.reason}; could not set the file aside: " +
                        "${aside.exceptionOrNull()?.message}"
                    created = false
                } else {
                    val writeFailure = writeDefault()
                    failure = if (writeFailure == null) {
                        decoded.reason
                    } else {
                        "${decoded.reason}; $writeFailure"
                    }
                    created = writeFailure == null
                }
                publish(default)
                onEvent(Event.Loaded(default, created, emptyList(), aside.getOrNull(), failure))
            }
        }
        loaded = true
    }

    /**
     * The one way to change the config: validate (by compiling), persist, publish, then
     * recompile-and-push if a helper is live. A [UpdateOutcome.Rejected] changes nothing —
     * not the file, not memory, not the generation.
     */
    suspend fun update(edit: (ThorcutsConfig) -> ThorcutsConfig): UpdateOutcome =
        withContext(dispatcher) {
            if (!loaded) return@withContext UpdateOutcome.Rejected("config not loaded")
            val candidate = try {
                edit(_config.value)
            } catch (e: RuntimeException) {
                return@withContext UpdateOutcome.Rejected(e.message ?: e.toString())
            }
            // Validate by compiling. The only `require` today is "more than 64 applicable
            // rules"; whatever the compiler refuses, the file never sees.
            val table = try {
                RuleCompiler.compile(candidate.shortcuts, null, generation + 1)
            } catch (e: IllegalArgumentException) {
                return@withContext UpdateOutcome.Rejected(e.message ?: "does not compile")
            }
            // The file is the source of truth: memory may not get ahead of it.
            try {
                file.write(ConfigCodec.encode(candidate))
            } catch (e: IOException) {
                return@withContext UpdateOutcome.Rejected("could not write: ${e.message}")
            }
            publish(candidate)
            onEvent(Event.Written(candidate))
            if (helperLive) offer(table)
            UpdateOutcome.Applied(candidate)
        }

    /** The helper said Ready: it is live, and it needs the current config at a fresh
     *  generation (every connection starts with no table). */
    fun helperReady() {
        scope.launch {
            helperLive = true
            val next = generation + 1
            val table = try {
                RuleCompiler.compile(_config.value.shortcuts, null, next)
            } catch (e: IllegalArgumentException) {
                generation = next
                onEvent(Event.PushFailed(next, e.message ?: "does not compile"))
                return@launch
            }
            offer(table)
        }
    }

    /** `T ok <gen> … installed|deferred`. An `installed` ack proves nothing older can fire
     *  again, so it prunes; `deferred` prunes nothing. An ack for a generation never pushed
     *  is reported and otherwise ignored. */
    fun tableAccepted(generation: Int, installed: Boolean) {
        scope.launch {
            if (tables.tables.none { it.generation == generation }) {
                onEvent(Event.UnexpectedAck(generation))
                return@launch
            }
            if (installed) tables = tables.installed(generation)
        }
    }

    /** `F <gen> <index>`: resolved against the retained tables and reported as a [Event.Fire]. */
    fun fired(generation: Int, index: Int) {
        scope.launch { onEvent(Event.Fire(tables.resolve(generation, index))) }
    }

    /** The helper's socket closed (exit, crash, panic, kill). Nothing retained can fire
     *  again; the next [helperReady] starts over at the next generation. */
    fun helperGone() {
        scope.launch {
            helperLive = false
            tables = RetainedTables()
        }
    }

    private fun publish(config: ThorcutsConfig) {
        _config.value = config
    }

    /** Retain, push, say so. The generation is consumed here, and by [helperReady]'s
     *  failed-compile branch — those two places and no other. */
    private fun offer(table: CompiledTable) {
        generation = table.generation
        tables = tables.push(table)
        push(table)
        onEvent(Event.TablePushed(table))
    }

    /** Null on success, else why not. Memory is published either way by the caller. */
    private fun writeDefault(): String? = try {
        file.write(ConfigCodec.encode(default))
        null
    } catch (e: IOException) {
        "could not write the default: ${e.message}"
    }

    private companion object {
        /** The same construction as Privilege.dispatcher, and deliberately not that thread. */
        fun singleThread(): CoroutineDispatcher =
            Executors.newSingleThreadExecutor { r ->
                Thread(r, "thorcuts-config").apply { isDaemon = true }
            }.asCoroutineDispatcher()
    }
}
