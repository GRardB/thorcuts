package com.grardb.thorcuts.platform.helper

import android.content.Context
import java.io.File

/**
 * Where the helper ends up on disk.
 *
 * It ships as jniLibs/arm64-v8a/libthorcutsd.so and, because :app sets
 * useLegacyPackaging=true, Android extracts it into nativeLibraryDir — a path
 * that permits exec, unlike anything the app can write to itself.
 */
object HelperBinary {

    fun path(context: Context): String =
        File(context.applicationInfo.nativeLibraryDir, "libthorcutsd.so").absolutePath

    fun exists(context: Context): Boolean = File(path(context)).canRead()
}
