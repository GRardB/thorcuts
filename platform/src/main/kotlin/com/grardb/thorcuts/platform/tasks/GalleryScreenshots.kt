package com.grardb.thorcuts.platform.tasks

import android.content.ContentValues
import android.content.Context
import android.media.MediaActionSound
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.widget.Toast
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The real [Screenshots]: the frame in the app's cache dir, the save through MediaStore
 * (the app's own image, so no permission on API 29+), the toast on the main looper and the
 * shutter click. THE ANDROID BOUNDARY — not host-tested; the M7 device run checks it: the
 * file appears in Photos' Screenshots album, the toast reads `Screenshot saved`.
 */
class GalleryScreenshots(private val context: Context) : Screenshots {

    override val frame: File = File(context.cacheDir, "screenshot.png")

    private val main = Handler(Looper.getMainLooper())
    private val sound: MediaActionSound by lazy {
        MediaActionSound().apply { load(MediaActionSound.SHUTTER_CLICK) }
    }

    override fun save(): String {
        val err = File(frame.path + ".err")
        try {
            val bytes = try {
                frame.readBytes()
            } catch (e: IOException) {
                return "ERR frame: ${e.message ?: e.javaClass.simpleName}"
            }
            if (!isPng(bytes)) return "ERR frame: not a PNG (${bytes.size} bytes)"
            val name = "Screenshot_${TIMESTAMP.format(Date())}_bottom.png"
            val resolver = context.contentResolver
            val collection =
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val pending = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, ALBUM)
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = try {
                resolver.insert(collection, pending) ?: return "ERR gallery: insert refused"
            } catch (e: RuntimeException) {
                return "ERR gallery: $e"
            }
            try {
                (resolver.openOutputStream(uri) ?: throw IOException("no output stream"))
                    .use { it.write(bytes) }
                resolver.update(
                    uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) },
                    null, null,
                )
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                return "ERR gallery: $e"
            }
            return "OK saved $ALBUM/$name"
        } finally {
            frame.delete()
            err.delete()
        }
    }

    override fun notify(line: String) {
        val ok = line.startsWith("OK ")
        val text = if (ok) "Screenshot saved" else "Screenshot failed: ${line.removePrefix("ERR ")}"
        main.post {
            Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
            if (ok) sound.play(MediaActionSound.SHUTTER_CLICK)
        }
    }

    private companion object {
        const val ALBUM = "Pictures/Screenshots"
        val TIMESTAMP = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
    }
}
