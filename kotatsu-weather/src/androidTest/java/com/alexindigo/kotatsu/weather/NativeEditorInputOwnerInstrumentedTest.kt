package com.alexindigo.kotatsu.weather

import android.app.ActivityManager
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.accessibility.AccessibilityWindowInfo
import android.view.inspector.WindowInspector
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gezimos.katapult.withFixtureCleanup
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class NativeEditorInputOwnerInstrumentedTest {
    @get:Rule val refresh: org.junit.rules.TestRule = RefreshQuiescenceFixture()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)
    private fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(
        instrumentation.uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText() }
    private fun await(message: String, condition: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < end) {
            if (condition()) return
            SystemClock.sleep(50)
        }
        throw AssertionError(message)
    }
    private fun fields(view: View): List<EditText> = if (view is EditText) listOf(view) else if (view is ViewGroup)
        (0 until view.childCount).flatMap { fields(view.getChildAt(it)) } else emptyList()
    private fun currentField(): EditText {
        lateinit var field: EditText
        main { field = WindowInspector.getGlobalWindowViews().filter { it.hasWindowFocus() }.flatMap(::fields).single() }
        return field
    }
    private fun focused(): Boolean {
        var result = false
        main { result = WindowInspector.getGlobalWindowViews().flatMap(::fields).any { it.isFocused } }
        return result
    }
    private fun imeVisible(): Boolean {
        val automation = instrumentation.uiAutomation
        val info = automation.serviceInfo
        info.flags = info.flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        automation.serviceInfo = info
        return automation.windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
    }
    private fun released(old: EditText) {
        await("Native field retained focus or IME after exit") {
            var oldFocused = true
            var insets = true
            main {
                oldFocused = old.isFocused
                insets = WindowInspector.getGlobalWindowViews().any {
                    it.isAttachedToWindow && it.hasWindowFocus() && it.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) == true
                }
            }
            !oldFocused && !insets && !imeVisible() &&
                !context.getSystemService(InputMethodManager::class.java).isAcceptingText
        }
    }
    private fun fixture(test: (ConfigureActivity, Int) -> Unit) {
        val host = AppWidgetHost(context, 0x494D45)
        instrumentation.uiAutomation.adoptShellPermissionIdentity("android.permission.BIND_APPWIDGET")
        val id = host.allocateAppWidgetId()
        var activity: ConfigureActivity? = null
        withFixtureCleanup(
            { main { activity?.finish() } },
            { host.deleteAppWidgetId(id) },
            { WeatherStore(context).delete(id) },
            { instrumentation.uiAutomation.dropShellPermissionIdentity() },
            { WeatherRuntime.cancel(context) },
        ) {
            assertTrue(AppWidgetManager.getInstance(context).bindAppWidgetIdIfAllowed(id, ComponentName(context, WeatherWidgetProvider::class.java)))
            assertTrue(context.getSharedPreferences("weather", 0).edit().putString("widget_$id",
                ConfigJson.encode(WidgetConfig(listOf(Location("First", "Fixture place", 10.0, 20.0))))).commit())
            activity = instrumentation.startActivitySync(Intent(context, ConfigureActivity::class.java)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as ConfigureActivity
            try { test(activity, id) }
            catch (failure: Throwable) {
                runCatching {
                    val directory = File(context.getExternalFilesDir(null), "native-owner-${SystemClock.uptimeMillis()}")
                    check(directory.mkdir())
                    for (service in listOf("window", "input_method")) File(directory, "$service.txt").writeText(shell("dumpsys $service"))
                    val bitmap = instrumentation.uiAutomation.takeScreenshot()
                    File(directory, "screen.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                    bitmap.recycle()
                }.onFailure { failure.addSuppressed(it) }
                throw failure
            }
        }
    }
    private fun edit(hint: String, value: String): EditText {
        tapControl(withHint(hint))
        onView(withHint(hint)).inRoot(isDialog()).perform(replaceText(value))
        val field = currentField()
        await("Native input did not become active") { focused() && imeVisible() }
        return field
    }

    private fun tap(x: Float, y: Float) {
        val down = SystemClock.uptimeMillis()
        for (action in listOf(android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_UP)) {
            val event = android.view.MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0)
            event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
            withFixtureCleanup({ event.recycle() }) { instrumentation.sendPointerSync(event) }
        }
        instrumentation.waitForIdleSync()
    }

    /** Input readiness includes the IME's native pan/resize, not only main-loop idleness. */
    private fun tapControl(matcher: org.hamcrest.Matcher<View>) {
        var previous = android.graphics.Rect()
        var stable = 0
        var bounds = android.graphics.Rect()
        await("Dialog control did not settle") {
            onView(matcher).inRoot(isDialog()).check { view, error ->
                if (error != null) throw error
                val position = IntArray(2)
                view.getLocationOnScreen(position)
                bounds = android.graphics.Rect(position[0], position[1], position[0] + view.width, position[1] + view.height)
                stable = if (view.isShown && !bounds.isEmpty && bounds == previous && !view.isLayoutRequested && view.hasWindowFocus()) stable + 1 else 0
                previous = android.graphics.Rect(bounds)
            }
            stable >= 3
        }
        tap(bounds.exactCenterX(), bounds.exactCenterY())
    }

    /** Native accessibility actions activate the actual dialog button even when
     * the platform IME covers its pointer region. No direct dismiss/hide/focus call.
     */
    private fun dialogAction(label: String) {
        val automation = instrumentation.uiAutomation
        val service = automation.serviceInfo
        service.flags = service.flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        automation.serviceInfo = service
        fun find(node: android.view.accessibility.AccessibilityNodeInfo): android.view.accessibility.AccessibilityNodeInfo? {
            if (!node.refresh()) return null
            if (node.text?.toString().equals(label, ignoreCase = true) && node.isClickable) return node
            for (index in 0 until node.childCount) node.getChild(index)?.let { find(it)?.let { found -> return found } }
            return null
        }
        var button: android.view.accessibility.AccessibilityNodeInfo? = null
        await("Native dialog action missing: $label") {
            button = automation.windows.singleOrNull { it.isFocused && it.root?.packageName?.toString() == context.packageName }
                ?.root?.let(::find)
            button != null
        }
        assertTrue("Native button rejected its accessibility click", button!!.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
    }

    @Test fun searchCancelAndHomeReleaseNativeInputWithoutChangingConfiguration() = fixture { activity, id ->
        val original = WeatherStore(context).config(id)
        onView(withText("Add location")).perform(click())
        var field = edit("City or town", "Unsaved city")
        tapControl(withText("Cancel"))
        released(field)
        onView(withText("Add location")).perform(click())
        field = edit("City or town", "Home city")
        shell("input keyevent KEYCODE_HOME")
        released(field)
        // Bring back the actual existing task, not a replacement editor instance.
        main { context.getSystemService(ActivityManager::class.java).appTasks.single { it.taskInfo.taskId == activity.taskId }.moveToFront() }
        await("Still-visible search did not regain focus") { focused() }
        main { assertEquals("Home city", field.text.toString()) }
        tapControl(withText("Cancel"))
        released(field)
        assertEquals(original, WeatherStore(context).config(id))
        onView(withText("Add location")).perform(click())
        field = edit("City or town", "Recreate city")
        val monitor = instrumentation.addMonitor(ConfigureActivity::class.java.name, null, false)
        var recreated: ConfigureActivity? = null
        withFixtureCleanup({ instrumentation.removeMonitor(monitor) }, { main { recreated?.finish() } }) {
            main { activity.recreate() }
            recreated = monitor.waitForActivityWithTimeout(10_000) as? ConfigureActivity
            assertNotNull(recreated)
            released(field)
            // Search has never persisted across Activity recreation. Do not invent a new draft policy.
            onView(withText("Add location")).check(androidx.test.espresso.assertion.ViewAssertions.matches(isDisplayed()))
            assertEquals(original, WeatherStore(context).config(id))
        }
    }

    @Test fun labelRecreationRetiresOldWindowAndKeepsUnsavedDraft() = fixture { activity, id ->
        onView(withText("First")).perform(click())
        val canceled = edit("Up to 8 characters", "Canceled")
        dialogAction("Cancel")
        released(canceled)
        assertEquals("First", WeatherStore(context).config(id)!!.locations.single().label)
        onView(withText("First")).perform(click())
        val field = edit("Up to 8 characters", "Edited")
        val monitor = instrumentation.addMonitor(ConfigureActivity::class.java.name, null, false)
        var recreated: ConfigureActivity? = null
        withFixtureCleanup({ instrumentation.removeMonitor(monitor) }, { main { recreated?.finish() } }) {
            main { activity.recreate() }
            recreated = monitor.waitForActivityWithTimeout(10_000) as? ConfigureActivity
            assertNotNull(recreated)
            await("Old label/window was not retired") { var retired = false; main { retired = !field.isFocused && !field.isAttachedToWindow }; retired }
            onView(withHint("Up to 8 characters")).inRoot(isDialog())
                .check(androidx.test.espresso.assertion.ViewAssertions.matches(withText("Edited")))
            assertEquals("First", WeatherStore(context).config(id)!!.locations.single().label)
            val fresh = edit("Up to 8 characters", "Edited")
            shell("input keyevent KEYCODE_HOME"); released(fresh)
            main { context.getSystemService(ActivityManager::class.java).appTasks.single { it.taskInfo.taskId == recreated!!.taskId }.moveToFront() }
            await("Restored native field did not regain focus") { focused() }
            dialogAction("Save")
            released(fresh)
            assertEquals("Edited", WeatherStore(context).config(id)!!.locations.single().label)
        }
    }
}
