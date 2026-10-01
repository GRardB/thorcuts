package com.grardb.thorcuts.platform.helper

import android.content.Context
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.util.Log
import com.grardb.thorcuts.core.helper.HelperCommand
import com.grardb.thorcuts.core.helper.HelperMessage
import com.grardb.thorcuts.core.helper.HelperProtocol
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

    fun stop() {
        running = false
        send(HelperCommand.Stop)
        try { server?.close() } catch (_: Throwable) {}
        server = null
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

        val result = gateway.execScript(
            listOf(
                "$binary $SOCKET </dev/null >/dev/null 2>&1 &",
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
    }
}
