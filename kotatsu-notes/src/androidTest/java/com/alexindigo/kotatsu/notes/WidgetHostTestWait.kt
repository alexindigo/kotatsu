package com.alexindigo.kotatsu.notes

import android.os.SystemClock
import org.junit.Assert.assertTrue

/** A readiness observation can be transient (e.g. no layout requested between two publications). */
internal fun awaitWidgetHost(message: String, ready: () -> Boolean) {
    val end = SystemClock.elapsedRealtime() + 20_000
    var observed = ready()
    while (!observed && SystemClock.elapsedRealtime() < end) {
        Thread.sleep(40)
        observed = ready()
    }
    assertTrue(message, observed)
}
