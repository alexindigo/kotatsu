package com.gezimos.katapult.lockscreen

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.gezimos.katapult.util.AccessibilityPolicy

/** Owns node acquisition/recycling and the lifetime of the freshly queried SystemUI root. */
@Suppress("DEPRECATION")
internal class AndroidLockscreenInspection(private val service: AccessibilityService) : AutoCloseable {
    private val inspection = LockscreenRootInspection<AccessibilityNodeInfo>(
        copy = { AccessibilityNodeInfo.obtain(it) }, refresh = { it.refresh() },
        inspect = { root ->
            fun visibleId(id: String): Boolean {
                val nodes = root.findAccessibilityNodeInfosByViewId(id).orEmpty()
                return try { nodes.any { it.isVisibleToUser } } finally { nodes.forEach { it.recycle() } }
            }
            LockscreenRootInspection.Observation(root.packageName?.toString() == "com.android.systemui",
                visibleId(AccessibilityPolicy.LOCKSCREEN_PANEL_ID), AccessibilityPolicy.PIN_VIEW_IDS.any(::visibleId))
        }, release = { it.recycle() },
    )

    fun covered(): Boolean {
        val windows = mutableListOf<AccessibilityWindowInfo>()
        val roots = mutableListOf<AccessibilityNodeInfo>()
        return try {
            val focus = service.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            val pinFocused = try {
                focus != null && AccessibilityPolicy.isPinField(focus.packageName, focus.isVisibleToUser,
                    focus.className, focus.viewIdResourceName)
            } finally { focus?.recycle() }
            windows += service.windows
            val snapshot = windows.map { window ->
                val root = window.root?.also { roots += it }
                LockscreenRootInspection.Window(root,
                    window.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY && root?.packageName?.toString() == service.packageName,
                    window.isActive, window.isFocused, window.type == AccessibilityWindowInfo.TYPE_APPLICATION)
            }
            inspection.covered(snapshot, pinFocused)
        } catch (_: RuntimeException) { close(); true }
        finally { roots.forEach { it.recycle() }; windows.forEach { it.recycle() } }
    }

    override fun close() = inspection.close()
}
