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
import com.grardb.thorcuts.platform.pad.ControllerNumbers
import com.grardb.thorcuts.platform.privilege.PserviceGateway
import java.io.OutputStream

/**
 * Owns the app's side of the helper channel: hosts the socket, launches the
 * helper as uid 0, pings it, and reads what it says.
 */
class HelperSupervisor(
    private val appContext: Context,
    private val gateway: PserviceGateway,
    private val onMessage: (HelperMessage) -> Unit,
) {

    @Volatile var connected: Boolean = false
        private set

    @Volatile private var running = false
    @Volatile private var server: LocalServerSocket? = null
    @Volatile private var out: OutputStream? = null

    /** Bind the socket, then launch the helper. Call on Privilege.dispatcher. */
    fun start() {
        if (running) return
        running = true

        // Self-heal. This is the layer that covers a SIGKILL, a crash, or the app
        // being swiped from recents — the cases where neither the helper nor a
        // clean stop could run. A pad sitting at 2 with no twin of ours to justify
        // it is exactly the condition that breaks Dolphin, and the user has no way
        // to connect the two facts.
        //
        // Acts ONLY on RESTORE. TAKE_OVER is deliberately ignored here: assess()
        // describes what the numbering needs, not what we intend, and taking the
        // controller over merely because the app opened would be wrong.
        //
        // RESTORE now also covers "no Thor pad registered AT ALL" — the state a
        // crash midway through the helper's takeover leaves behind, and the state
        // this layer was always claimed to cover. It is safe here specifically
        // because this runs BEFORE launch(): the window in which the pad is
        // legitimately absent has not opened yet.
        //
        // The PLAIN assess() is correct here, and only here: nothing in this
        // process has written the trigger key yet, so there is no re-enumeration
        // in flight to wait out. Every sample taken AFTER a write of ours goes
        // through assessAfterSettle() instead.
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
            return
        }
        server = ss

        Thread({ acceptLoop(ss) }, "thorcuts-helper-accept").apply { isDaemon = true }.start()
        Thread(::pingLoop, "thorcuts-helper-ping").apply { isDaemon = true }.start()

        launch()
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
     * Call on Privilege.dispatcher, as DiagnosticsViewModel already does through
     * privileged{}.
     */
    fun stop() {
        // Nothing to stop. Without this an idle supervisor still assessed the pad
        // and could run a full 3 s restore cycle — blinking out a controller this
        // object has never touched — on nothing more than a stray Stop tap.
        //
        // `connected` is in the test too, not just `running`: after a stop() that
        // timed out, running is already false while the helper may still be on the
        // socket, and a second Stop has to be able to reach it.
        if (!running && !connected) return

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

        running = false

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
        // helper's "RESTORE FAILED …" warning in DiagnosticsViewModel's single
        // message slot. That warning is the one this whole task exists to deliver.
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

    fun setCaptureMode(capture: Boolean) {
        send(if (capture) HelperCommand.ModeCapture else HelperCommand.ModeMatch)
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

    private fun acceptLoop(ss: LocalServerSocket) {
        while (running) {
            val client = try {
                ss.accept()
            } catch (t: Throwable) {
                if (running) Log.w(TAG, "accept: ${t.message}")
                return
            }
            serve(client)
        }
    }

    private fun serve(client: LocalSocket) {
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
            while (running) {
                val line = reader.readLine() ?: break
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
            connected = false
            out = null
            runCatching { client.close() }
            if (!sawExit) onMessage(HelperMessage.Log("helper disconnected"))
        }
    }

    private fun pingLoop() {
        while (running) {
            if (connected) send(HelperCommand.Ping)
            try {
                Thread.sleep(PING_INTERVAL_MS)
            } catch (_: InterruptedException) {
                return
            }
        }
    }

    private fun send(command: HelperCommand) {
        val stream = out ?: return
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

    private companion object {
        const val TAG = "ThorcutsHelper"
        const val SOCKET = "com.grardb.thorcuts.helper"
        /** Three pings inside the helper's 3s watchdog window. */
        const val PING_INTERVAL_MS = 1000L

        /**
         * How long stop() waits for the helper to disconnect. Its teardown
         * restore sleeps 3s between two settings writes and each write is a
         * pservice round trip, so this is that plus generous headroom. Erring
         * long costs a slower Stop; erring short loses the helper's final
         * message and un-serialises a restart against the teardown.
         */
        const val STOP_WAIT_MS = 8000L
        const val STOP_POLL_MS = 50L

        /**
         * What to say when restoreNumbering() reports that pservice never ran the
         * cycle. Worded like the helper's own TC_TAKEOVER_RESTORE_FAILED line, in
         * native/src/takeover.c, because the user's situation is identical.
         */
        const val RESTORE_FAILED =
            "RESTORE FAILED — pservice did not run the cycle and the pad may still " +
                "be deregistered. Use AYN's Controller style tile, or reboot."
    }
}
