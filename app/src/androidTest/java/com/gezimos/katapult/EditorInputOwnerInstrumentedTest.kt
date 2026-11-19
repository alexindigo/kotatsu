package com.gezimos.katapult

import android.content.Intent
import android.app.UiAutomation
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.view.WindowInsets
import android.view.inspector.WindowInspector
import android.view.inputmethod.InputMethodManager
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gezimos.katapult.model.AppModel
import com.gezimos.katapult.ui.LocalPalette
import com.gezimos.katapult.ui.PaperPalette
import com.gezimos.katapult.ui.BottomSheet
import com.gezimos.katapult.ui.input.PaperTextField
import com.gezimos.katapult.ui.settings.SettingsPage
import com.gezimos.katapult.ui.settings.SheetAction
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class EditorInputOwnerInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val ui = PlatformUiDriver(instrumentation)
    private fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(
        instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
            .executeShellCommand(command)).bufferedReader().use { it.readText() }
    private fun label(id: Int) = context.getString(id)
    private fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)

    private fun withHome(test: (ActivityScenario<MainActivity>) -> Unit) {
        val preferences = context.getSharedPreferences("katapult_prefs", 0)
        val before = preferences.all.toMap()
        val original = shell("cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME")
            .trim().lineSequence().last()
        shell("cmd package set-home-activity ${context.packageName}/${MainActivity::class.java.name}")
        withFixtureCleanup(
            { shell("cmd package set-home-activity $original"); Unit },
            { main {
                val restore = preferences.edit().clear()
                before.forEach { (key, value) -> when (value) {
                    is Boolean -> restore.putBoolean(key, value)
                    is Int -> restore.putInt(key, value)
                    is Long -> restore.putLong(key, value)
                    is Float -> restore.putFloat(key, value)
                    is String -> restore.putString(key, value)
                    is Set<*> -> restore.putStringSet(key, value.filterIsInstance<String>().toSet())
                } }
                assertTrue(restore.commit())
            } },
        ) {
            ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)
                .setAction(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)).use { scenario ->
                scenario.onActivity { assertTrue("Fixture must enter the real HOME route", it.intent.hasCategory(Intent.CATEGORY_HOME)) }
                withFixtureCleanup({ shell("cmd package set-home-activity $original"); Unit }) {
                    try { test(scenario) }
                    catch (failure: Throwable) {
                        runCatching { captureFailure() }.onFailure { failure.addSuppressed(it) }
                        throw failure
                    }
                }
            }
        }
    }

    private fun setEditorText(text: String) {
        ui.await("Editor missing") { ui.find { it.isEditable } != null }
        val node = ui.find { it.isEditable }!!
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }))
    }

    private fun focusAndEdit(text: String) {
        setEditorText(text)
        ui.await("Native editor did not focus and show IME") {
            ui.find { it.isEditable && it.isFocused } != null && ui.imeVisible()
        }
    }

    private fun released() {
        ui.await("Departed editor retained focus or native IME") {
            var hasImeInsets = false
            main {
                hasImeInsets = WindowInspector.getGlobalWindowViews().any {
                    it.isAttachedToWindow && it.hasWindowFocus() && it.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) == true
                }
            }
            !hasImeInsets && !ui.imeVisible() &&
                !context.getSystemService(InputMethodManager::class.java).isAcceptingText &&
                ui.find { it.isEditable && it.isFocused } == null
        }
    }

    private fun captureFailure() {
        val directory = File(context.getExternalFilesDir(null), "editor-owner-${android.os.SystemClock.uptimeMillis()}")
        check(directory.mkdir())
        for (service in listOf("window", "input_method", "activity")) File(directory, "$service.txt").writeText(shell("dumpsys $service"))
        val bitmap = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).takeScreenshot()
        File(directory, "screen.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    private fun home() {
        shell("input keyevent KEYCODE_HOME")
        ui.await("Real HOME did not reach launcher") { ui.has(label(R.string.all_apps)) }
        released()
    }

    private fun message(scenario: ActivityScenario<MainActivity>) {
        ui.homeMenu()
        ui.click(label(R.string.settings))
        ui.click(label(R.string.cat_lockscreen))
        ui.await("Lock screen missing") { ui.has(label(R.string.lockscreen_widget_desc)) }
        if (!ui.has(label(R.string.lockscreen_message))) ui.click(label(R.string.paper_next_page))
        ui.click(label(R.string.lockscreen_message))
        ui.await("Message missing") { ui.has(label(R.string.save)) }
    }

    @Test fun realHomeReleasesMessageAndRetainsDraftAcrossRecreation() = withHome { scenario ->
        var original = ""
        scenario.onActivity { original = it.viewModel.prefs.lockscreenMessage.orEmpty() }
        message(scenario)
        focusAndEdit("Native owner draft")
        var oldEditor: android.view.View? = null
        var oldInput: android.view.inputmethod.InputConnection? = null
        scenario.onActivity {
            oldEditor = it.window.decorView.findFocus()
            oldInput = oldEditor?.onCreateInputConnection(android.view.inputmethod.EditorInfo())
        }
        assertNotNull("Visible editor has no native input connection", oldInput)
        scenario.recreate()
        ui.await("Recreation lost visible draft") { ui.has("Native owner draft") }
        // Android may preserve the decor across Activity relaunch. The departed
        // editor and its connection, not that reusable container, must retire.
        ui.await("Old editor is still attached") {
            var detached = false
            main { detached = !oldEditor!!.isAttachedToWindow }
            detached
        }
        main { oldInput!!.commitText("Late retired editor", 1) }
        scenario.onActivity { assertEquals("Native owner draft", it.viewModel.lockscreenMessageDraft!!.text) }
        home()
        message(scenario)
        ui.await("HOME discarded unsaved draft") { ui.has("Native owner draft") }
        scenario.onActivity { assertEquals(original, it.viewModel.prefs.lockscreenMessage.orEmpty()) }
        ui.click(label(R.string.cancel)); released()
    }

    @Test fun messageHeaderCancelAndSaveReleaseInput() = withHome { scenario ->
        message(scenario); focusAndEdit("Cancel owner")
        ui.click(label(R.string.paper_back)); released()
        ui.click(label(R.string.lockscreen_message)); focusAndEdit("")
        // Saving the empty value avoids invoking unrelated Action Service setup.
        var original: String? = null
        scenario.onActivity { original = it.viewModel.prefs.lockscreenMessage }
        withFixtureCleanup({ scenario.onActivity { it.viewModel.prefs.lockscreenMessage = original.orEmpty() } }) {
            ui.click(label(R.string.save)); released()
            scenario.onActivity { assertEquals("", it.viewModel.prefs.lockscreenMessage) }
        }
    }

    @Test fun renameRealHomeDismissalAndTargetHandoffReleaseOnlyDepartedInput() = withHome { scenario ->
        val app = AppModel("__owner_first__", "First owner", "")
        fun open() {
            scenario.onActivity { it.viewModel.contextMenuApp = app; it.viewModel.beginRename(app) }
            ui.await("Rename missing") { ui.has(label(R.string.save)) }
        }
        open(); focusAndEdit("Kept owner rename"); home()
        open(); ui.await("Rename draft lost") { ui.has("Kept owner rename") }
        val other = app.copy(packageName = "__owner_second__", label = "Second owner")
        scenario.onActivity { it.viewModel.contextMenuApp = other; it.viewModel.beginRename(other) }
        ui.await("Successor lost focus") { ui.has("Second owner") && ui.find { it.isEditable && it.isFocused } != null && ui.imeVisible() }
        ui.click(label(R.string.cancel)); released()
        open(); focusAndEdit("Dismiss owner")
        ui.click(context.getString(com.gezimos.katapult.paper.R.string.paper_dismiss)); released()
    }

    @Test fun iconSearchAndNumericNavigationReleaseNativeInput() = withHome { scenario ->
        val app = AppModel("__owner_icon__", "Icon owner", "")
        fun openIcons() {
            scenario.onActivity { it.viewModel.contextMenuApp = app }
            ui.click(label(R.string.kotatsu_title)); ui.click(label(R.string.menu_change))
            ui.await("Search missing") { ui.has(label(R.string.search_icons)) }
        }
        openIcons()
        ui.click(label(R.string.search_icons)); focusAndEdit("weather")
        home()
        openIcons()
        ui.click(label(R.string.search_icons)); focusAndEdit("sun")
        ui.click(context.getString(com.gezimos.katapult.paper.R.string.paper_dismiss)); released()
    }

    @Test fun numericImeReallocationRetiresUnplacedInputAndKeepsImmediateValue() = withHome { scenario ->
        scenario.onActivity { it.viewModel.navigateTo(Screen.SETTINGS); it.viewModel.settingsPageRequest = "timed" }
        ui.await("Interval choices missing") { ui.has(label(R.string.screensaver_mode_interval)) }
        ui.click(label(R.string.screensaver_mode_interval))
        ui.await("Numeric editor missing") { ui.find { it.isEditable } != null }
        val sawIme = java.util.concurrent.atomic.AtomicBoolean()
        lateinit var decor: android.view.View
        val listener = android.view.ViewTreeObserver.OnGlobalLayoutListener {
            if (decor.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) == true) sawIme.set(true)
        }
        scenario.onActivity { decor = it.window.decorView; decor.viewTreeObserver.addOnGlobalLayoutListener(listener) }
        withFixtureCleanup({ main { decor.viewTreeObserver.removeOnGlobalLayoutListener(listener) } }) {
            setEditorText("7")
            ui.await("Numeric regression never opened the real IME") { sawIme.get() }
            released()
        }
        ui.click(label(R.string.screensaver_mode_auto)); released()
        scenario.onActivity { assertEquals(7, it.viewModel.prefs.screensaverUpdateMinutes) }
    }

    @Test fun sideNameRealHomeAndCancelReleaseInput() = withHome { scenario ->
        scenario.onActivity { it.viewModel.navigateTo(Screen.SETTINGS); it.viewModel.settingsPageRequest = "side-screen" }
        ui.click(label(R.string.side_name)); focusAndEdit("Side draft")
        scenario.recreate()
        ui.await("Side name draft lost on recreation") { ui.has("Side draft") }
        home()
        scenario.onActivity { it.viewModel.navigateTo(Screen.SETTINGS); it.viewModel.settingsPageRequest = "side-screen" }
        ui.click(label(R.string.side_name)); ui.click(label(R.string.cancel)); released()
    }

    @Test fun sameWindowHandoffAndRetainedOffPageEditorUseSharedOwner() {
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use { scenario ->
            val showFirst = mutableStateOf(true)
            scenario.onActivity { activity -> activity.setContent {
                CompositionLocalProvider(LocalPalette provides PaperPalette.Light(context)) {
                    SettingsPage("Owner fixture", {}, imePadding = true) {
                        if (showFirst.value) PaperTextField("First", {}, autofocus = true,
                            modifier = Modifier.height(60.dp).semantics { contentDescription = "First editor" })
                        else PaperTextField("Second", {}, autofocus = true,
                            modifier = Modifier.height(60.dp).semantics { contentDescription = "Second editor" })
                        repeat(8) { SheetAction("Block $it") {} }
                    }
                }
            } }
            ui.await("First editor not focused") { ui.find { it.isEditable && it.isFocused } != null }
            main { showFirst.value = false }
            ui.await("Old disposal stole successor input") { ui.has("Second editor") && ui.find { it.isEditable && it.isFocused } != null && ui.imeVisible() }
            ui.click(label(R.string.paper_next_page)); released()
        }
    }

    @Test fun dismissedDialogCannotHideActivitySuccessorKeyboard() {
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use { scenario ->
            val dialog = mutableStateOf(true)
            scenario.onActivity { activity -> activity.setContent {
                CompositionLocalProvider(LocalPalette provides PaperPalette.Light(context)) {
                    if (dialog.value) BottomSheet(onDismiss = { dialog.value = false }, imePadding = true) {
                        PaperTextField("Dialog input", {}, autofocus = true,
                            modifier = Modifier.semantics { contentDescription = "Dialog editor" })
                        SheetAction("Continue editing") { dialog.value = false }
                    } else SettingsPage("Activity editor", {}, imePadding = true) {
                        PaperTextField("Successor input", {}, autofocus = true,
                            modifier = Modifier.semantics { contentDescription = "Activity successor" })
                    }
                }
            } }
            ui.await("Dialog input missing") { ui.has("Dialog editor") && ui.imeVisible() }
            ui.click("Continue editing")
            instrumentation.waitForIdleSync()
            ui.await("Dismissed window stole successor input") {
                ui.has("Activity successor") && ui.find { it.isEditable && it.isFocused } != null && ui.imeVisible()
            }
        }
    }
}
