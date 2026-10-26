package com.alexindigo.kotatsu.weather

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.widget.TextView
import android.widget.EditText
import android.view.View
import android.view.ViewGroup
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.*
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.espresso.matcher.RootMatchers.isDialog

/** Real editor instances over a bound provider, including Android saved-state recreation. */
@RunWith(AndroidJUnit4::class)
class EditorPersistenceInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val store get() = WeatherStore(context)
    private val first = Location("First", "First fixture place", 10.0, 20.0)
    private val second = Location("Second", "Second fixture place", 30.0, 40.0)

    @Test fun olderEditorCannotEraseASavedAddition() {
        val host = AppWidgetHost(context, 0x4B54)
        val manager = AppWidgetManager.getInstance(context)
        instrumentation.uiAutomation.adoptShellPermissionIdentity("android.permission.BIND_APPWIDGET")
        val id = host.allocateAppWidgetId()
        var older: ConfigureActivity? = null
        var newer: ConfigureActivity? = null
        try {
            assertTrue(manager.bindAppWidgetIdIfAllowed(id, ComponentName(context, WeatherWidgetProvider::class.java)))
            seed(id, WidgetConfig(listOf(first)))
            older = open(id)
            // A second editor adds after the first opened, through the real Current location UI.
            newer = open(id)
            addCurrentThroughUi()
            tapControl(withText("Here"))
            inDialog(isAssignableFrom(EditText::class.java)).perform(replaceText("Second"))
            tapControl(withText("Save"), dialog = true)
            instrumentation.runOnMainSync {
                find(newer!!.window.decorView, "Done")!!.performClick()
            }
            instrumentation.waitForIdleSync()
            assertEquals(listOf("First", "Second"), store.config(id)!!.locations.map { it.label })
            instrumentation.runOnMainSync { find(older!!.window.decorView, "Done")!!.performClick() }
            instrumentation.waitForIdleSync()
            assertEquals("An older editor must not erase the newly saved place", listOf("First", "Second"), store.config(id)!!.locations.map { it.label })
        } finally {
            instrumentation.runOnMainSync { older?.finish(); newer?.finish() }
            host.deleteAppWidgetId(id)
            store.delete(id)
            instrumentation.uiAutomation.dropShellPermissionIdentity()
            WeatherRuntime.cancel(context)
            shell("cmd location providers remove-test-provider gps")
        }
    }

    @Test fun restoredAppIconEditorCannotWriteToAnotherWidget() {
        val host = AppWidgetHost(context, 0x4B54)
        val manager = AppWidgetManager.getInstance(context)
        instrumentation.uiAutomation.adoptShellPermissionIdentity("android.permission.BIND_APPWIDGET")
        val originalId = host.allocateAppWidgetId()
        var replacementId = AppWidgetManager.INVALID_APPWIDGET_ID
        var editor: ConfigureActivity? = null
        try {
            assertTrue(manager.bindAppWidgetIdIfAllowed(originalId, ComponentName(context, WeatherWidgetProvider::class.java)))
            seed(originalId, WidgetConfig(listOf(first)))
            editor = instrumentation.startActivitySync(Intent(context, ConfigureActivity::class.java)
                .setAction(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)) as ConfigureActivity
            // The host removes the original and later creates a different widget. Android's saved
            // state still belongs to the original editor, even though it was opened without an ID.
            host.deleteAppWidgetId(originalId)
            replacementId = host.allocateAppWidgetId()
            assertTrue(manager.bindAppWidgetIdIfAllowed(replacementId, ComponentName(context, WeatherWidgetProvider::class.java)))
            seed(replacementId, WidgetConfig(listOf(second)))
            val monitor = instrumentation.addMonitor(ConfigureActivity::class.java.name, null, false)
            try {
                instrumentation.runOnMainSync { editor!!.recreate() }
                editor = monitor.waitForActivityWithTimeout(10_000) as? ConfigureActivity
                assertNotNull("Android recreated the editor", editor)
                instrumentation.waitForIdleSync()
                instrumentation.runOnMainSync { find(editor!!.window.decorView, "Done")?.performClick() }
                instrumentation.waitForIdleSync()
                assertEquals("A restored editor belongs to its original widget", listOf(second), store.config(replacementId)!!.locations)
            } finally { instrumentation.removeMonitor(monitor) }
        } finally {
            instrumentation.runOnMainSync { editor?.finish() }
            host.deleteAppWidgetId(originalId)
            if (replacementId != AppWidgetManager.INVALID_APPWIDGET_ID) host.deleteAppWidgetId(replacementId)
            store.delete(originalId)
            if (replacementId != AppWidgetManager.INVALID_APPWIDGET_ID) store.delete(replacementId)
            instrumentation.uiAutomation.dropShellPermissionIdentity()
            WeatherRuntime.cancel(context)
        }
    }

    @Test fun singlePlaceFieldsSurviveRecreationWithoutSavingAList() {
        val host = AppWidgetHost(context, 0x4B54)
        val manager = AppWidgetManager.getInstance(context)
        instrumentation.uiAutomation.adoptShellPermissionIdentity("android.permission.BIND_APPWIDGET")
        val id = host.allocateAppWidgetId()
        var editor: ConfigureActivity? = null
        try {
            assertTrue(manager.bindAppWidgetIdIfAllowed(id, ComponentName(context, WeatherWidgetProvider::class.java)))
            seed(id, WidgetConfig(listOf(first)))
            editor = open(id)
            tapControl(withText("First"))
            inDialog(isAssignableFrom(EditText::class.java)).perform(replaceText("Edited"))
            assertEquals("First", store.config(id)!!.locations.single().label)
            store.add(id, second)
            val monitor = instrumentation.addMonitor(ConfigureActivity::class.java.name, null, false)
            try {
                instrumentation.runOnMainSync { editor!!.recreate() }
                editor = monitor.waitForActivityWithTimeout(10_000) as? ConfigureActivity
                assertNotNull(editor)
                inDialog(isAssignableFrom(EditText::class.java)).check(androidx.test.espresso.assertion.ViewAssertions.matches(withText("Edited")))
                tapControl(withText("Save"), dialog = true)
                assertEquals(listOf("Edited", "Second"), store.config(id)!!.locations.map { it.label })
                // Back closes without a whole-config save, and the change is already durable.
                instrumentation.runOnMainSync { editor!!.onBackPressed() }
                assertEquals(listOf("Edited", "Second"), WeatherStore(context).config(id)!!.locations.map { it.label })
            } finally { instrumentation.removeMonitor(monitor) }
        } finally {
            instrumentation.runOnMainSync { editor?.finish() }
            host.deleteAppWidgetId(id); store.delete(id)
            instrumentation.uiAutomation.dropShellPermissionIdentity()
            WeatherRuntime.cancel(context)
        }
    }

    @Test fun unreadableConfigurationHasNoEditingControls() {
        val host = AppWidgetHost(context, 0x4B54)
        instrumentation.uiAutomation.adoptShellPermissionIdentity("android.permission.BIND_APPWIDGET")
        val id = host.allocateAppWidgetId()
        var editor: ConfigureActivity? = null
        try {
            assertTrue(AppWidgetManager.getInstance(context).bindAppWidgetIdIfAllowed(id, ComponentName(context, WeatherWidgetProvider::class.java)))
            val raw = "{\"version\":99,\"locations\":[]}"
            context.getSharedPreferences("weather", 0).edit().putString("widget_$id", raw).commit()
            editor = open(id)
            instrumentation.waitForIdleSync()
            assertNotNull(find(editor.window.decorView, context.getString(R.string.config_unreadable)))
            assertNull(find(editor.window.decorView, "Done"))
            assertNull(find(editor.window.decorView, "Add location"))
            assertEquals(raw, context.getSharedPreferences("weather", 0).getString("widget_$id", null))
        } finally {
            instrumentation.runOnMainSync { editor?.finish() }
            host.deleteAppWidgetId(id); store.delete(id)
            instrumentation.uiAutomation.dropShellPermissionIdentity()
            WeatherRuntime.cancel(context)
        }
    }

    @Test fun appIconResolvesAgainInsteadOfResumingAnOldWidgetEditor() {
        val host = AppWidgetHost(context, 0x4B54)
        val manager = AppWidgetManager.getInstance(context)
        instrumentation.uiAutomation.adoptShellPermissionIdentity("android.permission.BIND_APPWIDGET")
        val firstId = host.allocateAppWidgetId()
        val secondId = host.allocateAppWidgetId()
        val activities = mutableListOf<android.app.Activity>()
        val monitor = instrumentation.addMonitor(ConfigureActivity::class.java.name, null, false)
        try {
            assertTrue(manager.bindAppWidgetIdIfAllowed(firstId, ComponentName(context, WeatherWidgetProvider::class.java)))
            seed(firstId, WidgetConfig(listOf(first)))
            val original = open(firstId)
            activities += original
            assertTrue(manager.bindAppWidgetIdIfAllowed(secondId, ComponentName(context, WeatherWidgetProvider::class.java)))
            seed(secondId, WidgetConfig(listOf(second)))
            context.startActivity(context.packageManager.getLaunchIntentForPackage(context.packageName)!!)
            awaitFocusedEditor(monitor, activities, dialog = true) {
                it !== original && !it.intent.hasExtra(AppWidgetManager.EXTRA_APPWIDGET_ID)
            }
            inDialog(withText("Which widget?")).check(androidx.test.espresso.assertion.ViewAssertions.matches(isDisplayed()))
            tapControl(withText("Second"), dialog = true)
            val selected = awaitFocusedEditor(monitor, activities) {
                it.intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1) == secondId
            }
            tapControl(withText("Temperature"), decor = selected.window.decorView)
            assertTrue(store.config(firstId)!!.fahrenheit)
            assertFalse(store.config(secondId)!!.fahrenheit)
            assertEquals(listOf(first), store.config(firstId)!!.locations)
            assertEquals(listOf(second), store.config(secondId)!!.locations)
        } finally {
            instrumentation.removeMonitor(monitor)
            instrumentation.runOnMainSync { activities.forEach { it.finish() } }
            host.deleteAppWidgetId(firstId); host.deleteAppWidgetId(secondId)
            store.delete(firstId); store.delete(secondId)
            instrumentation.uiAutomation.dropShellPermissionIdentity()
            WeatherRuntime.cancel(context)
        }
    }

    /** Dialog controls belong to their dialog, not a still-visible older editor Activity. */
    private fun inDialog(matcher: org.hamcrest.Matcher<View>) = onView(matcher).inRoot(isDialog())

    /** Espresso selects the exact view/root; the public SDK injection waits for native window
     * animations and input transactions too (main-loop idle/focus alone cannot establish that). */
    private fun tapControl(matcher: org.hamcrest.Matcher<View>, dialog: Boolean = false, decor: View? = null) {
        val interaction = when {
            dialog -> inDialog(matcher)
            decor != null -> onView(matcher).inRoot(androidx.test.espresso.matcher.RootMatchers.withDecorView(org.hamcrest.Matchers.`is`(decor)))
            else -> onView(matcher)
        }
        val position = IntArray(2)
        var x = 0f
        var y = 0f
        interaction.check { view, error ->
            if (error != null) throw error
            assertTrue("Control must be displayed", view.isShown && view.width > 0 && view.height > 0)
            view.getLocationOnScreen(position)
            x = position[0] + view.width / 2f
            y = position[1] + view.height / 2f
        }
        val down = android.os.SystemClock.uptimeMillis()
        for (action in listOf(android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_UP)) {
            val event = android.view.MotionEvent.obtain(down, android.os.SystemClock.uptimeMillis(), action, x, y, 0)
            event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
            try { instrumentation.sendPointerSync(event) } finally { event.recycle() }
        }
        instrumentation.waitForIdleSync()
    }

    /** AOSP reports the same class to ActivityMonitor at both create and resume. A hit is neither
     * a fresh route nor input readiness: match the requested editor identity and its focused window. */
    private fun awaitFocusedEditor(monitor: android.app.Instrumentation.ActivityMonitor,
        activities: MutableList<android.app.Activity>, dialog: Boolean = false,
        accepts: (ConfigureActivity) -> Boolean): ConfigureActivity {
        val end = android.os.SystemClock.uptimeMillis() + 10_000
        var selected: ConfigureActivity? = null
        while (android.os.SystemClock.uptimeMillis() < end) {
            (monitor.waitForActivityWithTimeout(25) as? ConfigureActivity)?.let { candidate ->
                if (candidate !in activities) activities += candidate
                if (accepts(candidate)) selected = candidate
            }
            var ready = false
            instrumentation.runOnMainSync {
                // The chooser's dialog owns focus, not the Activity decor underneath it;
                // the following Espresso inDialog assertion observes that dialog's input root.
                selected?.let { ready = !it.isFinishing && (dialog || it.window.decorView.hasWindowFocus()) }
            }
            if (ready) return selected!!
        }
        throw AssertionError("The requested editor did not acquire input focus")
    }

    private fun open(id: Int): ConfigureActivity = instrumentation.startActivitySync(
        Intent(context, ConfigureActivity::class.java)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
    ) as ConfigureActivity

    private fun seed(id: Int, config: WidgetConfig) {
        assertTrue(context.getSharedPreferences("weather", 0).edit().putString("widget_$id", ConfigJson.encode(config)).commit())
    }

    private fun shell(command: String): String = android.os.ParcelFileDescriptor.AutoCloseInputStream(
        instrumentation.uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText() }

    private fun addCurrentThroughUi() {
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, android.Manifest.permission.ACCESS_FINE_LOCATION)
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, android.Manifest.permission.ACCESS_COARSE_LOCATION)
        shell("cmd location set-location-enabled true")
        shell("appops set 2000 android:mock_location allow")
        shell("cmd location providers add-test-provider gps")
        shell("cmd location providers set-test-provider-enabled gps true")
        shell("cmd location providers set-test-provider-location gps --location 30,40 --accuracy 5")
        tapControl(withText("Add location"))
        tapControl(withText("Add current location"), dialog = true)
        instrumentation.waitForIdleSync()
    }

    private fun find(view: View, value: String): TextView? {
        if (view is TextView && view.text.toString() == value) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) find(view.getChildAt(i), value)?.let { return it }
        return null
    }
}
