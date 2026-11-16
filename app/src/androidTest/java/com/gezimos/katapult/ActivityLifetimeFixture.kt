package com.gezimos.katapult

import android.app.Activity
import android.os.Looper
import android.os.SystemClock
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleCallback
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage

/** Observe only this scope's creations, including indirect launches and recreated instances. */
internal class ActivityLifetimeFixture : AutoCloseable {
    private val monitor = ActivityLifecycleMonitorRegistry.getInstance()
    private val activities = linkedSetOf<Activity>()
    private var closed = false
    private val callback = ActivityLifecycleCallback { activity, stage ->
        if (stage == Stage.PRE_ON_CREATE || stage == Stage.CREATED) activities.add(activity)
    }

    init { fixtureMain { monitor.addLifecycleCallback(callback) } }

    fun finishAndAwaitDestroyed(activity: Activity) {
        check(Looper.myLooper() != Looper.getMainLooper()) { "Destruction must be awaited off main" }
        fixtureMain { activities.add(activity); if (!activity.isDestroyed) activity.finish() }
        fixtureAwait("Activity did not destroy: ${activity.javaClass.name}") {
            fixtureMain { activity.isDestroyed && (activity !is LifecycleOwner || activity.lifecycle.currentState == Lifecycle.State.DESTROYED) }
        }
        fixtureMain { check(activity.isDestroyed) }
    }

    fun closeActivities() {
        check(Looper.myLooper() != Looper.getMainLooper())
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        // Destruction can launch another owned Activity (for example a pending result).
        fixtureAwait("Owned Activities survived teardown") {
            val live = fixtureMain { activities.filterNot { it.isDestroyed } }
            live.forEach(::finishAndAwaitDestroyed)
            fixtureMain { activities.all { it.isDestroyed } }
        }
    }

    fun <A : Activity> closeAndAwaitDestroyed(scenario: ActivityScenario<A>) {
        scenario.close()
        closeActivities()
    }

    override fun close() {
        if (closed) return
        try { closeActivities() } finally {
            fixtureMain { monitor.removeLifecycleCallback(callback) }
            closed = true
        }
    }
}

internal fun <T> fixtureMain(action: () -> T): T {
    if (Looper.myLooper() == Looper.getMainLooper()) return action()
    var result: Result<T>? = null
    InstrumentationRegistry.getInstrumentation().runOnMainSync { result = runCatching(action) }
    return checkNotNull(result).getOrThrow()
}

internal fun fixtureAwait(message: String, ready: () -> Boolean) {
    val deadline = SystemClock.elapsedRealtime() + 15_000
    while (SystemClock.elapsedRealtime() < deadline) {
        if (ready()) return
        SystemClock.sleep(25)
    }
    throw AssertionError(message)
}

/** Attempt every restoration without losing the primary exception. */
internal fun fixtureCleanup(vararg actions: () -> Unit) {
    var failure: Throwable? = null
    actions.forEach { action ->
        try { action() } catch (error: Throwable) {
            if (failure == null) failure = error else failure!!.addSuppressed(error)
        }
    }
    failure?.let { throw it }
}
