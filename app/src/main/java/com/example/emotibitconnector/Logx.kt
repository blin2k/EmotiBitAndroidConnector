package com.example.emotibitconnector

import android.util.Log

/** Centralized logging helper for consistent tag usage. */
object Logx {
    const val TAG: String = "EmotiBit"

    fun i(msg: String) {
        Log.i(TAG, msg)
    }

    fun w(msg: String, tr: Throwable? = null) {
        Log.w(TAG, msg, tr)
    }

    fun e(msg: String, tr: Throwable? = null) {
        Log.e(TAG, msg, tr)
    }
}
