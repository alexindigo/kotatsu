package com.gezimos.katapult

import android.app.Instrumentation
import android.app.UiAutomation
import android.graphics.Rect
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.accessibilityservice.AccessibilityServiceInfo

/** Test-only public UI input; no adopted shell permissions or production hooks. */
class PlatformUiDriver(instrumentation: Instrumentation) {
    private val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)

    private fun windows(): List<AccessibilityWindowInfo> {
        // Another test helper can reconnect UiAutomation via its default getter, resetting service flags.
        // Keep the observation capability with the driver that needs it, including after that reconnect.
        val service = automation.serviceInfo
        if (service.flags and AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS == 0) {
            service.flags = service.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            automation.serviceInfo = service
        }
        return automation.windows
    }

    fun await(message: String, timeoutMs: Long = 8000, predicate: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < end) {
            if (predicate()) return
            SystemClock.sleep(80)
        }
        throw AssertionError(message)
    }

    fun find(label: String): AccessibilityNodeInfo? = find {
        it.text?.toString() == label || it.contentDescription?.toString() == label
    }

    /** Accessibility events can leave cached children at an older layout/text revision. */
    fun find(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        val root = focusedRoot() ?: return null
        fun walk(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (!node.refresh()) return null
            if (predicate(node)) return node
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                walk(child)?.let { return it }
            }
            return null
        }
        return walk(root)
    }

    /** Input goes to the focused owner, including system dialogs such as the fullscreen hint. */
    private fun focusedRoot(): AccessibilityNodeInfo? = windows().singleOrNull {
        it.isFocused && it.isActive
    }?.root?.takeIf { it.refresh() }

    /** A model callback can precede dialog disposal. Observe the input destination before the next action. */
    fun awaitFocusedWindow(windowId: Int) {
        await("Window $windowId did not regain input focus") {
            focusedRoot()?.let { it.windowId == windowId && it.childCount > 0 } == true
        }
    }

    fun has(label: String): Boolean = find(label) != null

    /**
     * Returns the matching node or its closest matching ancestor, freshly queried.
     * refresh() bypasses Android's cache without updating it, so parent lookups
     * must refresh their own result even after a fresh downward traversal.
     */
    fun findAncestor(label: String, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        var node = find(label)
        while (node != null) {
            if (!node.refresh()) return null
            if (predicate(node)) return node
            node = node.parent
        }
        return null
    }

    fun checked(label: String): Boolean? = findAncestor(label) { it.isCheckable }?.isChecked

    fun scrollTo(label: String) {
        repeat(12) {
            if (has(label)) return
            val root = Rect()
            focusedRoot()!!.getBoundsInScreen(root)
            val x = root.centerX().toFloat()
            val start = root.top + root.height() * 3 / 4f
            val end = root.top + root.height() / 3f
            val down = SystemClock.uptimeMillis()
            for (step in 0..15) {
                val action = when (step) { 0 -> MotionEvent.ACTION_DOWN; 15 -> MotionEvent.ACTION_UP; else -> MotionEvent.ACTION_MOVE }
                val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, start + (end - start) * step / 15f, 0)
                event.source = InputDevice.SOURCE_TOUCHSCREEN
                check(automation.injectInputEvent(event, true)); event.recycle(); SystemClock.sleep(20)
            }
        }
        throw AssertionError("Public scroll did not reveal $label")
    }

    fun click(label: String, longPress: Boolean = false) {
        var rect = Rect()
        var window = -1
        var stable = 0
        // Presence is not touch readiness: scrolling and IME insets move controls
        // after their semantics appear. Observe fresh geometry; inject only once.
        await("Public control did not settle: $label") {
            val node = find(label)
            val current = Rect()
            node?.getBoundsInScreen(current)
            stable = if (node?.isVisibleToUser == true && !current.isEmpty &&
                current == rect && node.windowId == window) stable + 1 else 0
            rect = current
            window = node?.windowId ?: -1
            stable >= 3
        }
        tap(rect.centerX().toFloat(), rect.centerY().toFloat(), if (longPress) 750 else 60)
    }

    private fun tap(x: Float, y: Float, duration: Long) {
        val downTime = SystemClock.uptimeMillis()
        fun event(action: Int) {
            val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            check(automation.injectInputEvent(event, true))
            event.recycle()
        }
        event(MotionEvent.ACTION_DOWN)
        SystemClock.sleep(duration)
        event(MotionEvent.ACTION_UP)
    }

    fun outside(label: String) {
        await("Missing decision sheet") { has(label) }
        val sheet = Rect()
        find(label)!!.getBoundsInScreen(sheet)
        val root = Rect()
        focusedRoot()!!.getBoundsInScreen(root)
        check(sheet.top > root.top) { "No public outside region" }
        tap(root.centerX().toFloat(), (root.top + sheet.top) / 2f, 60)
    }

    fun back() {
        val time = SystemClock.uptimeMillis()
        check(automation.injectInputEvent(KeyEvent(time, time, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK, 0), true))
        check(automation.injectInputEvent(KeyEvent(time, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK, 0), true))
    }

    fun packageVisible(packageName: String): Boolean = focusedRoot()?.packageName?.toString() == packageName

    fun gridLabels(labels: List<String>): List<String> = labels.map { label ->
        val rect = Rect()
        val node = find(label) ?: return emptyList()
        node.getBoundsInScreen(rect)
        label to rect
    }.sortedWith(compareBy({ it.second.top }, { it.second.left })).map { it.first }

    fun homeMenu() {
        await("HOME is not visible") { has("Apps") }
        val root = Rect()
        focusedRoot()!!.getBoundsInScreen(root)
        // The empty side of the clock lane, using this frame's actual window bounds.
        tap(root.left + root.width() / 10f, root.top + root.height() / 3f, 750)
        // The wallpaper option is the menu entry present in every configuration.
        await("HOME long press did not open its public menu") { has("Set Wallpaper") || has("Clear Wallpaper") }
    }

    fun swipeToHome(vertical: Boolean) {
        val root = Rect()
        focusedRoot()!!.getBoundsInScreen(root)
        val x = root.left + root.width() / 2f
        val y = root.top + root.height() / 2f
        val startX = if (vertical) x else root.left + root.width() / 4f
        val startY = if (vertical) root.top + root.height() / 4f else y
        val endX = if (vertical) x else root.left + root.width() * 3 / 4f
        val endY = if (vertical) root.top + root.height() * 3 / 4f else y
        val down = SystemClock.uptimeMillis()
        for (step in 0..20) {
            val action = when (step) {
                0 -> MotionEvent.ACTION_DOWN
                20 -> MotionEvent.ACTION_UP
                else -> MotionEvent.ACTION_MOVE
            }
            val fraction = step / 20f
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action,
                startX + (endX - startX) * fraction, startY + (endY - startY) * fraction, 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            check(automation.injectInputEvent(event, true))
            event.recycle()
            SystemClock.sleep(20)
        }
    }

    fun imeVisible(): Boolean = windows().any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
}
