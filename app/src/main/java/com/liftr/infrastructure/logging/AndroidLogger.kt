package com.liftr.infrastructure.logging

import android.util.Log
import com.liftr.domain.logging.Logger
import javax.inject.Inject

/**
 * Android implementation of the Logger interface.
 *
 * Delegates logging calls to android.util.Log.
 */
class AndroidLogger @Inject constructor() : Logger {

    override fun d(tag: String, message: String) {
        Log.d(tag, message)
    }

    override fun i(tag: String, message: String) {
        Log.i(tag, message)
    }

    override fun w(tag: String, message: String) {
        Log.w(tag, message)
    }

    override fun e(tag: String, message: String, throwable: Throwable?) {
        if (throwable != null) {
            Log.e(tag, message, throwable)
        } else {
            Log.e(tag, message)
        }
    }
}
