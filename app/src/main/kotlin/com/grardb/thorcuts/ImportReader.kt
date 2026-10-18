package com.grardb.thorcuts

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

/**
 * The import's bounded read: [input] as UTF-8, up to [maxBytes]; a leading byte-order mark is
 * then removed. One byte more is an IOException naming the limit, so a mis-picked file cannot
 * load gigabytes into memory before the decoder sees it is not JSON. Read through a small
 * chunk into a buffer that grows with the file, so a 64-rule config (tens of KB) costs tens
 * of KB, not the limit. java.io only, so the host tests it; `readNBytes` would do the same
 * but is API 33, and minSdk is 30.
 */
object ImportReader {
    fun read(input: InputStream, maxBytes: Int): String {
        val out = ByteArrayOutputStream()
        val chunk = ByteArray(CHUNK)
        while (out.size() <= maxBytes) {
            // Never ask for more than the one byte past the limit that proves it is crossed.
            val r = input.read(chunk, 0, minOf(chunk.size, maxBytes + 1 - out.size()))
            // 0 for a non-empty request breaks InputStream's contract; stop rather than spin.
            if (r <= 0) break
            out.write(chunk, 0, r)
        }
        if (out.size() > maxBytes) throw IOException("the file is larger than $maxBytes bytes")
        // A Windows editor or a chat client may have prefixed the app's own export with a BOM;
        // the parser would refuse it at offset 0.
        return out.toByteArray().toString(Charsets.UTF_8).removePrefix(BOM)
    }

    private const val CHUNK = 8 * 1024
    private const val BOM = "\uFEFF"
}
