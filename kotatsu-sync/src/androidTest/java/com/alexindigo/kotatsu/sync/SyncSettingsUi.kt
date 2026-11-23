package com.alexindigo.kotatsu.sync

import android.app.Activity
import android.app.Instrumentation
import android.graphics.Bitmap
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.semantics.getOrNull
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*

/** Drives the public Compose accessibility tree and captures only frame-committed windows. */
internal class SyncSettingsUi(private val instrumentation: Instrumentation, private val captureScreenshots: Boolean) {
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
    fun await(label: String) = awaitSyncSettings("Settings must show $label") { has(label) }
    fun awaitPage(title: String) = awaitSyncSettings("Settings heading: $title") { find(title)?.isHeading == true }
    fun selected(label: String): Boolean {
        var node = find(label)
        while (node != null && !node.isCheckable) node = node.parent
        return node?.isChecked == true
    }
    fun click(label: String) {
        awaitSyncSettings("Actionable settings row: $label") { find(label, actionable = true) != null }
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

    fun dumpFailure(directory: File) {
        val text = StringBuilder()
        fun visit(node: AccessibilityNodeInfo, depth: Int) {
            text.append(" ".repeat(depth)).append("${node.className} text=${node.text} description=${node.contentDescription} checked=${node.isChecked}\n")
            for (i in 0 until node.childCount) node.getChild(i)?.let { visit(it, depth + 1) }
        }
        automation.rootInActiveWindow?.let { visit(it, 0) }
        File(directory, "window.txt").writeText(text.toString())
        val image = automation.takeScreenshot()
        File(directory, "screen.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
    }

    @OptIn(androidx.compose.ui.InternalComposeUiApi::class)
    fun drawings(tag: String): List<androidx.compose.ui.geometry.Rect> {
        val result = mutableListOf<androidx.compose.ui.geometry.Rect>()
        fun semantics(node: androidx.compose.ui.semantics.SemanticsNode) {
            if (node.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.TestTag) == tag)
                result += node.boundsInWindow
            node.children.forEach(::semantics)
        }
        fun views(view: android.view.View) {
            if (view is androidx.compose.ui.platform.ViewRootForTest) semantics(view.semanticsOwner.unmergedRootSemanticsNode)
            else if (view is android.view.ViewGroup) for (i in 0 until view.childCount) views(view.getChildAt(i))
        }
        instrumentation.runOnMainSync { android.view.inspector.WindowInspector.getGlobalWindowViews().forEach(::views) }
        return result
    }

    @OptIn(androidx.compose.ui.InternalComposeUiApi::class)
    fun textLayout(label: String): androidx.compose.ui.text.TextLayoutResult {
        val result = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        fun semantics(node: androidx.compose.ui.semantics.SemanticsNode) {
            // The chapter subcomposes a sizing pass as well as its placed rows.
            if (node.layoutInfo.isPlaced && node.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.Text)?.any { it.text == label } == true)
                node.config.getOrNull(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult)?.action?.invoke(result)
            node.children.forEach(::semantics)
        }
        fun views(view: android.view.View) {
            if (view is androidx.compose.ui.platform.ViewRootForTest) semantics(view.semanticsOwner.unmergedRootSemanticsNode)
            else if (view is android.view.ViewGroup) for (i in 0 until view.childCount) views(view.getChildAt(i))
        }
        instrumentation.runOnMainSync { android.view.inspector.WindowInspector.getGlobalWindowViews().forEach(::views) }
        return result.single()
    }

    fun capture(activity: Activity, name: String) {
        if (!captureScreenshots) return
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
        val expected = IntArray(contentSize[0] * contentSize[1])
        val actual = IntArray(expected.size)
        var frame: Bitmap? = null
        awaitSyncSettings("The displayed frame must equal the committed window, without a transition overlay") {
            // A completed pass may publish while capturing. Compare the current
            // committed window each attempt, never a permanently stale first frame.
            val copied = CountDownLatch(1)
            var status = -1
            android.view.PixelCopy.request(activity.window, window, { status = it; copied.countDown() },
                android.os.Handler(android.os.Looper.getMainLooper()))
            assertTrue(copied.await(10, TimeUnit.SECONDS))
            assertEquals(android.view.PixelCopy.SUCCESS, status)
            window.getPixels(expected, 0, contentSize[0], contentLocation[0] - location[0],
                contentLocation[1] - location[1], contentSize[0], contentSize[1])
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

internal fun awaitSyncSettings(message: String, condition: () -> Boolean) {
    val end = android.os.SystemClock.uptimeMillis() + 15000
    while (!condition()) {
        assertTrue(message, android.os.SystemClock.uptimeMillis() < end)
        android.os.SystemClock.sleep(50)
    }
}
