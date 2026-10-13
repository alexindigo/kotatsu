package com.gezimos.katapult

import android.app.Activity
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gezimos.katapult.model.AppModel
import com.gezimos.katapult.util.BitmapHelper
import com.gezimos.katapult.util.SensitiveChoice
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class DraftsAndPickersInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val ui = PlatformUiDriver(instrumentation)
    private val fixture = AppModel("__f7_rename__", "Original name", "")

    private fun edit(text: String) {
        fun find(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (node.isEditable) return node
            for (i in 0 until node.childCount) node.getChild(i)?.let { find(it)?.let { found -> return found } }
            return null
        }
        val automation = instrumentation.getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        ui.await("No editable draft") { automation.rootInActiveWindow?.let(::find) != null }
        assertTrue(find(automation.rootInActiveWindow!!)!!.performAction(
            AccessibilityNodeInfo.ACTION_SET_TEXT,
            Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) },
        ))
        ui.await("Edited draft not rendered") { ui.has(text) }
    }

    @Test fun renameDraftSurvivesHomeAndActivityRecreation() {
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use { scenario ->
            fun open() {
                scenario.onActivity { it.viewModel.screen = Screen.HOME; it.viewModel.contextMenuApp = fixture }
                ui.click(context.getString(R.string.rename))
                ui.await("Rename sheet not rendered") { ui.has(context.getString(R.string.save)) }
            }
            open()
            edit("Unsaved rename F7")
            scenario.onActivity { it.viewModel.onHomeIntent() }
            ui.await("HOME did not close rename") { !ui.has(context.getString(R.string.save)) }
            open()
            ui.await("HOME lost unsaved rename") { ui.has("Unsaved rename F7") }
            scenario.recreate()
            ui.await("Recreation lost unsaved rename") { ui.has("Unsaved rename F7") }
            scenario.onActivity { assertNull(it.viewModel.prefs.getAppRename(fixture.key)) }
            ui.click(context.getString(R.string.cancel))
            open()
            ui.await("Explicit cancel did not discard rename") { ui.has(fixture.label) && !ui.has("Unsaved rename F7") }
            edit("Different target draft")
            scenario.onActivity {
                val other = fixture.copy(packageName = "__f7_other__", label = "Other app")
                it.viewModel.beginRename(other)
                it.viewModel.contextMenuApp = other
            }
            ui.await("Rename leaked to another target") { ui.has("Other app") && !ui.has("Different target draft") }
            ui.click(context.getString(R.string.cancel))
        }
    }

    @Test fun messageDraftSurvivesHomeAndRecreationWithoutSavingAndCancelStillDiscards() {
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use { scenario ->
            var original: String? = null
            scenario.onActivity { original = it.viewModel.prefs.lockscreenMessage }
            fun open() {
                scenario.onActivity { it.viewModel.navigateTo(Screen.SETTINGS) }
                ui.click(context.getString(R.string.cat_lockscreen))
                ui.scrollTo(context.getString(R.string.lockscreen_message))
                ui.click(context.getString(R.string.lockscreen_message))
                ui.await("Message editor not rendered") { ui.has(context.getString(R.string.save)) }
            }
            open()
            edit("Unsaved message F7")
            scenario.onActivity { it.viewModel.onHomeIntent() }
            ui.await("HOME did not close message") { !ui.has(context.getString(R.string.save)) }
            open()
            ui.await("HOME lost message draft") { ui.has("Unsaved message F7") }
            scenario.recreate()
            ui.await("Recreation lost message draft") { ui.has("Unsaved message F7") }
            scenario.onActivity { assertEquals(original, it.viewModel.prefs.lockscreenMessage) }
            ui.click(context.getString(R.string.cancel))
            scenario.onActivity { it.viewModel.beginLockscreenMessage() }
            ui.await("Explicit cancel kept message draft") { ui.has(context.getString(R.string.save)) && !ui.has("Unsaved message F7") }
            scenario.onActivity { assertEquals(original.orEmpty(), it.viewModel.lockscreenMessageDraft!!.text) }
            ui.click(context.getString(R.string.cancel))
        }
    }

    /** Intercept only the external picker; keep the real launcher's outstanding request. */
    private fun pending(scenario: ActivityScenario<MainActivity>, index: Int, action: String,
                        launch: (MainActivity) -> Unit): Int {
        val filter = IntentFilter(action).apply { addDataType("*/*"); addCategory(Intent.CATEGORY_OPENABLE) }
        val monitor = instrumentation.addMonitor(filter, null, true)
        var request = 0
        try {
            scenario.onActivity { activity ->
                launch(activity)
                val saved = Bundle()
                activity.activityResultRegistry.onSaveInstanceState(saved)
                val key = "activity_rq#$index"
                val keys = saved.getStringArrayList("KEY_COMPONENT_ACTIVITY_REGISTERED_KEYS")!!
                val codes = saved.getIntegerArrayList("KEY_COMPONENT_ACTIVITY_REGISTERED_RCS")!!
                assertTrue("Activity-owned picker is not registered", key in keys)
                assertTrue("Picker request was not launched", key in saved.getStringArrayList("KEY_COMPONENT_ACTIVITY_LAUNCHED_KEYS")!!)
                request = codes[keys.indexOf(key)]
                activity.viewModel.onHomeIntent()
            }
            assertEquals("External picker was not intercepted", 1, monitor.hits)
        } finally { instrumentation.removeMonitor(monitor) }
        scenario.recreate()
        return request
    }

    private fun deliver(scenario: ActivityScenario<MainActivity>, request: Int, uri: Uri?) {
        scenario.onActivity {
            assertEquals(Screen.HOME, it.viewModel.screen)
            assertTrue(it.activityResultRegistry.dispatchResult(request,
                if (uri == null) Activity.RESULT_CANCELED else Activity.RESULT_OK,
                uri?.let { data -> Intent().setData(data) }))
        }
    }

    @Test fun exportResultAfterHomeAndRecreationWritesConfigurationAndCancelDoesNotWrite() {
        val file = File(context.filesDir, "f7-export-${UUID.randomUUID()}.json")
        try {
            ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use { scenario ->
                val request = pending(scenario, 3, Intent.ACTION_CREATE_DOCUMENT) { it.configExporter.launch(file.name) }
                deliver(scenario, request, Uri.fromFile(file))
                assertEquals("katapult_config", JSONObject(file.readText()).getString("_type"))
                val contents = file.readText()
                val cancel = pending(scenario, 3, Intent.ACTION_CREATE_DOCUMENT) { it.configExporter.launch(file.name) }
                deliver(scenario, cancel, null)
                assertEquals(contents, file.readText())
            }
        } finally { file.delete() }
    }

    @Test fun importResultAndPreviewSurviveHomeAndRecreationUntilExplicitCancel() {
        val file = File(context.filesDir, "f7-import-${UUID.randomUUID()}.json")
        try {
            ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use { scenario ->
                var local = false
                scenario.onActivity {
                    local = it.viewModel.prefs.sensitiveChoice(SensitiveChoice.MEDIA_INFO)
                    file.writeText(JSONObject(it.viewModel.prefs.exportToJson(context))
                        .put(SensitiveChoice.MEDIA_INFO.key, !local).toString())
                }
                val request = pending(scenario, 4, Intent.ACTION_OPEN_DOCUMENT) { it.configImporter.launch(arrayOf("application/json")) }
                deliver(scenario, request, Uri.fromFile(file))
                val text = file.readText()
                scenario.onActivity {
                    assertEquals(text, it.viewModel.pendingImportText)
                    assertEquals(!local, it.viewModel.pendingImportPreview!!.changes[SensitiveChoice.MEDIA_INFO.key])
                    it.viewModel.navigateTo(Screen.SETTINGS)
                }
                ui.await("Import preview not rendered") { ui.has(context.getString(R.string.policy_import_confirmation)) }
                scenario.onActivity { it.viewModel.onHomeIntent() }
                ui.await("HOME did not remove preview") { !ui.has(context.getString(R.string.policy_import_confirmation)) }
                scenario.recreate()
                scenario.onActivity {
                    assertEquals(text, it.viewModel.pendingImportText)
                    assertEquals(local, it.viewModel.prefs.sensitiveChoice(SensitiveChoice.MEDIA_INFO))
                    it.viewModel.navigateTo(Screen.SETTINGS)
                }
                ui.await("HOME/recreation lost import confirmation") { ui.has(context.getString(R.string.policy_import_confirmation)) }
                ui.click(context.getString(R.string.cancel))
                scenario.onActivity {
                    assertNull(it.viewModel.pendingImportText)
                    assertNull(it.viewModel.pendingImportPreview)
                    assertEquals(local, it.viewModel.prefs.sensitiveChoice(SensitiveChoice.MEDIA_INFO))
                }
            }
        } finally { file.delete() }
    }

    @Test fun screensaverPickerResultAfterHomeAndRecreationReachesObservedPathAndBitmap() {
        val source = File(context.filesDir, "f7-image-${UUID.randomUUID()}.png")
        val destination = File(context.filesDir, "screensaver_wallpaper.png")
        val oldBytes = destination.takeIf { it.isFile }?.readBytes()
        val raw = context.getSharedPreferences("katapult_prefs", android.content.Context.MODE_PRIVATE)
        val key = "screensaver_wallpaper_path"
        val oldPath = raw.getString(key, null)
        val bitmap = Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.CYAN) }
        source.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
        try {
            ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use { scenario ->
                val request = pending(scenario, 5, Intent.ACTION_GET_CONTENT) { it.screensaverWallpaperPicker.launch("image/*") }
                deliver(scenario, request, Uri.fromFile(source))
                var model: MainViewModel? = null
                scenario.onActivity { model = it.viewModel }
                ui.await("Wallpaper result did not reach observable state") {
                    model!!.screensaverWallpaperPath == destination.path && destination.isFile &&
                        BitmapHelper.decodeFile(destination.path, 64)?.getPixel(12, 12) == Color.CYAN
                }
                assertEquals(destination.path, model!!.prefs.screensaverWallpaperPath)
                assertEquals(Color.CYAN, BitmapHelper.decodeFile(model!!.screensaverWallpaperPath!!, 64)!!.getPixel(12, 12))
                val cancel = pending(scenario, 5, Intent.ACTION_GET_CONTENT) { it.screensaverWallpaperPicker.launch("image/*") }
                deliver(scenario, cancel, null)
                scenario.onActivity { assertEquals(destination.path, it.viewModel.screensaverWallpaperPath) }
            }
        } finally {
            source.delete()
            if (oldBytes == null) destination.delete() else destination.writeBytes(oldBytes)
            assertTrue(raw.edit().apply { if (oldPath == null) remove(key) else putString(key, oldPath) }.commit())
        }
    }
}
