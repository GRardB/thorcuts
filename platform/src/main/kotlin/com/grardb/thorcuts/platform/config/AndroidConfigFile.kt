package com.grardb.thorcuts.platform.config

import android.content.Context
import androidx.core.util.AtomicFile
import com.grardb.thorcuts.core.config.ConfigFile
import java.io.File
import java.io.IOException

/**
 * The one app-private config file, `filesDir/config.json`, through AtomicFile: a write goes
 * to a sibling and is renamed over the original, so a reader sees the old text or the new,
 * never a torn one. [setAside] renames an unreadable file to `config.json.bad-<millis>`
 * rather than deleting it — the user's text is never destroyed, by the config spec.
 *
 * No host test: AtomicFile is Android, and there is no logic here to test. The device run
 * exercises all three methods.
 */
class AndroidConfigFile(context: Context) : ConfigFile {

    private val file = File(context.filesDir, NAME)
    private val atomic = AtomicFile(file)

    override fun read(): String? {
        if (!file.exists()) return null
        return atomic.readFully().toString(Charsets.UTF_8)
    }

    override fun write(text: String) {
        val out = try {
            atomic.startWrite()
        } catch (t: Throwable) {
            throw t.asIOException()
        }
        try {
            out.write(text.toByteArray(Charsets.UTF_8))
            atomic.finishWrite(out)
        } catch (t: Throwable) {
            atomic.failWrite(out)
            throw t.asIOException()
        }
    }

    /** [ConfigFile.write]'s contract is IOException and only that; AtomicFile can also throw
     *  a SecurityException or an IllegalStateException, which the store would not catch and
     *  which would then escape `update()` into its caller. Wrapped here, once, so the store's
     *  one catch is the whole story. */
    private fun Throwable.asIOException(): IOException =
        this as? IOException ?: IOException("could not write $NAME: $this", this)

    override fun setAside(): String? {
        if (!file.exists()) return null
        val target = File(file.parentFile, "$NAME.bad-${System.currentTimeMillis()}")
        if (!file.renameTo(target)) throw IOException("could not move $NAME to ${target.name}")
        return target.name
    }

    companion object {
        const val NAME = "config.json"
    }
}
