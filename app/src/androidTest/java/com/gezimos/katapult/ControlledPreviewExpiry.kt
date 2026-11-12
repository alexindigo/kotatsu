package com.gezimos.katapult

import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.SystemClock

/** Injected only into capture fixtures: park expiry, while immediate appearance delivery stays real. */
internal class ControlledPreviewExpiry : Handler(Looper.getMainLooper()), AutoCloseable {
    private var expiration: Runnable? = null
    override fun sendMessageAtTime(message: Message, uptimeMillis: Long): Boolean {
        if (message.callback != null && uptimeMillis > SystemClock.uptimeMillis()) {
            expiration = message.callback
            return super.sendMessageAtTime(message, Long.MAX_VALUE)
        }
        return super.sendMessageAtTime(message, uptimeMillis)
    }
    fun expireNow() {
        val action = checkNotNull(expiration)
        expiration = null
        removeCallbacks(action)
        post(action)
    }
    override fun close() { removeCallbacksAndMessages(null); expiration = null }
}
