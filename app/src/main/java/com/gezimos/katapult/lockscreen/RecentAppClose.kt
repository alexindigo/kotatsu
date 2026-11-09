package com.gezimos.katapult.lockscreen

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo

/** Owns the bounded Home transition before a foreground app's background-stop request. */
internal class RecentAppClose(private val service: AccessibilityService, private val handler: Handler) : AutoCloseable {
    private var pending: (() -> Unit)? = null
    private var home: String? = null
    private val delivery = AndroidEventDelivery.scoped(service)
    private var lease: Long? = null
    private val timeout = Runnable {
        android.util.Log.w("RecentAppClose", "Home observation timed out; requesting background stop")
        finish()
    }

    fun start(stopped: () -> Unit) {
        val previous = pending
        if (previous != null) {
            pending = { previous(); stopped() }
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
            confirm()
            return
        }
        home = service.packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
            PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
        pending = stopped
        handler.postDelayed(timeout, 1500L)
        try { lease = delivery.begin() }
        catch (error: RuntimeException) { android.util.Log.w("RecentAppClose", "Home observation unavailable", error) }
        service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
        // Also handles opening Recent apps while already on Home.
        confirm()
    }

    fun event(event: AccessibilityEvent?) {
        if (pending != null && event?.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED) handler.post { confirm() }
    }

    private fun confirm() {
        if (pending == null || home == null) return
        val windows = service.windows
        val reachedHome = try {
            windows.any { window ->
                if (window.type != AccessibilityWindowInfo.TYPE_APPLICATION || !window.isActive) false
                else {
                    val root = window.root
                    try { root?.packageName?.toString() == home }
                    finally { @Suppress("DEPRECATION") root?.recycle() }
                }
            }
        } finally { @Suppress("DEPRECATION") windows.forEach { it.recycle() } }
        if (reachedHome) {
            android.util.Log.d("RecentAppClose", "Home observed before background stop")
            finish()
        }
    }

    private fun finish() {
        val action = pending
        pending = null
        handler.removeCallbacks(timeout)
        lease?.let(delivery::end)
        lease = null
        action?.invoke()
    }

    override fun close() {
        pending = null
        handler.removeCallbacks(timeout)
        lease?.let(delivery::end)
        lease = null
    }
}
