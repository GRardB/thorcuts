package com.grardb.thorcuts.platform.tasks

/** The PNG signature: the eight bytes every PNG begins with (ISO/IEC 15948 §5.2). */
private val PNG_SIGNATURE =
    byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

/**
 * Whether [bytes] begins like a PNG. The one check `GalleryScreenshots` makes before it
 * files a frame the root script left behind: `screencap` writes a whole PNG or nothing,
 * so a wrong header means the script did not do what it said. Pure; host-tested.
 */
fun isPng(bytes: ByteArray): Boolean =
    bytes.size >= PNG_SIGNATURE.size && PNG_SIGNATURE.indices.all { bytes[it] == PNG_SIGNATURE[it] }
