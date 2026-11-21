package com.gezimos.katapult

import android.app.Instrumentation
import android.app.UiAutomation
import android.graphics.Rect
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Xml
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.accessibilityservice.AccessibilityServiceInfo
import java.io.File
import org.xmlpull.v1.XmlSerializer

/** Test-only public UI input; no adopted shell permissions or production hooks. */
class PlatformUiDriver(instrumentation: Instrumentation, private val paneTitle: String? = null) {
    private val context = instrumentation.targetContext
    private val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
    private var acknowledgedFullscreenWindow: Int? = null

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
        try {
            ObservationDeadline.await(message, timeoutMs, 80) {
                !acknowledgeFullscreenHint() && predicate()
            }
        } catch (failure: AssertionError) {
            // Evidence has its own bounded reads, outside the expired predicate's scope.
            runCatching { recordFailure(failure) }.exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        }
    }

    /** Android can post this input-owning tutorial after app content has already appeared.
     * Resolve that owner at every await boundary, not with a one-shot GOT IT text lookup.
     * Send one action per real window and observe its removal before evaluating app state. */
    private fun acknowledgeFullscreenHint(): Boolean {
        if (paneTitle != null) return false
        val title = find { it.packageName == "android" &&
            it.viewIdResourceName == "android:id/immersive_cling_title" } ?: return false
        val button = find { it.windowId == title.windowId && it.packageName == "android" &&
            it.viewIdResourceName == "android:id/ok" && it.isVisibleToUser && it.isEnabled } ?: return true
        if (acknowledgedFullscreenWindow != title.windowId) {
            check(button.performAction(AccessibilityNodeInfo.ACTION_CLICK)) { "Android fullscreen tutorial rejected acknowledgement" }
            acknowledgedFullscreenWindow = title.windowId
        }
        return true
    }

    /**
     * Saves what the timed-out await could observe under the target's external files,
     * `failure-dumps/<test>-<uptime>/`. CI and the VM runner pull that directory.
     */
    private fun recordFailure(failure: AssertionError) {
        val test = failure.stackTrace.firstNotNullOfOrNull { frame ->
            runCatching {
                Class.forName(frame.className).methods.firstOrNull {
                    it.name == frame.methodName && it.isAnnotationPresent(org.junit.Test::class.java)
                }?.let { "${frame.className}.${frame.methodName}" }
            }.getOrNull()
        } ?: "unknown"
        val directory = File(context.getExternalFilesDir(null), "failure-dumps/$test-${SystemClock.uptimeMillis()}")
        check(directory.mkdirs()) { "Could not create $directory" }
        File(directory, "message.txt").writeText(failure.stackTraceToString())
        File(directory, "windows.xml").outputStream().use { output ->
            val xml = Xml.newSerializer()
            xml.setOutput(output, "UTF-8")
            xml.startDocument("UTF-8", true)
            xml.startTag(null, "windows")
            for (window in windows()) {
                xml.startTag(null, "window")
                xml.attribute(null, "id", window.id.toString())
                xml.attribute(null, "type", window.type.toString())
                xml.attribute(null, "title", window.title?.toString().orEmpty())
                xml.attribute(null, "layer", window.layer.toString())
                xml.attribute(null, "active", window.isActive.toString())
                xml.attribute(null, "focused", window.isFocused.toString())
                window.root?.let { writeNode(xml, it) }
                xml.endTag(null, "window")
            }
            xml.endTag(null, "windows")
            xml.endDocument()
        }
        File(directory, "logcat.txt").writeText(automation.readShell("logcat -d -v threadtime -t 3000"))
    }

    private fun writeNode(xml: XmlSerializer, node: AccessibilityNodeInfo) {
        if (!node.refresh()) return
        val bounds = Rect().also(node::getBoundsInScreen)
        xml.startTag(null, "node")
        xml.attribute(null, "class", node.className?.toString().orEmpty())
        xml.attribute(null, "package", node.packageName?.toString().orEmpty())
        xml.attribute(null, "resource-id", node.viewIdResourceName.orEmpty())
        xml.attribute(null, "text", node.text?.toString().orEmpty())
        xml.attribute(null, "content-desc", node.contentDescription?.toString().orEmpty())
        xml.attribute(null, "pane-title", node.paneTitle?.toString().orEmpty())
        xml.attribute(null, "bounds", bounds.toShortString())
        xml.attribute(null, "visible", node.isVisibleToUser.toString())
        xml.attribute(null, "enabled", node.isEnabled.toString())
        xml.attribute(null, "clickable", node.isClickable.toString())
        xml.attribute(null, "checkable", node.isCheckable.toString())
        xml.attribute(null, "checked", node.isChecked.toString())
        xml.attribute(null, "focused", node.isFocused.toString())
        for (index in 0 until node.childCount) node.getChild(index)?.let { writeNode(xml, it) }
        xml.endTag(null, "node")
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
    private fun focusedRoot(): AccessibilityNodeInfo? {
        fun containsPane(node: AccessibilityNodeInfo): Boolean {
            if (!node.refresh()) return false
            if (node.paneTitle?.toString() == paneTitle) return true
            for (i in 0 until node.childCount) if (node.getChild(i)?.let(::containsPane) == true) return true
            return false
        }
        return windows().singleOrNull {
            if (paneTitle == null) it.isFocused && it.isActive
            else it.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY && it.root?.let(::containsPane) == true
        }?.root?.takeIf { it.refresh() }
    }

    /** A model callback can precede dialog disposal. Observe the input destination before the next action. */
    fun awaitFocusedWindow(windowId: Int) {
        await("Window $windowId did not regain input focus") {
            focusedRoot()?.let { it.windowId == windowId && it.childCount > 0 } == true
        }
    }

    fun has(label: String): Boolean = find(label) != null

    /** Mounted input targets, including duplicate app labels; never infer empty space from names. */
    fun clickableBounds(predicate: (AccessibilityNodeInfo) -> Boolean = { true }): List<Rect> {
        val bounds = mutableListOf<Rect>()
        fun visit(node: AccessibilityNodeInfo) {
            if (!node.refresh()) return
            if (node.isVisibleToUser && node.isClickable && predicate(node)) {
                val rect = Rect()
                node.getBoundsInScreen(rect)
                if (!rect.isEmpty) bounds.add(rect)
            }
            for (index in 0 until node.childCount) node.getChild(index)?.let(::visit)
        }
        focusedRoot()?.let(::visit)
        return bounds
    }

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

    /** Own both published states, or an explicit destination when switching opens another page. */
    fun toggleSwitch(label: String, from: Boolean, to: Boolean? = !from, settled: () -> Boolean = { true }) {
        await("Switch has not published its starting state: $label") { checked(label) == from }
        val control = checkNotNull(findAncestor(label) { it.isCheckable })
        check(control.performAction(AccessibilityNodeInfo.ACTION_CLICK)) { "Switch rejected its action: $label" }
        await("Switch transition did not reach its consumer: $label") {
            (to == null || checked(label) == to) && settled()
        }
    }

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
    fun windowVisible(): Boolean = focusedRoot()?.childCount?.let { it > 0 } == true
    fun windowDescriptions(): String = windows().joinToString { "title=${it.title} type=${it.type} focused=${it.isFocused} package=${it.root?.packageName}" }

    fun gridLabels(labels: List<String>): List<String> = labels.map { label ->
        val rect = Rect()
        val node = find(label) ?: return emptyList()
        node.getBoundsInScreen(rect)
        label to rect
    }.sortedWith(compareBy({ it.second.top }, { it.second.left })).map { it.first }

    fun homeMenu() {
        await("HOME is not visible") { has(context.getString(R.string.all_apps)) }
        val root = Rect()
        focusedRoot()!!.getBoundsInScreen(root)
        // The empty side of the clock lane, using this frame's actual window bounds.
        tap(root.left + root.width() / 10f, root.top + root.height() / 3f, 750)
        // The wallpaper option is the menu entry present in every configuration.
        await("HOME long press did not open its public menu") { has(context.getString(R.string.wallpaper)) }
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
