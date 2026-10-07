package com.gezimos.katapult

import android.app.Instrumentation
import android.app.UiAutomation
import android.graphics.Rect
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo

/** Test-only public UI input; no adopted shell permissions or production hooks. */
class PlatformUiDriver(instrumentation: Instrumentation) {
    private val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)

    init {
        // AOSP API31 UiAutomation.getWindows requires this test-service opt-in.
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
    }

    fun await(message: String, timeoutMs: Long = 8000, predicate: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < end) {
            if (predicate()) return
            SystemClock.sleep(80)
        }
        throw AssertionError(message)
    }

    private fun matching(label: String): AccessibilityNodeInfo? {
        val root = automation.rootInActiveWindow ?: return null
        fun walk(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (node.text?.toString() == label || node.contentDescription?.toString() == label) return node
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                walk(child)?.let { return it }
            }
            return null
        }
        return walk(root)
    }

    fun has(label: String): Boolean = matching(label) != null

    fun checked(label: String): Boolean? {
        var node = matching(label)
        while (node != null) {
            if (node.isCheckable) return node.isChecked
            node = node.parent
        }
        return null
    }

    fun scrollTo(label: String) {
        repeat(12) {
            if (has(label)) return
            val root = Rect()
            automation.rootInActiveWindow!!.getBoundsInScreen(root)
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
        await("Missing public control: $label") { has(label) }
        val rect = Rect()
        matching(label)!!.getBoundsInScreen(rect)
        check(!rect.isEmpty) { "Control has no current bounds: $label" }
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
        matching(label)!!.getBoundsInScreen(sheet)
        val root = Rect()
        automation.rootInActiveWindow!!.getBoundsInScreen(root)
        check(sheet.top > root.top) { "No public outside region" }
        tap(root.centerX().toFloat(), (root.top + sheet.top) / 2f, 60)
    }

    fun back() {
        val time = SystemClock.uptimeMillis()
        check(automation.injectInputEvent(KeyEvent(time, time, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK, 0), true))
        check(automation.injectInputEvent(KeyEvent(time, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK, 0), true))
    }

    fun packageVisible(packageName: String): Boolean = automation.rootInActiveWindow?.packageName?.toString() == packageName

    fun gridLabels(labels: List<String>): List<String> = labels.map { label ->
        val rect = Rect()
        val node = matching(label) ?: return emptyList()
        node.getBoundsInScreen(rect)
        label to rect
    }.sortedWith(compareBy({ it.second.top }, { it.second.left })).map { it.first }

    fun homeMenu() {
        await("HOME is not visible") { has("Apps") }
        val root = Rect()
        automation.rootInActiveWindow!!.getBoundsInScreen(root)
        // The empty side of the clock lane, using this frame's actual window bounds.
        tap(root.left + root.width() / 10f, root.top + root.height() / 3f, 750)
        await("HOME long press did not open its public menu") { has("Hidden Apps") }
    }

    fun swipeToHome(vertical: Boolean) {
        val root = Rect()
        automation.rootInActiveWindow!!.getBoundsInScreen(root)
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

    fun imeVisible(): Boolean = automation.windows.any { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD }
}
