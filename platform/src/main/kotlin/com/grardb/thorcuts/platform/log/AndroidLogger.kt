package com.grardb.thorcuts.platform.log

import android.util.Log

/** [Logger] over `android.util.Log` under one [tag]. */
class AndroidLogger(private val tag: String) : Logger {
    override fun i(text: String) {
        Log.i(tag, text)
    }

    override fun w(text: String) {
        Log.w(tag, text)
    }

    override fun e(text: String, error: Throwable) {
        Log.e(tag, text, error)
    }
}
