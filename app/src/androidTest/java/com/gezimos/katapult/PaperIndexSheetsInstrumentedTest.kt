package com.gezimos.katapult

import android.appwidget.AppWidgetProviderInfo
import android.content.Intent
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gezimos.katapult.lockscreen.LockscreenWidget
import com.gezimos.katapult.lockscreen.LockscreenWidgetSize
import com.gezimos.katapult.lockscreen.WidgetProviderChoice
import com.gezimos.katapult.lockscreen.WidgetSettingsRow
import com.gezimos.katapult.ui.LockscreenWidgetMenu
import com.gezimos.katapult.ui.MultiChoiceSheet
import com.gezimos.katapult.ui.WidgetProviderSheet
import com.gezimos.katapult.ui.SettingsScreen
import com.gezimos.katapult.ui.settings.AppChecklistPage
import com.gezimos.katapult.ui.settings.NotifyCell
import com.gezimos.katapult.model.AppModel
import com.gezimos.katapult.util.SensitiveChoice
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Exercise the production mixed-menu and multi-select consumers without allocating real widgets. */
@RunWith(AndroidJUnit4::class)
class PaperIndexSheetsInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val ui = PlatformUiDriver(instrumentation)

    @Test fun checklistSwipesAdvanceFromTheCurrentPageAndRetainState() = withActivity { activity ->
        val apps = (1..13).map { AppModel("fixture.$it", "App $it", "") }
        instrumentation.runOnMainSync {
            activity.setContent { AppChecklistPage("Fixture apps", apps, emptySet(), {}, {}, notifyCell = { NotifyCell.NONE }) }
        }
        ui.await("First app page missing") { ui.has("App 1") && ui.has("App 5") }
        val pager = android.graphics.Rect().also {
            ui.find(context.getString(R.string.paper_page, 1, 3))!!.getBoundsInScreen(it)
        }
        // Pager semantics are inside its 7.5dp bottom padding; the ordinary lane is63dp.
        assertEquals("Pager must retain its full exported bounds outside the scroll viewport",
            55.5f * context.resources.displayMetrics.density, pager.height().toFloat(), 1f)
        val arrow = android.graphics.Rect().also {
            ui.find(context.getString(R.string.paper_next_page))!!.getBoundsInScreen(it)
        }
        assertEquals(48f * context.resources.displayMetrics.density, arrow.height().toFloat(), 1f)
        val lastCell = android.graphics.Rect().also {
            ui.find(context.getString(R.string.paper_hide_app, "App 5"))!!.getBoundsInScreen(it)
        }
        assertEquals(64f * context.resources.displayMetrics.density, lastCell.height().toFloat(), 1f)
        assertTrue("The last row must be complete above the pager", lastCell.bottom <= pager.top)
        assertFalse("An overflowing sixth row must move to the next page", ui.has("App 6"))
        swipeLeft()
        ui.await("First swipe did not advance") { ui.has("App 6") && ui.has("App 10") }
        swipeLeft()
        ui.await("Second swipe reused the initial page") { ui.has("App 13") && !ui.has("App 6") }
        ui.click(context.getString(R.string.paper_previous_page))
        ui.await("Previous-page control missing") { ui.has("App 6") && ui.has("App 10") }
    }

    @Test fun importPreviewCancelPreservesSettingsAndShowsSeparateIncomingValues() = withActivity { activity ->
        val model = androidx.lifecycle.ViewModelProvider(activity)[MainViewModel::class.java]
        val raw = context.getSharedPreferences("katapult_prefs", android.content.Context.MODE_PRIVATE)
        lateinit var before: Map<String, *>
        instrumentation.runOnMainSync {
            val text = org.json.JSONObject(model.prefs.exportToJson(context))
                .put(SensitiveChoice.MEDIA_INFO.key, !model.prefs.sensitiveChoice(SensitiveChoice.MEDIA_INFO)).toString()
            model.screen = Screen.SETTINGS
            model.pendingImportText = text
            model.pendingImportPreview = model.prefs.previewPolicyImport(text)
            before = raw.all
            activity.setContent { SettingsScreen(model, activity.configExporter, activity.configImporter, activity.screensaverWallpaperPicker) }
        }
        ui.await("Import confirmation missing") { ui.has(context.getString(R.string.policy_import_confirmation)) }
        assertTrue(ui.has(context.getString(R.string.config_import_replaces_settings)))
        assertTrue(ui.has(context.getString(R.string.policy_media_info)))
        capture("import-preview")
        ui.click(context.getString(R.string.cancel))
        ui.await("Cancel did not clear preview") { model.pendingImportPreview == null && model.pendingImportText == null }
        assertEquals(before, raw.all)
    }

    @Test fun visibilityMatrixRendersAllFourNotifyStatesWithoutMutatingDisabledCells() = withActivity { activity ->
        val apps = listOf("Alarm", "Calculator", "Calendar", "Camera").map { AppModel(it, it, "") }
        var hides = 0
        var notifications = 0
        instrumentation.runOnMainSync {
            activity.setContent {
                AppChecklistPage("Hidden apps (3)", apps, setOf("Alarm", "Calculator", "Calendar"), {}, { hides++ },
                    notifyCell = { when (it.label) {
                        "Alarm" -> NotifyCell.DISABLED; "Calculator" -> NotifyCell.ON
                        "Calendar" -> NotifyCell.OFF; else -> NotifyCell.NONE
                    } }, onToggleNotify = { notifications++ })
            }
        }
        ui.await("Notify matrix missing") { ui.checked("Notify: Calculator") == true }
        assertEquals(false, ui.checked("Notify: Calendar"))
        assertFalse(ui.find("Notify: Alarm")!!.isEnabled)
        assertEquals(false, ui.checked("Notify: Alarm"))
        capture("visibility-states")
        ui.click("Notify: Alarm")
        assertEquals(0, hides); assertEquals(0, notifications)
        ui.click("Notify: Calendar")
        assertEquals(0, hides); assertEquals(1, notifications)
    }

    private fun swipeLeft() {
        val automation = instrumentation.getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val root = android.graphics.Rect()
        checkNotNull(automation.rootInActiveWindow).getBoundsInScreen(root)
        val down = android.os.SystemClock.uptimeMillis()
        for (step in 0..20) {
            val action = when (step) { 0 -> android.view.MotionEvent.ACTION_DOWN; 20 -> android.view.MotionEvent.ACTION_UP; else -> android.view.MotionEvent.ACTION_MOVE }
            val event = android.view.MotionEvent.obtain(down, android.os.SystemClock.uptimeMillis(), action,
                root.width() * (.8f - .6f * step / 20f), root.height() * .6f, 0)
            event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
            assertTrue(automation.injectInputEvent(event, true))
            event.recycle(); android.os.SystemClock.sleep(20)
        }
    }

    private fun capture(name: String) {
        if (InstrumentationRegistry.getArguments().getString("sheetEvidence") != "true") return
        instrumentation.waitForIdleSync()
        val directory = java.io.File(context.getExternalFilesDir(null), "sheet-evidence").apply { mkdirs() }
        val automation = instrumentation.getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        var previous = automation.takeScreenshot()
        var stable: android.graphics.Bitmap? = null
        for (attempt in 0 until 20) {
            android.os.SystemClock.sleep(100)
            val current = automation.takeScreenshot()
            if (current.sameAs(previous)) { previous.recycle(); stable = current; break }
            previous.recycle(); previous = current
        }
        val image = checkNotNull(stable) { "Screenshot did not settle: $name" }
        java.io.File(directory, "$name.png").outputStream().use {
            assertTrue(image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
        }
        image.recycle()
    }

    @Test fun widgetMenuRetainsSizeSelectionAndEveryConditionalAction() = withActivity { activity ->
        val row = WidgetSettingsRow(LockscreenWidget(27, LockscreenWidgetSize.M), "Fixture widget", true)
        var resized: LockscreenWidgetSize? = null
        var moved: Int? = null
        var edited = false
        var removed = false
        var dismissed = false
        fun render(up: Boolean = true, down: Boolean = true, editable: Boolean = true) {
            val frame = java.util.concurrent.CountDownLatch(1)
            instrumentation.runOnMainSync {
                activity.setContent {
                    LockscreenWidgetMenu(row.copy(canEdit = editable), up, down,
                        { resized = it }, { moved = it }, { edited = true }, { removed = true }, { dismissed = true })
                    SideEffect { frame.countDown() }
                }
            }
            assertTrue("Widget fixture did not compose", frame.await(8, java.util.concurrent.TimeUnit.SECONDS))
            ui.await("Widget menu not rendered") { ui.has("Fixture widget") }
        }
        render()
        assertEquals(true, ui.checked(context.getString(R.string.lockscreen_widgets_medium)))
        assertEquals(false, ui.checked(context.getString(R.string.lockscreen_widgets_small)))
        assertEquals(false, ui.checked(context.getString(R.string.lockscreen_widgets_large)))
        ui.click(context.getString(R.string.lockscreen_widgets_small))
        assertEquals(LockscreenWidgetSize.S, resized); assertTrue(dismissed)
        dismissed = false
        ui.click(context.getString(R.string.lockscreen_widgets_up))
        assertEquals(-1, moved); assertTrue(dismissed)
        dismissed = false
        ui.click(context.getString(R.string.lockscreen_widgets_down))
        assertEquals(1, moved); assertTrue(dismissed)
        dismissed = false
        ui.click(context.getString(R.string.lockscreen_widgets_edit))
        assertTrue(edited); assertTrue(dismissed)
        dismissed = false
        ui.click(context.getString(R.string.lockscreen_widgets_remove))
        assertTrue(removed); assertTrue(dismissed)
        render(up = false, down = false, editable = false)
        ui.await("Conditional widget actions were not removed") {
            !ui.has(context.getString(R.string.lockscreen_widgets_up)) &&
                !ui.has(context.getString(R.string.lockscreen_widgets_down)) &&
                !ui.has(context.getString(R.string.lockscreen_widgets_edit))
        }
        assertFalse(ui.has(context.getString(R.string.lockscreen_widgets_up)))
        assertFalse(ui.has(context.getString(R.string.lockscreen_widgets_down)))
        assertFalse(ui.has(context.getString(R.string.lockscreen_widgets_edit)))
        dismissed = false
        ui.back()
        ui.await("Back did not dismiss widget menu") { dismissed }
    }

    @Test fun providerPickForwardsTheExactProviderAndDismisses() = withActivity { activity ->
        val first = WidgetProviderChoice(AppWidgetProviderInfo(), "First provider")
        val second = WidgetProviderChoice(AppWidgetProviderInfo(), "Second provider")
        var picked: WidgetProviderChoice? = null
        var dismissed = false
        instrumentation.runOnMainSync {
            activity.setContent { WidgetProviderSheet(listOf(first, second), { picked = it }, { dismissed = true }) }
        }
        ui.click("Second provider")
        assertSame(second, picked)
        assertTrue(dismissed)
    }

    @Test fun multiChoiceTogglesIndependentlyAndStaysOpenUntilBack() = withActivity { activity ->
        var first by mutableStateOf(true)
        var second by mutableStateOf(false)
        var dismissed = false
        instrumentation.runOnMainSync {
            activity.setContent {
                MultiChoiceSheet("Fixture customization", listOf(
                    Triple("First option", first, { value: Boolean -> first = value }),
                    Triple("Second option", second, { value: Boolean -> second = value })), { dismissed = true })
            }
        }
        ui.await("Multi-choice not rendered") { ui.checked("First option") == true && ui.checked("Second option") == false }
        ui.click("Second option")
        ui.await("Second checkbox did not change") { ui.checked("Second option") == true }
        assertTrue(first); assertTrue(second); assertFalse(dismissed)
        ui.click("First option")
        ui.await("First checkbox did not change independently") { ui.checked("First option") == false }
        assertFalse(first); assertTrue(second); assertFalse(dismissed)
        ui.back()
        ui.await("Back did not dismiss multi-choice") { dismissed }
        assertFalse(first); assertTrue(second)
    }

    private fun withActivity(block: (MainActivity) -> Unit) {
        val activity = instrumentation.startActivitySync(Intent(Intent.ACTION_MAIN).apply {
            setClassName(context.packageName, MainActivity::class.java.name)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }) as MainActivity
        try { block(activity) } finally { instrumentation.runOnMainSync { activity.finish() } }
    }
}
