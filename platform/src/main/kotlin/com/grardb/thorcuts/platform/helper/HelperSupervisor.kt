package com.grardb.thorcuts.platform.helper

import android.content.Context
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.SystemClock
import android.util.Log
import com.grardb.thorcuts.core.helper.HelperCommand
import com.grardb.thorcuts.core.helper.HelperMessage
import com.grardb.thorcuts.core.helper.HelperProtocol
import com.grardb.thorcuts.core.pad.PadAction
import com.grardb.thorcuts.core.rules.CompiledTable
import com.grardb.thorcuts.platform.pad.ControllerNumbers
import com.grardb.thorcuts.platform.privilege.PserviceGateway
import java.io.OutputStream

/**
 * Owns the app's side of the helper channel: hosts the socket, launches the
 * helper as uid 0, pings it, and reads what it says — one session at a time: a
 * session this supervisor has moved past (a [stop] that timed out with the
 * helper still on its socket, then a new [start]) is never heard again, see
 * [session]. The [HelperChannel] the coordinator holds; built once per process.
 */
class HelperSupervisor(
    private val appContext: Context,
    private val gateway: PserviceGateway,
    private val onMessage: (HelperMessage) -> Unit,
) : HelperChannel {

    @Volatile var connected: Boolean = false
        private set

    @Volatile private var running = false
    @Volatile private var stopRequested = false
    @Volatile private var server: LocalServerSocket? = null
    @Volatile private var out: OutputStream? = null
    @Volatile private var pinger: Thread? = null

    /**
     * Bumped by every start(). The accept thread (which is the reader), the ping thread and
     * serve() capture theirs and stand down when it is no longer the current one, so a line,
     * a disconnect, a teardown or a ping from a session this supervisor has moved past never
     * reaches the coordinator or touches the new session's fields. Written on the privileged
     * thread only; read by the session threads.
     */
    @Volatile private var session = 0

    /**
     * Bind the socket, then launch the helper. Call on Privilege.dispatcher: BLOCKING — up
     * to [SESSION_WAIT_MS] (8 s) for a dying session, then the settle
     * ([ControllerNumbers.SETTLE_WAIT_MS]) and the launch script's second. NOT_BOUND only
     * when the socket could not be bound (a launch that fails says so as a message);
     * ALREADY_RUNNING only when a dying session has not ended within [SESSION_WAIT_MS] —
     * its terminal message reaches the coordinator seconds before its socket closes, and
     * this waits that out — which the coordinator treats as no start at all.
     */
    override fun start(): HelperChannel.Start {
        // A session that just reported its exit is still winding down: the helper restores
        // the pad's number (3 s) between its last words and closing the socket, and
        // acceptLoop releases the name only at EOF. The coordinator asks only when it holds
        // no session, so `running` here means exactly that window — wait for it, bounded,
        // before binding. A relaunch after an `X`-reason loss lands here every time; a
        // reopen in the window did too, and used to be answered ALREADY_RUNNING at once.
        if (running) {
            val from = SystemClock.elapsedRealtime()
            while (running && SystemClock.elapsedRealtime() - from < SESSION_WAIT_MS) {
                try {
                    Thread.sleep(STOP_POLL_MS)
                } catch (_: InterruptedException) {
                    break
                }
            }
            if (running) {
                // The only signal in the supervisor's own tag that a session hung: the
                // coordinator logs the outcome, not the wait.
                val waited = SystemClock.elapsedRealtime() - from
                Log.w(TAG, "SESSION_WAIT timeout after ${waited}ms; session still up")
                return HelperChannel.Start.ALREADY_RUNNING
            }
            Log.i(TAG, "SESSION_WAIT ${SystemClock.elapsedRealtime() - from}ms")
        }
        running = true
        stopRequested = false
        val mine = ++session

        // Self-heal. This covers a SIGKILL of the helper, a crash mid-takeover, a
        // restore that never landed — the cases where the helper's own teardown
        // could not finish and no clean stop ran (a swipe from recents is NOT one:
        // measured 2026-10-14, it is a force-stop whose socket close the helper
        // does see, and it restores the pad on the way out) — and where a pad left
        // renumbered (or deregistered outright) is exactly what breaks the
        // emulators this feature exists to fix, with nothing on screen to connect
        // the two facts.
        //
        // IT RUNS HERE, WHICH IS WHEN THE USER TAPS START — NOT WHEN THE APP
        // OPENS. Nothing auto-starts the helper, so a user who opens Thorcuts to
        // find out why their controller is broken, sees "not started" and does not
        // tap Start gets no repair. The design once called this "self-heal at
        // launch"; that was wrong and is corrected in the spec. Tapping Start is
        // the natural next action, so the gap is modest, but it is a gap.
        //
        // Acts ONLY on RESTORE. TAKE_OVER is deliberately ignored here: assess()
        // describes what the numbering needs, not what we intend. Whether to take
        // over is launch()'s decision, made below from a settled sample, and the
        // helper's to execute.
        //
        // RESTORE also covers "no Thor pad registered AT ALL" — the state a crash
        // midway through the helper's takeover leaves behind, and the state this
        // layer exists to cover. That is safe here SPECIFICALLY because this runs
        // BEFORE launch(): the one window in which the pad is legitimately absent
        // is our own takeover's deregister, and it has not opened yet, so an
        // empty list here cannot be our doing.
        //
        // The PLAIN assess() is correct here, and ONLY here: nothing in this
        // process has written the trigger key yet, so there is no re-enumeration
        // in flight to wait out. Every sample taken AFTER a write of ours goes
        // through assessAfterSettle() instead — `settings put` returns as soon as
        // the value is stored, the pad is absent for seconds afterwards, and an
        // early sample reads that transient absence as RESTORE. Do not collapse
        // the two into one call.
        val onStart = ControllerNumbers.assess()
        Log.i(TAG, "PAD_ASSESS start action=${onStart.action} why=${onStart.why}")
        if (onStart.action == PadAction.RESTORE) {
            onMessage(HelperMessage.Log("restoring the controller numbering: ${onStart.why}"))
            // A null reply from pservice means the cycle never ran, so the pad is
            // still wrong. Say so, with the same recovery the helper names.
            if (!ControllerNumbers.restoreNumbering(gateway)) {
                onMessage(HelperMessage.Log(RESTORE_FAILED))
            }
        }

        // Bind SYNCHRONOUSLY before launching, so the helper cannot race ahead of
        // the socket existing.
        val ss = try {
            LocalServerSocket(SOCKET)
        } catch (t: Throwable) {
            Log.w(TAG, "bind $SOCKET failed: ${t.message}")
            running = false
            return HelperChannel.Start.NOT_BOUND
        }
        server = ss

        Thread({ acceptLoop(ss, mine) }, "thorcuts-helper-accept").apply { isDaemon = true }
            .start()
        pinger = Thread({ pingLoop(mine) }, "thorcuts-helper-ping").apply {
            isDaemon = true
            start()
        }

        launch()
        return HelperChannel.Start.STARTED
    }

    /**
     * Ask the helper to go, then WAIT for it to actually go.
     *
     * A no-op when nothing was started. Otherwise BLOCKING, and the honest worst
     * case is roughly **17 s**: a blocking send, then up to [STOP_WAIT_MS] (8 s)
     * waiting for the helper's socket to reach EOF, then up to
     * [ControllerNumbers.SETTLE_WAIT_MS] (6 s) waiting for Android to re-enumerate
     * the pad, and then — only if the pad really is still wrong — a restore
     * cycle, which is a 3 s sleep between two pservice round trips. Every stage
     * but the send is skipped on the normal path, where the helper disconnects in
     * well under a second and its own restore has already put the pad back.
     *
     * Call on Privilege.dispatcher, as the coordinator does.
     */
    override fun stop() {
        // Nothing to stop. Without this an idle supervisor still assessed the pad
        // and could run a full 3 s restore cycle — blinking out a controller this
        // object has never touched — on nothing more than a stray Stop tap.
        //
        // `connected` is in the test too, not just `running`: after a stop() that
        // timed out, running is already false while the helper may still be on the
        // socket, and a second Stop has to be able to reach it.
        if (!running && !connected) return

        stopRequested = true
        send(HelperCommand.Stop)

        // The helper writes "X stop requested" at once and THEN spends ~3s putting
        // the controller numbering back, so its most important line — the one
        // saying the restore failed and the user must use AYN's Controller style
        // tile or reboot — is the LAST thing it writes. Closing the channel as soon
        // as the command was sent discarded exactly that message.
        //
        // Waiting here also serialises a restart against this teardown for free.
        // start() and stop() both run on Privilege.dispatcher, which is
        // single-threaded, so a Start queued behind this one cannot begin until the
        // helper has gone and the pad is back. Without the wait, a Start during the
        // ~3s window finds no gamepad at all: no takeover is attempted, the reopen
        // fails too, and the helper runs with its pad half dead while the dying
        // process quietly restores the pad behind it.
        //
        // elapsedRealtime(), not currentTimeMillis(): the latter can step (NTP, the
        // user, a timezone-less clock sync) and a backwards step cuts this wait
        // short, losing the helper's final message — the exact failure this wait
        // exists to close. It is the Kotlin equivalent of the CLOCK_MONOTONIC the
        // native side already uses for the same job.
        val deadline = SystemClock.elapsedRealtime() + STOP_WAIT_MS
        while (connected && SystemClock.elapsedRealtime() < deadline) {
            try {
                Thread.sleep(STOP_POLL_MS)
            } catch (_: InterruptedException) {
                break
            }
        }
        if (connected) {
            Log.w(TAG, "helper still connected after ${STOP_WAIT_MS}ms; closing anyway")
            onMessage(HelperMessage.Log("helper did not exit cleanly"))
        }

        // `running` goes false BEFORE the name is released here — the reverse of the unasked
        // teardown's order in acceptLoop — because the self-connect below must find the
        // accept loop ready to exit, not take the unasked branch. Safe only because start()
        // and stop() share the one privileged thread: no start() can be waiting on the flag
        // while this runs.
        running = false
        pinger?.interrupt()
        pinger = null

        // Wake the accept thread before closing, or the socket never goes away.
        // LocalServerSocket.close() does NOT interrupt a thread already blocked in
        // accept(), and until that call returns the abstract name stays bound — so
        // the next start() fails with "Address already in use" and the helper can
        // never be restarted without killing the app. MEASURED on hardware
        // 2026-10-12: /proc/net/unix still listed @com.grardb.thorcuts.helper as
        // listening, with the accept thread sleeping, long after stop() returned.
        //
        // Connecting to ourselves unblocks it. The accept returns our throwaway
        // client, serve() refuses it on the uid check that exists for exactly this
        // class of caller, and the loop then sees running == false and exits,
        // releasing the name.
        runCatching {
            LocalSocket().use { it.connect(LocalSocketAddress(SOCKET)) }
        }
        try { server?.close() } catch (_: Throwable) {}
        server = null

        // The helper restores the numbering on its own exit paths, so this is the
        // backstop for when it could not. Gated on the same assessment as the
        // self-heal: restoring unconditionally would cycle the key a second time
        // and blink the controller for nothing.
        //
        // SETTLE first, and this is where it matters most. The helper's own
        // restore returns right after its re-register write and then exits, so EOF
        // reaches us with the pad still absent — we wake within STOP_POLL_MS and,
        // sampling immediately, would see "no pad at all", call it RESTORE and
        // cycle the key again: a second gratuitous 3 s blackout after EVERY normal
        // takeover, whose "pad left renumbered" line would also overwrite the
        // helper's "RESTORE FAILED …" warning in CoordinatorState.helperState's
        // single message slot. That warning is the one this whole task exists to deliver.
        val after = ControllerNumbers.assessAfterSettle()
        Log.i(TAG, "PAD_ASSESS stop action=${after.action} why=${after.why}")
        if (after.action == PadAction.RESTORE) {
            onMessage(HelperMessage.Log("pad left renumbered; restoring"))
            // Same reason as in start(): a restore that never ran must not pass
            // for one that worked.
            if (!ControllerNumbers.restoreNumbering(gateway)) {
                onMessage(HelperMessage.Log(RESTORE_FAILED))
            }
        }
    }

    override fun setCapture(on: Boolean) {
        send(if (on) HelperCommand.ModeCapture else HelperCommand.ModeMatch)
    }

    /**
     * Send a compiled rule table.
     *
     * Call it on [HelperMessage.Ready] and never earlier. The helper's handshake wait
     * reads every line until it sees `H` and DISCARDS the rest, then spends seconds
     * opening devices and running the takeover before it enters the loop that
     * understands `T`. A table pushed on "helper connected" is silently eaten; Ready
     * is written immediately before that loop starts, so a push on Ready is the first
     * thing it reads. Thread-safe like [send], so the reader thread that delivers
     * Ready can call it directly.
     *
     * The supervisor owns the channel, not the policy: WHAT to push and WHEN to
     * recompile is the caller's.
     */
    override fun pushTable(table: CompiledTable) {
        Log.i(TAG, "TABLE_PUSH gen=${table.generation} rules=${table.ruleCount} " +
            "bytes=${table.bytes.size}")
        send(HelperCommand.PushTable(table.generation, table.hex))
    }

    private fun launch() {
        val binary = HelperBinary.path(appContext)
        if (!HelperBinary.exists(appContext)) {
            onMessage(HelperMessage.Log("helper binary missing at $binary"))
            return
        }
        // The trailing `sleep 1` is ESSENTIAL, not cosmetic: pservice runs this via
        // popen and reaps the launching shell as soon as it returns. With a bare `&`
        // the shell exits immediately and the just-forked helper is torn down before
        // it detaches. The sleep keeps the shell alive long enough for the child to
        // reparent to init. Do not use setsid.
        // Report BEFORE running the script, not after. execScript blocks for the
        // `sleep 1` above, by which time the helper has usually already connected
        // and reported "ready, N devices" — so a message emitted afterwards
        // overwrites a newer, truer state with a stale one. That made the UI claim
        // the helper had not started when it was already streaming events.
        onMessage(HelperMessage.Log("launching helper…"))

        // Ask whether a takeover is warranted, and let the HELPER perform it.
        // The decision is cheap and testable here (InputDevice is a public API);
        // the execution belongs in the helper, which runs as uid 0, has the pad's
        // capabilities already in hand, and — unlike this process — cannot be
        // killed by the system midway through the window where the user has no
        // controller at all.
        //
        // SETTLE first. start() may have just run the self-heal's restore, whose
        // second write returns long before AYN's daemon has rebuilt the pad and
        // Android has re-enumerated it. Sampling now would read "no pad at all",
        // i.e. RESTORE, and the run that needed healing would launch the helper
        // WITHOUT the takeover argument — degrading safely, and silently never
        // doing the thing. When no restore ran, the pad is already there and this
        // returns on its first turn at no cost.
        val assessment = ControllerNumbers.assessAfterSettle()
        val takeover = assessment.action == PadAction.TAKE_OVER
        Log.i(TAG, "PAD_ASSESS launch action=${assessment.action} why=${assessment.why}")
        val arg = if (takeover) " takeover" else ""

        val result = gateway.execScript(
            listOf(
                "$binary $SOCKET$arg </dev/null >/dev/null 2>&1 &",
                "sleep 1",
            )
        )

        // Only worth saying if it failed: on success the helper's own "connected"
        // and "ready" messages are both newer and more informative.
        if (result == null) {
            onMessage(HelperMessage.Log("launch failed: no reply from pservice"))
        }
    }

    /** [mine] is the session this thread serves: once a later start() has moved past it,
     *  the fields below belong to that session and this thread only returns. */
    private fun acceptLoop(ss: LocalServerSocket, mine: Int) {
        while (running && mine == session) {
            val client = try {
                ss.accept()
            } catch (t: Throwable) {
                if (running && mine == session) Log.w(TAG, "accept: ${t.message}")
                return
            }
            serve(client, mine)

            if (running && mine == session) {
                // The helper went away without us asking: the panic combo, its
                // watchdog, a crash, a kill. Nothing is going to reconnect, so
                // tear the session down here rather than looping back into
                // accept(). Leaving `running` true made the next Start a SILENT
                // no-op — start() returns at its first guard, before even the
                // self-heal — while this thread sat in accept() holding the
                // abstract name. Measured on hardware 2026-10-12: after a panic
                // combo, tapping Start produced no log line at all and the screen
                // still read "exited: panic combo".
                //
                // Deliberately silent. serve()'s finally has just reported the
                // real reason, and CoordinatorState.helperState is ONE message slot, so
                // anything said here would overwrite "exited: panic combo" with
                // bookkeeping. The user sees why it stopped; Start simply works
                // again.
                if (stopRequested) {
                    Log.i(TAG, "helper exited after Stop; supervisor reset so start() can bind")
                } else {
                    Log.i(TAG, "helper gone unasked; supervisor reset so start() can bind again")
                }
                // The name is released BEFORE the session is declared over: a start() woken
                // by `running` going false (a relaunch's) binds the same name and must not
                // race the close. The pinger is interrupted rather than left to notice the
                // flag — it may not within its one-second sleep, and the relaunch sets the
                // flag true again; left alone, one more pinger per relaunch would outlive it.
                // (A thread whose session a later start() has moved past never gets here:
                // `server`, `pinger` and `running` are that session's — the guard above.)
                try { ss.close() } catch (_: Throwable) {}
                server = null
                pinger?.interrupt()
                pinger = null
                running = false
                return
            }
        }
    }

    /** [mine] as in [acceptLoop]: a line read after a later start() has moved past this
     *  session is dropped, never delivered — the coordinator hears one session at a time —
     *  and the teardown below leaves the new session's fields alone. */
    private fun serve(client: LocalSocket, mine: Int) {
        // Abstract sockets have NO file permissions, so this check is the only thing
        // standing between our channel and any other app on the device. Without it,
        // anything installed here could connect and inject controller events.
        val uid = runCatching { client.peerCredentials.uid }.getOrDefault(-1)
        if (uid != 0) {
            Log.w(TAG, "refused a connection from uid $uid")
            runCatching { client.close() }
            return
        }

        connected = true
        out = client.outputStream
        send(HelperCommand.Handshake)
        onMessage(HelperMessage.Log("helper connected"))

        // Whether this connection told us why it was going away. When it did, that
        // reason is strictly more informative than the generic disconnect message
        // below, so the generic one must not overwrite it. When it didn't (a crash,
        // a kill), the absence of a reason is itself the diagnostic, so the generic
        // message still needs to fire.
        var sawExit = false
        try {
            val reader = client.inputStream.bufferedReader()
            while (running && mine == session) {
                val line = reader.readLine() ?: break
                if (mine != session) {
                    Log.i(TAG, "stale session $mine: dropped ${line.take(1)}")
                    break
                }
                val message = HelperProtocol.parse(line)
                if (message == null) {
                    Log.w(TAG, "unparsed line: $line")
                    continue
                }
                if (message is HelperMessage.Exiting) sawExit = true
                onMessage(message)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "read: ${t.message}")
        } finally {
            if (mine == session) {
                connected = false
                out = null
            }
            runCatching { client.close() }
            if (mine == session && !sawExit) onMessage(HelperMessage.Log(DISCONNECTED))
        }
    }

    /** [mine] as in [acceptLoop]: a pinger whose session has been moved past returns (the
     *  relaunch review, R1-3); the interrupt on teardown is what ends it promptly. */
    private fun pingLoop(mine: Int) {
        while (running && mine == session) {
            if (connected) send(HelperCommand.Ping)
            try {
                Thread.sleep(PING_INTERVAL_MS)
            } catch (_: InterruptedException) {
                return
            }
        }
    }

    private fun send(command: HelperCommand) {
        val stream = out ?: run {
            Log.w(TAG, "send ${command.wire.take(40)} dropped: no helper connected")
            return
        }
        val line = HelperProtocol.encode(command)
        try {
            synchronized(this) {
                stream.write((line + '\n').toByteArray())
                stream.flush()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "send $line: ${t.message}")
        }
    }

    companion object {
        private const val TAG = "ThorcutsHelper"
        private const val SOCKET = "com.grardb.thorcuts.helper"
        /** Three pings inside the helper's 3s watchdog window. */
        private const val PING_INTERVAL_MS = 1000L

        /**
         * How long stop() waits for the helper to disconnect. Its teardown
         * restore sleeps 3s between two settings writes and each write is a
         * pservice round trip, so this is that plus generous headroom. Erring
         * long costs a slower Stop; erring short loses the helper's final
         * message and un-serialises a restart against the teardown.
         */
        private const val STOP_WAIT_MS = 8000L
        private const val STOP_POLL_MS = 50L

        /**
         * How long start() waits for a dying session to close its socket before answering
         * ALREADY_RUNNING. The helper's teardown restore is the same 3 s cycle stop() waits
         * for, so the same budget with the same headroom.
         */
        private const val SESSION_WAIT_MS = 8000L

        /**
         * What to say when restoreNumbering() reports that pservice never ran the
         * cycle. Worded like the helper's own TC_TAKEOVER_RESTORE_FAILED line, in
         * native/src/takeover.c, because the user's situation is identical.
         */
        private const val RESTORE_FAILED =
            "RESTORE FAILED — pservice did not run the cycle and the pad may still " +
                "be deregistered. Use AYN's Controller style tile, or reboot."

        /**
         * The Log text serve() emits when the helper went away without saying why. Public
         * so the UI can recognise "no helper, no table" without matching free text.
         */
        const val DISCONNECTED = "helper disconnected"
    }
}
