package com.gezimos.katapult.lockscreen

import android.content.Context

/** One durable identity for a screen-on epoch, shared by every hosted surface. */
internal class HostedWidgetWake(context: Context) {
    private val prefs = context.createDeviceProtectedStorageContext().getSharedPreferences("hosted-widget-wake", 0)

    fun current(): Long = synchronized(prefs) { prefs.getLong("counter", 0) }

    // Only SCREEN_ON calls this. Service creation, visibility and sizing merely read current().
    fun begin(): Long = synchronized(prefs) {
        val next = Math.addExact(prefs.getLong("counter", 0), 1)
        check(prefs.edit().putLong("counter", next).commit()) { "Could not retain widget wake identity" }
        next
    }
}
