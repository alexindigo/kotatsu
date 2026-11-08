package com.alexindigo.kotatsu.notes

import android.app.Activity
import android.app.Instrumentation
import android.graphics.Bitmap
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*

/** Drives the public Compose accessibility tree and captures only frame-committed windows. */
internal class NotesSettingsUi(private val instrumentation: Instrumentation) {
    private val automation = instrumentation.uiAutomation

    fun find(label: String, actionable: Boolean = false): AccessibilityNodeInfo? {
        fun visit(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (!node.refresh()) return null
            if (node.text?.toString() == label || node.contentDescription?.toString() == label ||
                node.contentDescription?.toString()?.startsWith("$label, ") == true) {
                if (!actionable) return node
                var control: AccessibilityNodeInfo? = node
                while (control != null && !control.isClickable) control = control.parent
                if (control?.isVisibleToUser == true) return control
            }
            for (index in 0 until node.childCount) node.getChild(index)?.let { visit(it)?.let { return it } }
            return null
        }
        return automation.rootInActiveWindow?.let(::visit)
    }
    fun has(label: String) = find(label) != null
    fun await(label: String) = awaitWidgetHost("Settings must show $label") { has(label) }
    fun awaitPage(title: String) = awaitWidgetHost("Settings heading: $title") { find(title)?.isHeading == true }
    fun selected(label: String): Boolean {
        var node = find(label)
        while (node != null && !node.isCheckable) node = node.parent
        return node?.isChecked == true
    }
    fun click(label: String) {
        awaitWidgetHost("Actionable settings row: $label") { find(label, actionable = true) != null }
        val node = find(label, actionable = true)
        assertNotNull("Clickable row for $label", node)
        val bounds = Rect().also { node!!.getBoundsInScreen(it) }
        val minimum = 48 * instrumentation.targetContext.resources.displayMetrics.density
        assertTrue("48dp target for $label: $bounds", bounds.height() >= minimum - 1 && bounds.width() >= minimum - 1)
        assertTrue(node!!.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        instrumentation.waitForIdleSync()
    }
    fun key(code: Int) {
        val time = android.os.SystemClock.uptimeMillis()
        for (action in listOf(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.ACTION_UP)) {
            val event = android.view.KeyEvent(time, android.os.SystemClock.uptimeMillis(), action, code, 0,
                0, android.view.KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, android.view.InputDevice.SOURCE_KEYBOARD)
            assertTrue(automation.injectInputEvent(event, true))
        }
        instrumentation.waitForIdleSync()
        automation.waitForIdle(100, 5000)
    }
    fun back() = key(android.view.KeyEvent.KEYCODE_BACK)

    fun capture(activity: Activity, name: String) {
        instrumentation.waitForIdleSync()
        val committed = CountDownLatch(1)
        instrumentation.runOnMainSync {
            activity.window.decorView.viewTreeObserver.registerFrameCommitCallback { committed.countDown() }
            activity.window.decorView.invalidate()
        }
        assertTrue("Settings frame committed", committed.await(10, TimeUnit.SECONDS))
        val size = IntArray(2)
        val location = IntArray(2)
        val contentLocation = IntArray(2)
        val contentSize = IntArray(2)
        instrumentation.runOnMainSync {
            size[0] = activity.window.decorView.width; size[1] = activity.window.decorView.height
            activity.window.decorView.getLocationOnScreen(location)
            activity.findViewById<android.view.View>(android.R.id.content).let {
                it.getLocationOnScreen(contentLocation)
                contentSize[0] = it.width; contentSize[1] = it.height
            }
        }
        val window = Bitmap.createBitmap(size[0], size[1], Bitmap.Config.ARGB_8888)
        val copied = CountDownLatch(1)
        var status = -1
        android.view.PixelCopy.request(activity.window, window, { status = it; copied.countDown() },
            android.os.Handler(android.os.Looper.getMainLooper()))
        assertTrue(copied.await(10, TimeUnit.SECONDS))
        assertEquals(android.view.PixelCopy.SUCCESS, status)
        val expected = IntArray(contentSize[0] * contentSize[1])
        val actual = IntArray(expected.size)
        window.getPixels(expected, 0, contentSize[0], contentLocation[0] - location[0],
            contentLocation[1] - location[1], contentSize[0], contentSize[1])
        var frame: Bitmap? = null
        awaitWidgetHost("The displayed frame must equal the committed window, without a transition overlay") {
            val candidate = automation.takeScreenshot()
            candidate.getPixels(actual, 0, contentSize[0], contentLocation[0], contentLocation[1], contentSize[0], contentSize[1])
            if (expected.contentEquals(actual)) { frame = candidate; true }
            else { candidate.recycle(); false }
        }
        window.recycle()
        val bitmap = checkNotNull(frame)
        val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "settings-after").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
    }
}
