package com.bitchat.android

import android.os.SystemClock
import android.util.Log

/**
 * Tiny cold-start profiler: one log line per phase with the duration of each step, so slow
 * steps on low-end phones show up in `adb logcat -s NemixStartup`. Contains no user data.
 */
internal object StartupTiming {
    private const val TAG = "NemixStartup"
    private var started = 0L
    private var last = 0L
    private val steps = ArrayList<Long>(24)

    fun begin() {
        started = SystemClock.elapsedRealtime()
        last = started
        steps.clear()
    }

    fun mark() {
        val now = SystemClock.elapsedRealtime()
        steps += now - last
        last = now
    }

    fun end(label: String) {
        val total = SystemClock.elapsedRealtime() - started
        Log.i(TAG, "$label ${total}ms steps=${steps.joinToString(",")}")
    }
}
