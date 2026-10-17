package com.grardb.thorcuts

import java.io.IOException
import java.io.InputStream

/**
 * The import's bounded read: [input] as UTF-8, up to [maxBytes]; a leading byte-order mark is
 * then removed. One byte more is an IOException naming the limit, so a mis-picked file cannot
 * load gigabytes into memory before the decoder sees it is not JSON. java.io only, so the host
 * tests it; `readNBytes` would do the same but is API 33, and minSdk is 30.
 */
object ImportReader {
    fun read(input: InputStream, maxBytes: Int): String {
        val buf = ByteArray(maxBytes + 1)
        var n = 0
        while (n < buf.size) {
            val r = input.read(buf, n, buf.size - n)
            // 0 for a non-empty request breaks InputStream's contract; stop rather than spin.
            if (r <= 0) break
            n += r
        }
        if (n > maxBytes) throw IOException("the file is larger than $maxBytes bytes")
        // A Windows editor or a chat client may have prefixed the app's own export with a BOM;
        // the parser would refuse it at offset 0.
        return String(buf, 0, n, Charsets.UTF_8).removePrefix(BOM)
    }

    private const val BOM = "\uFEFF"
}
