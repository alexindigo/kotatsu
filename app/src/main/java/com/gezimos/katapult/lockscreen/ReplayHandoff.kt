package com.gezimos.katapult.lockscreen

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Handler
import android.os.Parcel
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import com.gezimos.katapult.R
import org.xmlpull.v1.XmlPullParser

/** Owns the entire detach -> acknowledgement -> injection -> completion transaction. */
internal class ReplayHandoff(
    private val service: AccessibilityService,
    private val handler: Handler,
    private val eligible: () -> Boolean,
    private val finished: () -> Unit,
) {
    private class Pending(val token: Long, val path: Path, val duration: Long, val views: List<View>,
                          val ids: Set<Int>, val titles: Set<String>) {
        val started = SystemClock.uptimeMillis()
        var removed = false
        lateinit var timeout: Runnable
    }
    private var pending: Pending? = null
    private val delivery = AndroidEventDelivery.scoped(service)
    private var lease: Long? = null
    val busy get() = lease != null

    fun begin(path: Path, duration: Long, owned: List<Pair<View, String>>, detach: () -> Unit) {
        if (busy || !eligible()) return
        try {
            val ids = owned.map { (view, _) ->
                val node = view.createAccessibilityNodeInfo()
                try { node.windowId } finally { @Suppress("DEPRECATION") node.recycle() }
            }.toSet()
            val titles = owned.map { it.second }.toSet()
            val windows = service.windows
            val observed = try {
                windows.filter { it.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY && it.title?.toString() in titles }
                    .map { it.id to it.title?.toString() }
            } finally { @Suppress("DEPRECATION") windows.forEach { it.recycle() } }
            if (owned.isEmpty() || ids.size != owned.size || ids.any { it < 0 } ||
                observed.size != owned.size || observed.map { it.first }.toSet() != ids ||
                observed.map { it.second }.toSet() != titles) {
                Log.w(TAG, "Replay dropped: current windows not observed")
                detach()
                return
            }
            val token = delivery.begin()
            lease = token
            val next = Pending(token, Path(path), EdgeSwipe.duration(duration), owned.map { it.first }, ids, titles)
            pending = next
            next.timeout = Runnable { if (pending === next) { cancel("removal timeout"); finished() } }
            handler.postDelayed(next.timeout, 1500L)
            detach()
        } catch (error: RuntimeException) {
            cancel("capture failed")
            detach()
            Log.w(TAG, "Replay dropped", error)
        }
    }

    fun event(event: AccessibilityEvent?) {
        val next = pending ?: return
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOWS_CHANGED) return
        if (event.windowId in next.ids && event.windowChanges and AccessibilityEvent.WINDOWS_CHANGE_REMOVED != 0)
            next.removed = true
        handler.post { confirm(next) }
    }

    private fun confirm(next: Pending) {
        if (pending !== next) return
        if (SystemClock.uptimeMillis() - next.started >= 1500L || !eligible()) {
            cancel("expired or ineligible"); finished(); return
        }
        try {
            val windows = service.windows
            val snapshot = try { windows.map { it.id to it.title?.toString() } }
            finally { @Suppress("DEPRECATION") windows.forEach { it.recycle() } }
            if (!ReplayBarrier.ready(next.ids, next.titles, next.removed,
                    next.views.none { it.isAttachedToWindow }, snapshot)) return
            if (pending !== next || !eligible()) return
            pending = null
            handler.removeCallbacks(next.timeout)
            check(delivery.dispatched(next.token)) { "Could not restore event delivery before dispatch" }
            val token = next.token
            val gesture = GestureDescription.Builder().addStroke(
                GestureDescription.StrokeDescription(next.path, 0, next.duration)).build()
            val callback = object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription) = complete(token)
                override fun onCancelled(gestureDescription: GestureDescription) = complete(token)
            }
            if (!service.dispatchGesture(gesture, callback, handler)) complete(token)
        } catch (error: RuntimeException) {
            cancel("dispatch failed"); Log.w(TAG, "Replay dropped", error); finished()
        }
    }

    private fun complete(token: Long) {
        if (!delivery.current(token)) return
        delivery.end(token)
        if (lease == token) lease = null
        finished()
    }

    fun cancel(reason: String) {
        if (busy) Log.w(TAG, "Replay dropped: $reason")
        pending?.let { handler.removeCallbacks(it.timeout) }
        pending = null
        lease?.let(delivery::end)
        lease = null
    }

    companion object { private const val TAG = "ReplayHandoff" }
}
