package com.grardb.thorcuts.platform.coordinator

import com.grardb.thorcuts.core.config.ConfigCodec
import com.grardb.thorcuts.core.config.ConfigFile
import com.grardb.thorcuts.core.config.ConfigMerge
import com.grardb.thorcuts.core.config.ConfigStore
import com.grardb.thorcuts.core.config.DecodeResult
import com.grardb.thorcuts.core.config.MergeResult
import com.grardb.thorcuts.core.config.ThorcutsConfig
import com.grardb.thorcuts.core.helper.HelperMessage
import com.grardb.thorcuts.core.model.Action
import com.grardb.thorcuts.core.recorder.TriggerRecorder
import com.grardb.thorcuts.core.rules.FireResolution
import com.grardb.thorcuts.platform.actions.ActionDispatcher
import com.grardb.thorcuts.platform.helper.HelperChannel
import com.grardb.thorcuts.platform.helper.HelperSupervisor
import com.grardb.thorcuts.platform.log.Logger
import com.grardb.thorcuts.platform.privilege.Privilege
import com.grardb.thorcuts.platform.privilege.PrivilegeGateway
import com.grardb.thorcuts.platform.tasks.Screenshots
import com.grardb.thorcuts.platform.tasks.TaskActions
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val NO_TABLE = "no table"
private const val NO_TABLE_NO_HELPER = "no table (helper not running)"
private const val LOG_ROWS = 40
private const val STARTING = "starting…"
private const val RESTARTING = "restarting…"
private const val STOPPING = "stopping…"

/** A loss within this of the previous one gives up instead of relaunching — the owner's one
 *  relaunch per chain; a quiet minute, or any user-driven start, forgives. */
private const val RELAUNCH_WINDOW_MS = 60_000L

/** The helper's exit reason for the hardwired combo: native/src/main.c's `leave("panic combo")`,
 *  carried as `X panic combo`. */
private const val PANIC = "panic combo"

/**
 * What the holder shows: the switch and its transition, whether the helper runs and is live,
 * why the last start failed when it did ([failure] — the one line the main screen keeps of
 * the three status lines since the UI polish; those three stay for the log and the tests),
 * and its own record — [lastOutcome] and [eventLog], rendered by nothing since the
 * diagnostics screen went (the editor spec's decision) and asserted by its tests. Held by
 * [ThorcutsCoordinator] for the life of the process, so a screen closed and reopened reads
 * the same state and the same log.
 */
data class CoordinatorState(
    /** The persisted switch: shortcuts are wanted on. */
    val enabled: Boolean,
    /**
     * A start has been decided and no stop, give-up or final loss has followed. Set when the
     * start is DECIDED, not when it completes: a helper lost during the blocking start must
     * not be resurrected by that start returning. Stays true through a relaunch, so the
     * switch reads ON and [ThorcutsCoordinator.ensureRunning] does nothing while one is
     * pending.
     */
    val running: Boolean = false,
    /** True between the helper's Ready and its loss: the one yes/no the editor's Record
     *  button needs ([helperState]'s text is overwritten by every later line). */
    val helperLive: Boolean = false,
    /** "starting…" / "restarting…" / "stopping…" while a blocking start, relaunch or stop is
     *  in flight — the switch button is disabled on it, and so is every other caller — and
     *  null otherwise. */
    val transition: String? = null,
    /** The reason the last start failed or the relaunch chain gave up — shown under the
     *  switch, which then reads ON with nothing running (a failed start) or OFF (a give-up).
     *  Null otherwise, and null again once the next start is decided. */
    val failure: String? = null,
    val helperState: String = "not started",
    /** Its own slot: an ack lands milliseconds after "ready, N devices" and must not
     *  overwrite it. */
    val tableState: String = NO_TABLE,
    /** What the config store last said — a count, the load warning, a rejected write — or
     *  what an export or import did (M5). */
    val configState: String = "config: loading",
    /** The last action's outcome line as `<line> (<label>)`: a fired shortcut's, or the
     *  Swap button's. */
    val lastOutcome: String = "",
    /** Newest first, [LOG_ROWS] rows. */
    val eventLog: List<String> = emptyList(),
    /** The accessibility service is connected: the app in front is being reported (M4). */
    val monitor: Boolean = false,
    /** The package of the app that holds input focus, or null: no monitor, nothing focused,
     *  or not known yet. */
    val foreground: String? = null,
)

/**
 * What [ThorcutsCoordinator.importFrom] came to. The config line carries the same outcome in
 * words; this is the structured form the app reads (the editor polish): a merge becomes the
 * report dialog, a failure a snackbar.
 */
sealed interface ImportOutcome {
    /** The merge ran and was written: what was added, skipped (with reasons) and dropped
     *  (with reasons). */
    data class Merged(val result: MergeResult) : ImportOutcome

    /** The outcome line without its `config: ` prefix: `import failed: …` — a read that
     *  threw, a file that failed to decode, or a merge the store refused (an overflow). */
    data class Failed(val line: String) : ImportOutcome
}

/**
 * Parent spec §7's owner "above the screen" and §12's `ThorcutsCoordinator`: the one holder
 * of everything with a lifetime — the gateway, the actions, the dispatcher, the helper
 * channel, the config store, the persisted switch — built once per process by the
 * Application and never torn down. `ThorcutsViewModel` is a view of [state] and [config];
 * the editor edits through [update], and a file the user picked is exported or imported through
 * [exportTo] and [importFrom]. Nothing here imports `android.*`: every Android touchpoint
 * arrives through a constructor argument, so the whole bookkeeping runs on the host.
 *
 * Threading: the helper's messages arrive on the supervisor's reader thread, the store's
 * events on the store's thread, and an export or import's outcome on its caller's thread
 * (the ViewModel's IO, M5) — all updating the thread-safe [state]; a fire hops to
 * [privileged] — Privilege.scope, the one
 * privileged thread, process-lifetime — and so do the blocking start and stop, which
 * therefore serialise against every fire and every UI-driven call. Every decision about
 * the switch, a start or a stop is made inside the state's CAS under one monitor
 * ([decideStart], [decideStop]), and a call during a transition is ignored, so a start and
 * a stop never overlap and the transition always has one owner. One guarantee is new: a
 * start awaits the one-time `load()`, so the helper's Ready can never compile the empty
 * placeholder.
 *
 * The switch. [setEnabled] true persists, holds the process through [keeper] (the app maps
 * it to its foreground service) and starts the helper; false stops the helper and then
 * releases. [ensureRunning] is the start half when the switch is on and nothing runs — the
 * one entry for the Activity, the boot receiver and a sticky restart of the service. A
 * helper that goes away unasked is RELAUNCHED once: the process stays held (a hold cannot be
 * re-issued from a process with nothing on screen), the switch reads "restarting…", and a
 * start is queued on the privileged thread, where the supervisor waits for the dying
 * session's socket before binding. A second loss within a minute of the previous one gives
 * up: the switch goes OFF with the reason, as the panic combo turns it off — the user asked
 * for the controller back, or the helper could not stay up, and nothing may re-grab it
 * until they say so. Any user-driven start forgives the chain.
 *
 * The recorder. [startRecording] holds the helper in capture mode — every raw event comes up
 * and feeds [recording], nothing but the panic goes out — exactly while [recording] is
 * non-null; [stopRecording] ends it. A lost helper clears it. The editor's ViewModel ends a
 * recording on the release, on close and on a timeout, so the gate never stays shut behind
 * a screen that is gone.
 */
class ThorcutsCoordinator(
    private val gateway: PrivilegeGateway,
    /**
     * Builds the channel around the callback its messages come back through. The real one
     * is `{ cb -> HelperSupervisor(app, gateway, cb) }`, which needs the callback at
     * construction — hence a factory rather than an instance.
     */
    channel: ((HelperMessage) -> Unit) -> HelperChannel,
    file: ConfigFile,
    private val switch: EnabledSwitch,
    /**
     * Hold (true) or release (false) the process. Idempotent on both sides; a release is
     * never issued while a stop owns the transition (its `finally` releases). MUST NOT
     * THROW: a hold runs before the start body's `finally` and a release before the stop's
     * `transition = null`, so a throw would latch the transition for the life of the process
     * and every later call would be ignored. The app's keeper catches every RuntimeException.
     */
    private val keeper: (Boolean) -> Unit,
    /** The launcher packages `close` refuses; see [TaskActions]. */
    homePackages: () -> Set<String>,
    /** A package's launch activity as `am start -n` takes it, or null; see [TaskActions]. */
    launchComponent: (String) -> String?,
    /** The bottom screenshot's gallery and toast; see [TaskActions]. */
    screenshots: Screenshots,
    private val log: Logger,
    /** Where the blocking privileged work and the fires run: Privilege.scope. A test passes
     *  an Unconfined scope so everything runs inline. */
    private val privileged: CoroutineScope = Privilege.scope,
    /** The store's thread; null means its own single thread. A test passes Unconfined. */
    storeDispatcher: CoroutineDispatcher? = null,
    /** Monotonic milliseconds, for the relaunch chain. A test passes its own. */
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private val tasks: TaskActions = TaskActions(gateway, homePackages, launchComponent, screenshots)
    private val dispatcher = ActionDispatcher(tasks)
    private val helper: HelperChannel = channel(::onHelperMessage)

    /**
     * Parent spec §8's serialising actor, built here beside the channel: the owner of the
     * config, the generation counter, the retained tables, recompile-on-write and the
     * Ready / ack / fire bookkeeping. Its thread is its own, so nothing here waits behind a
     * privileged call.
     */
    private val store: ConfigStore =
        if (storeDispatcher == null) ConfigStore(file, helper::pushTable, ::onConfigEvent)
        else ConfigStore(file, helper::pushTable, ::onConfigEvent, dispatcher = storeDispatcher)

    /** §8: the config as an immutable snapshot behind a StateFlow; the UI reads it. */
    val config: StateFlow<ThorcutsConfig> get() = store.config

    private val _state = MutableStateFlow(CoordinatorState(enabled = switch.read()))
    val state: StateFlow<CoordinatorState> = _state.asStateFlow()

    /** Set once the store has read the file. Before that the store holds the empty default,
     *  and an export would write it over the user's file. Written on the store's thread,
     *  read on the exporter's. */
    @Volatile private var loaded = false

    private val _recording = MutableStateFlow<TriggerRecorder?>(null)

    /**
     * The editor's recorder while it records, else null. The helper is in capture mode
     * exactly while this is non-null — the one fact both sides keep. Fed on the supervisor's
     * reader thread; read by the UI.
     */
    val recording: StateFlow<TriggerRecorder?> = _recording.asStateFlow()

    /**
     * Every write of the switch carries the epoch it was decided at and lands only if it is
     * still the latest. The UI's write is queued on the privileged thread (a disk commit,
     * never on main) while the panic's is immediate, on the reader thread; without the epoch
     * a stale queued ON could land after the panic's OFF. Read and written under the monitor.
     */
    private var switchEpoch = 0

    /** When the last unasked loss was decided, by [clock], or null when no chain is open: a
     *  loss within [RELAUNCH_WINDOW_MS] of it gives up. Under the monitor. */
    private var lastLossAt: Long? = null

    /** A relaunch is queued and not yet begun: the attempt whose helper was lost keeps the
     *  transition for it ([endTransition]); a failed start withdraws it; a give-up or the
     *  panic cancels it ([decideLoss]). Under the monitor. */
    private var relaunchOwed = false

    init {
        // The one-time load. The store hops to its own thread inside load(), so this parks
        // nothing; a start calls load() again — idempotent, queued on the store's single
        // thread behind this call — and so awaits it (see begin).
        privileged.launch { store.load() }
    }

    private enum class Verdict { IGNORED, NOOP, START, STOP }

    /** What a decision saw and decided, and the switch epoch it left behind. */
    private class Decision(val verdict: Verdict, val seen: CoordinatorState, val epoch: Int)

    private enum class Loss { STOPPING, RELEASE, RELAUNCH, GIVE_UP }

    /** What a loss decided, and the switch epoch a give-up's write carries. */
    private class LossDecision(val verdict: Loss, val epoch: Int)

    /**
     * Persist the switch and act on it. Returns at once. Call it on the MAIN thread, as every
     * caller does today: the hold ([keeper] true) runs on the caller's thread, and the keeper's
     * flag is safe only while every hold is issued on main (HelperService.live says why).
     * Ignored, with a log line, while a start or stop is in flight: the button is disabled on
     * the transition, and no other caller may overlap one either. The write lands on the
     * privileged thread, never the caller's, and being queued first on that single thread it
     * precedes the start or stop queued after it.
     */
    fun setEnabled(on: Boolean) {
        val decision = if (on) decideStart(switchOn = true) else decideStop()
        if (decision.verdict == Verdict.IGNORED) {
            log.w("SWITCH on=$on ignored: ${decision.seen.transition}")
            return
        }
        privileged.launch { writeSwitch(on, decision.epoch, by = "ui") }
        if (on) {
            if (decision.verdict == Verdict.START) begin()
        } else {
            privileged.launch {
                try {
                    helper.stop()
                    log.i("HELPER_STOP done")
                } finally {
                    _state.update { it.copy(running = false) }
                    release()
                    _state.update { it.copy(transition = null) }
                }
            }
        }
    }

    /**
     * Start the helper if the switch is on and nothing runs; otherwise nothing. Idempotent,
     * and the one entry for every process start: the Activity's ViewModel, the boot
     * receiver, and the service's onStartCommand after a sticky restart. Returns at once.
     * On the MAIN thread, for the reason [setEnabled] gives.
     */
    fun ensureRunning() {
        val d = decideStart(switchOn = false)
        log.i(
            "ENSURE enabled=${d.seen.enabled} running=${d.seen.running} -> " +
                d.verdict.name.lowercase()
        )
        if (d.verdict == Verdict.START) begin()
    }

    /** The accessibility service connected or went away (ForegroundAppMonitor). On the main
     *  thread, as the service calls it; returns at once. */
    fun monitor(connected: Boolean) {
        log.i("MONITOR ${if (connected) "connected" else "disconnected"}")
        _state.update { it.copy(monitor = connected) }
    }

    /**
     * The app that holds input focus changed, or is unknown (null: no monitor, nothing
     * focused, or not yet known). On the main thread; logged on a change only. The store
     * recompiles for [packageName] on its own thread and pushes only when the selected list
     * changed, so an app switch that changes no rule costs nothing.
     */
    fun foreground(packageName: String?) {
        if (_state.value.foreground == packageName) return
        log.i("FOREGROUND ${packageName ?: "unknown"}")
        _state.update { it.copy(foreground = packageName) }
        store.foreground(packageName)
    }

    /**
     * Perform [action] now, on the calling thread — Privilege.dispatcher, as every
     * [TaskActions] call requires — and record its outcome as `<line> (<label>)`. A fire
     * calls it with the shortcut's id; nothing outside this module does since the diagnostics
     * screen went (its test still does).
     */
    internal fun run(action: Action, label: String): String {
        val outcome = dispatcher.dispatch(action)
        _state.update { it.copy(lastOutcome = "$outcome ($label)") }
        return outcome
    }

    /** The one write surface: the store's update, plus a rejected write shown and logged. */
    suspend fun update(edit: (ThorcutsConfig) -> ThorcutsConfig): ConfigStore.UpdateOutcome {
        val outcome = store.update(edit)
        if (outcome is ConfigStore.UpdateOutcome.Rejected) {
            log.w("CONFIG_WRITE_REJECTED ${outcome.reason}")
            configLine("write rejected: ${outcome.reason}")
        }
        return outcome
    }

    /** Put [outcome] on the config line and return it, without the line's `config: ` prefix:
     *  what the screen shows as a snackbar (the UI polish) — except an import that merged,
     *  whose report is the dialog's and which calls this for the line alone. */
    private fun configLine(outcome: String): String {
        _state.update { it.copy(configState = "config: $outcome") }
        return outcome
    }

    /**
     * Export: the current config, encoded, handed to [write] — the ViewModel's write into the
     * file the user picked (parent §10: "export is the file"). The outcome goes to the config
     * line and is returned, for the snackbar. [write] is foreign I/O, so whatever it throws
     * becomes that line and not a crash (the store treats the edit lambda the same way).
     * Refused before the first load, when the store still holds the empty default. Call off
     * main; the ViewModel does.
     */
    fun exportTo(write: (String) -> Unit): String {
        if (!loaded) return exportFailed("the config has not loaded yet")
        val config = store.config.value
        try {
            write(ConfigCodec.encode(config))
        } catch (e: Exception) {
            return exportFailed(e.message ?: e.toString())
        }
        val n = config.shortcuts.size
        log.i("CONFIG_EXPORT shortcuts=$n")
        return configLine("exported $n shortcut(s)")
    }

    private fun exportFailed(reason: String): String {
        log.w("CONFIG_EXPORT_FAILED $reason")
        return configLine("export failed: $reason")
    }

    /**
     * Import as a merge that never replaces (parent §10): [read]'s text decoded, merged into
     * the current config inside [update] — the edit runs against the store's value, so a
     * write between the pick and the merge is not lost — and reported on the config line:
     * what was added, skipped and dropped, each with its reason. A read that throws or a file
     * that fails to decode is reported and writes nothing; a write the store refuses (an
     * overflow) is a failed import with the store's reason. The report lands after the
     * store's "written" line, which `update` sets before it returns. The outcome is also
     * returned in its structured form ([ImportOutcome]) for the app's report dialog. Call
     * off main.
     */
    suspend fun importFrom(read: () -> String): ImportOutcome {
        val text = try {
            read()
        } catch (e: Exception) {
            return importFailed("could not read: ${e.message ?: e.toString()}")
        }
        val decoded = when (val d = ConfigCodec.decode(text)) {
            is DecodeResult.Failed -> return importFailed(d.reason)
            is DecodeResult.Ok -> d
        }
        var result: MergeResult? = null
        val outcome = update { current ->
            ConfigMerge.merge(current, decoded).also { result = it }.config
        }
        return when (outcome) {
            is ConfigStore.UpdateOutcome.Rejected -> importFailed(outcome.reason)
            is ConfigStore.UpdateOutcome.Applied -> {
                val r = checkNotNull(result) { "Applied, so the edit ran" }
                log.i(
                    "CONFIG_IMPORT added=${r.added.size} skipped=${r.skipped.size} " +
                        "dropped=${r.dropped.size}"
                )
                configLine(importLine(r))   // the line only: the screen's report is the dialog's
                ImportOutcome.Merged(r)
            }
        }
    }

    private fun importFailed(reason: String): ImportOutcome.Failed {
        log.w("CONFIG_IMPORT_FAILED $reason")
        return ImportOutcome.Failed(configLine("import failed: $reason"))
    }

    /** `N shortcut(s), imported: a added, s skipped (id: reason; …), d dropped (#i id:
     *  reason; …)` — a parenthesis only when its count is non-zero; a drop is its
     *  `Dropped.line`, as the load report writes one. [configLine] adds the prefix. */
    private fun importLine(r: MergeResult): String = buildString {
        append("${r.config.shortcuts.size} shortcut(s), imported: ")
        append("${r.added.size} added, ${r.skipped.size} skipped")
        if (r.skipped.isNotEmpty()) {
            append(r.skipped.joinToString("; ", " (", ")") { "${it.id}: ${it.reason}" })
        }
        append(", ${r.dropped.size} dropped")
        if (r.dropped.isNotEmpty()) {
            append(r.dropped.joinToString("; ", " (", ")") { it.line })
        }
    }

    /**
     * Start recording, or start over: a fresh recorder, and the helper asked for capture.
     * Nothing, with a log line, while the helper is not live.
     * The state write and the queued send are not one step, so every start and stop must come
     * from ONE thread — the UI's main, as every caller does; [lost] is the one other writer
     * and sends nothing. The send is queued on the privileged thread — a socket write, never
     * on the caller's — and being queued on that single thread it stays in order with a stop
     * queued after it. Dropped by the channel when nothing is connected.
     */
    fun startRecording() {
        if (!_state.value.helperLive) {
            // The UI offers Record only while the helper is live; this closes the race with
            // a loss landing between the render and the tap, and the window a relaunch
            // opens. The ViewModel's timeout then ends a recording that never began: a
            // no-op.
            log.w("CAPTURE on ignored: helper not live")
            return
        }
        _recording.value = TriggerRecorder()
        log.i("CAPTURE on")
        privileged.launch { helper.setCapture(true) }
    }

    /** End the recording and reopen the gate. Nothing when not recording. */
    fun stopRecording() {
        if (_recording.getAndUpdate { null } == null) return
        log.i("CAPTURE off")
        privileged.launch { helper.setCapture(false) }
    }

    /**
     * The one place a start — and, with [switchOn], the switch itself — is decided: inside
     * the state's CAS and under one monitor, so two callers racing (the boot receiver and
     * the Activity, or two taps) decide once and no write from the reader or store thread is
     * lost. IGNORED while a transition is in flight; NOOP when the switch is off or a helper
     * already runs (the switch still goes on). A fresh start gets a fresh table slot: there
     * is no live helper, so "no table" is true. `update`'s last invocation is the committed
     * one, so what the locals hold at the end is what was decided.
     */
    @Synchronized
    private fun decideStart(switchOn: Boolean): Decision {
        var verdict = Verdict.NOOP
        var seen = _state.value
        _state.update { s ->
            seen = s
            val enabled = s.enabled || switchOn
            verdict = when {
                s.transition != null -> Verdict.IGNORED
                !enabled || s.running -> Verdict.NOOP
                else -> Verdict.START
            }
            when (verdict) {
                Verdict.IGNORED -> s
                Verdict.START -> s.copy(
                    enabled = true, running = true, transition = STARTING, tableState = NO_TABLE,
                    failure = null,
                )
                else -> s.copy(enabled = enabled)
            }
        }
        if (switchOn && verdict != Verdict.IGNORED) switchEpoch++
        // A user asking for a start always gets the full relaunch budget.
        if (verdict == Verdict.START) lastLossAt = null
        return Decision(verdict, seen, switchEpoch)
    }

    /** The stop's twin: IGNORED during a transition, else the switch goes off and the stop
     *  owns the transition until its `finally`. */
    @Synchronized
    private fun decideStop(): Decision {
        var verdict = Verdict.STOP
        var seen = _state.value
        _state.update { s ->
            seen = s
            verdict = if (s.transition != null) Verdict.IGNORED else Verdict.STOP
            if (verdict == Verdict.IGNORED) s else s.copy(enabled = false, transition = STOPPING)
        }
        if (verdict != Verdict.IGNORED) switchEpoch++
        return Decision(verdict, seen, switchEpoch)
    }

    /** The panic's half of [decideStop]: the switch goes off at a fresh epoch, nothing else
     *  (the loss itself is handled by [lost]). */
    @Synchronized
    private fun switchOff(): Int {
        _state.update { it.copy(enabled = false) }
        return ++switchEpoch
    }

    /** Write the switch if [epoch] is still the latest. Under the monitor, so two writes
     *  cannot interleave. */
    @Synchronized
    private fun writeSwitch(on: Boolean, epoch: Int, by: String) {
        if (epoch != switchEpoch) {
            log.w("SWITCH on=$on by=$by stale, not written")
            return
        }
        switch.write(on)
        log.i("SWITCH on=$on by=$by")
    }

    /** The start itself: hold the process, then bind and launch on the privileged thread. */
    private fun begin() {
        hold()
        privileged.launch { attempt(relaunch = false) }
    }

    /**
     * One start on the privileged thread. A relaunch holds nothing — the process never let
     * go — and is skipped when the owed relaunch was withdrawn by a failed start queued ahead
     * of it, or cancelled by a give-up or the panic (which end the transition it would have
     * ended). Two BLOCKING starts never overlap (one thread, FIFO); an attempt does yield the
     * thread at `store.load()` (a hop to the store's thread), so a relaunch queued in that
     * window runs interleaved with it, and the transition is ended by whichever attempt
     * finishes with no relaunch owed.
     */
    private suspend fun attempt(relaunch: Boolean) {
        if (relaunch && !takeOwed()) return
        try {
            // Awaits the one-time load: idempotent, so a load that threw once is retried
            // here rather than poisoning every later start.
            store.load()
            val outcome = helper.start()
            log.i("HELPER_START outcome=$outcome")
            val failed = when (outcome) {
                HelperChannel.Start.STARTED -> null
                HelperChannel.Start.NOT_BOUND -> "could not bind the helper socket"
                // The coordinator only asks when it holds no session, so this names a dying
                // session that did not end within the supervisor's wait. Not a start.
                HelperChannel.Start.ALREADY_RUNNING ->
                    "the previous helper session was still winding down; start again"
            }
            if (failed != null) {
                withdrawOwed()
                if (relaunch) {
                    giveUp(failed)
                } else {
                    _state.update {
                        it.copy(running = false, helperState = failed, failure = failed)
                    }
                    release()
                }
            }
        } catch (t: Throwable) {
            // Unreachable by any path that exists today — load() catches its own I/O and
            // start() reports failures as messages — but if it ever is, the state must not
            // claim a helper that never started: say so, release, and let the scope's
            // handler log the throw.
            withdrawOwed()
            val reason = "start failed: $t"
            _state.update { it.copy(running = false, helperState = reason, failure = reason) }
            release()
            throw t
        } finally {
            // Whatever happened, the transition ends — or the switch button stays disabled —
            // unless a relaunch queued behind this attempt inherits it.
            endTransition()
        }
    }

    @Synchronized
    private fun takeOwed(): Boolean = relaunchOwed.also { relaunchOwed = false }

    @Synchronized
    private fun withdrawOwed() {
        relaunchOwed = false
    }

    /** The attempt is over: the transition ends unless a relaunch is owed, in which case the
     *  attempt queued behind this one ends it. Under the monitor with [decideLoss], so a loss
     *  cannot land between the check and the clear. */
    @Synchronized
    private fun endTransition() {
        if (!relaunchOwed) _state.update { it.copy(transition = null) }
    }

    /** A relaunch whose own start failed: the switch goes off with the reason, as a second
     *  loss would turn it off, and the process is released. */
    private fun giveUp(reason: String) {
        val epoch = switchOff()
        val text = "gave up: $reason"
        _state.update { it.copy(running = false, helperState = text, failure = text) }
        log.w("RELAUNCH gave up: $reason")
        row("GAVE UP: $reason")
        writeSwitch(false, epoch, by = "gave-up")
        release()
    }

    private fun hold() {
        log.i("KEEPER hold")
        keeper(true)
    }

    private fun release() {
        log.i("KEEPER release")
        keeper(false)
    }

    /**
     * The helper is gone, asked or not: nothing runs. Decided under the monitor
     * ([decideLoss]); the keeper and the switch write run outside it, as everywhere. A stop
     * that owns the transition releases in its own `finally`, once the pad is back, so a
     * UI-less process is not left cached while the pad may still be renumbered; the panic
     * has already turned the switch off, so it releases here; an unasked loss with the
     * switch on is relaunched or, within a minute of the previous one, given up on.
     */
    private fun lost(helperState: String) {
        log.w("HELPER_LOST $helperState")
        row("HELPER $helperState")
        // Nothing left to listen to: the gate went with the helper, and the editor's
        // recording block must not keep asking for a combo. No send — nothing is connected.
        _recording.value = null
        val d = decideLoss(helperState)
        when (d.verdict) {
            Loss.STOPPING -> Unit
            Loss.RELEASE -> release()
            Loss.RELAUNCH -> privileged.launch { attempt(relaunch = true) }
            Loss.GIVE_UP -> {
                // Persisted BEFORE the release, as the panic's OFF is: once the service is
                // gone a UI-less process may die at any moment.
                writeSwitch(false, d.epoch, by = "gave-up")
                release()
            }
        }
    }

    /**
     * The loss's decision, inside the state's CAS and under the monitor: STOPPING when a stop
     * owns the transition; RELEASE when the switch is already off (the panic) or nothing runs
     * (a message from a session this holder has already given up on — a start that timed out
     * waiting for it, a stop that timed out — finds nothing held, so there is nothing to
     * relaunch into: it releases as it always did); else the chain — RELAUNCH for the first
     * loss, or one at least [RELAUNCH_WINDOW_MS] after the previous, GIVE_UP for a second
     * within it. A relaunch keeps `running` (the switch reads ON; ensureRunning does
     * nothing), shows "restarting…" unless a start is in flight (whose attempt then keeps its
     * own transition for the relaunch queued behind it) and marks the relaunch owed. A give-up
     * turns the switch off at a fresh epoch. A give-up or a RELEASE that finds a relaunch
     * still queued cancels it, and ends the "restarting…" that relaunch would have ended.
     */
    @Synchronized
    private fun decideLoss(helperState: String): LossDecision {
        val now = clock()
        val owed = relaunchOwed
        var verdict = Loss.RELEASE
        _state.update { s ->
            verdict = when {
                s.transition == STOPPING -> Loss.STOPPING
                !s.enabled || !s.running -> Loss.RELEASE
                lastLossAt.let { it != null && now - it < RELAUNCH_WINDOW_MS } -> Loss.GIVE_UP
                else -> Loss.RELAUNCH
            }
            val gone = s.copy(
                helperLive = false, helperState = helperState, tableState = NO_TABLE_NO_HELPER,
            )
            // A relaunch still queued is cancelled below, and its attempt returns before the
            // try that would have ended the transition it was handed — so a "restarting…"
            // with no other owner ends here. A "starting…" belongs to an attempt in flight,
            // which ends its own.
            val ended = if (owed && s.transition == RESTARTING) null else s.transition
            val gaveUp = "gave up: $helperState, twice in a minute"
            when (verdict) {
                Loss.RELAUNCH -> gone.copy(transition = s.transition ?: RESTARTING)
                Loss.GIVE_UP -> gone.copy(
                    enabled = false, running = false, transition = ended,
                    helperState = gaveUp, failure = gaveUp,
                )
                Loss.RELEASE -> gone.copy(running = false, transition = ended)
                Loss.STOPPING -> gone.copy(running = false)
            }
        }
        when (verdict) {
            Loss.RELAUNCH -> {
                lastLossAt = now
                relaunchOwed = true
                log.i("RELAUNCH after $helperState")
                row("RELAUNCH after $helperState")
            }
            Loss.GIVE_UP -> {
                lastLossAt = now
                relaunchOwed = false
                switchEpoch++
                log.w("RELAUNCH gave up: $helperState")
                row("GAVE UP: $helperState")
            }
            // The panic, or a loss that found nothing running: nothing may start after it. A
            // relaunch still queued returns at takeOwed().
            Loss.RELEASE -> relaunchOwed = false
            Loss.STOPPING -> Unit
        }
        return LossDecision(verdict, switchEpoch)
    }

    /** On the supervisor's reader thread. */
    private fun onHelperMessage(message: HelperMessage) {
        when (message) {
            is HelperMessage.Ready -> {
                val ready = "ready, ${message.deviceCount} devices"
                _state.update { it.copy(helperLive = true, helperState = ready) }
                // The store compiles the current config at the next generation and pushes
                // it — on Ready, never earlier (HelperSupervisor.pushTable says why). A
                // config that does not compile comes back as a PushFailed event.
                store.helperReady()
                // A helper that came up during a recording starts in match mode. A recording
                // needs a live helper (startRecording) and a loss clears it, so no path
                // reaches this today; it stays as the backstop of the invariant — capture
                // mode exactly while recording — and its test pins it.
                if (_recording.value != null) privileged.launch { helper.setCapture(true) }
            }
            is HelperMessage.Log -> {
                // The disconnect line is the one Log that means "no helper": it went away
                // without saying why (a crash, a kill). lost() decides what follows.
                if (message.text == HelperSupervisor.DISCONNECTED) {
                    store.helperGone()
                    lost(message.text)
                } else {
                    _state.update { it.copy(helperState = message.text) }
                }
            }
            is HelperMessage.Exiting -> {
                store.helperGone()
                if (message.reason == PANIC) {
                    // The user asked for the controller back: nothing may restart the helper
                    // at the next boot or open. Persisted BEFORE the release in lost() —
                    // once the service is gone, a UI-less process may die at any moment —
                    // and at a fresh epoch, so a stale queued ON cannot land after it.
                    writeSwitch(false, switchOff(), by = "panic")
                }
                lost("exited: ${message.reason}")
            }
            is HelperMessage.Fired -> store.fired(message.generation, message.index)
            // Raw events arrive only in capture mode, and only the recorder wants them.
            is HelperMessage.Event -> _recording.update { it?.feed(message) }
            is HelperMessage.TableAccepted -> {
                val gen = message.generation
                // `installed` is the helper's proof that nothing older than gen can fire
                // again; the store prunes on it and keeps on deferred. An ack for a
                // generation it never pushed comes back as an UnexpectedAck event.
                store.tableAccepted(gen, message.installed)
                val how = if (message.installed) "installed" else "deferred"
                log.i("TABLE_ACK gen=$gen rules=${message.ruleCount} $how")
                _state.update {
                    it.copy(tableState = "table gen $gen: ${message.ruleCount} rule(s), $how")
                }
                row("TABLE gen $gen ok, ${message.ruleCount} rule(s), $how")
            }
            is HelperMessage.TableRejected -> {
                val gen = message.generation
                log.w("TABLE_REJECTED gen=$gen reason=${message.reason}")
                val line = "table gen $gen REJECTED: ${message.reason}"
                _state.update { it.copy(tableState = line) }
                row("TABLE gen $gen REJECTED: ${message.reason}")
            }
        }
    }

    /** On the store's thread. A fire hops to [privileged] below. */
    private fun onConfigEvent(event: ConfigStore.Event) {
        when (event) {
            is ConfigStore.Event.Loaded -> {
                loaded = true
                val n = event.config.shortcuts.size
                log.i(
                    "CONFIG_LOAD created=${event.created} shortcuts=$n " +
                        "dropped=${event.dropped.size} setAside=${event.setAside} " +
                        "failure=${event.failure}"
                )
                val line = buildString {
                    append("config: $n shortcut(s)")
                    if (event.created) append(", default written")
                    if (event.dropped.isNotEmpty()) {
                        append(", ${event.dropped.size} dropped: ")
                        append(event.dropped.joinToString("; ") { it.line })
                    }
                    if (event.setAside != null) {
                        append(", unreadable file kept as ${event.setAside}")
                    }
                    if (event.failure != null) append(" (${event.failure})")
                }
                _state.update { it.copy(configState = line) }
            }
            is ConfigStore.Event.Written -> {
                val n = event.config.shortcuts.size
                log.i("CONFIG_WRITE shortcuts=$n")
                _state.update { it.copy(configState = "config: $n shortcut(s), written") }
            }
            is ConfigStore.Event.TablePushed -> {
                // The supervisor's TABLE_PUSH line already logs it; the ack is the proof.
                val t = event.table
                row("TABLE gen ${t.generation} pushed, ${t.ruleCount} rule(s)")
            }
            is ConfigStore.Event.PushFailed -> {
                val gen = event.generation
                log.w("TABLE_PUSH_FAILED gen=$gen: ${event.reason}")
                val line = "table gen $gen NOT PUSHED: ${event.reason}"
                _state.update { it.copy(tableState = line) }
                row("TABLE gen $gen NOT PUSHED: ${event.reason}")
            }
            is ConfigStore.Event.UnexpectedAck ->
                log.w("TABLE_ACK gen=${event.generation} matches no pushed table")
            is ConfigStore.Event.Fire -> onFire(event.resolution)
            is ConfigStore.Event.Failed -> {
                // Where the process used to die: reported with the trace, and a row the
                // owner can see.
                log.e("CONFIG_FAILURE", event.error)
                row("CONFIG FAILURE: ${event.error}")
            }
        }
    }

    private fun onFire(res: FireResolution) {
        when (res) {
            is FireResolution.Hit -> {
                val gen = res.table.generation
                val index = res.index
                val id = res.shortcut.id
                log.i("FIRED gen=$gen index=$index shortcut=$id")
                row("FIRED gen $gen #$index $id")
                // The privileged scope, never a UI scope: a shortcut is not a screen (§8),
                // and this holder has no screen to die with anyway. The same single thread
                // as every UI-driven call, so they serialise.
                privileged.launch {
                    val outcome = run(res.shortcut.action, id)
                    log.i("FIRE_OUTCOME gen=$gen index=$index shortcut=$id outcome=$outcome")
                    row("FIRED gen $gen #$index $id -> $outcome")
                }
            }
            is FireResolution.Stale -> unresolved("gen=${res.generation}", res)
            is FireResolution.OutOfRange ->
                unresolved("gen=${res.generation} index=${res.index}", res)
        }
    }

    /** A fire from a table no longer held, or an index the table does not have. Nothing
     *  runs; the row says why. */
    private fun unresolved(where: String, res: FireResolution) {
        log.w("FIRE_UNRESOLVED $where: $res")
        row("FIRED $where UNRESOLVED: $res")
    }

    private fun row(line: String) {
        _state.update { it.copy(eventLog = (listOf(line) + it.eventLog).take(LOG_ROWS)) }
    }
}
