package com.gezimos.katapult

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gezimos.katapult.util.DeviceHelper
import com.gezimos.katapult.util.PrefsManager
import com.gezimos.katapult.lockscreen.ScreensaverActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LauncherPreviewInstrumentedTest {
    @Test fun publicPreviewAndDirtyOrderDecisions() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val expected = InstrumentationRegistry.getArguments().getString("expectedApplicationId")
        assertEquals("io.github.alexindigo.kotatsu.debug", expected)
        assertEquals(expected, context.packageName)
        assertFalse(DeviceHelper.isDefaultLauncher(context))
        assertFalse(NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName))
        for (permission in listOf(Manifest.permission.READ_SMS, Manifest.permission.READ_CALL_LOG, Manifest.permission.WRITE_CALL_LOG)) {
            assertEquals(PackageManager.PERMISSION_DENIED, context.checkSelfPermission(permission))
        }
        val activity = instrumentation.startActivitySync(Intent(Intent.ACTION_MAIN).apply {
            setClassName(context.packageName, MainActivity::class.java.name)
            addCategory(Intent.CATEGORY_LAUNCHER)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }) as MainActivity
        val model = activity.viewModel
        val ui = PlatformUiDriver(instrumentation)
        val badgeChoice = model.prefs.notificationIndicators
        ui.await("Neither onboarding nor HOME rendered") { ui.has("Finish") || ui.has("Apps") }
        if (ui.has("Finish")) {
            ui.click("Finish")
            ui.await("Finish did not render HOME") { model.screen == Screen.HOME && ui.has("Apps") }
        }
        assertEquals(badgeChoice, model.prefs.notificationIndicators)
        assertFalse(DeviceHelper.isDefaultLauncher(context))
        ui.click("Apps")
        ui.await("App grid did not render") { model.screen == Screen.ALL_APPS && model.orderedApps.size >= 2 }
        assertFalse(model.reorderMode)
        val baseline = model.orderedApps.map { it.key }
        val first = model.orderedApps[0]
        val second = model.orderedApps[1]
        assertNotEquals("Synthetic first grid labels must be distinguishable", first.label, second.label)
        val saved = model.prefs.loadAppOrder()?.toList()
        val rawPrefs = context.getSharedPreferences("katapult_prefs", 0)
        val originalOrderJson = rawPrefs.all["app_order"]
        assertTrue("Preserve unexpected stored order types", originalOrderJson == null || originalOrderJson is String)
        var generatedIcon: Pair<com.gezimos.katapult.model.AppModel, java.io.File>? = null
        try {
        ui.click(first.label, longPress = true)
        ui.click("Reorder")
        ui.await("Reorder mode missing") { model.reorderMode }
        ui.click(second.label)
        ui.await("Public swap did not reach rendered model") { model.orderedApps[0].key == second.key }
        val draft = model.orderedApps.map { it.key }
        instrumentation.runOnMainSync { model.loadApps() }
        assertEquals("Resume/late refresh overwrote draft", draft, model.orderedApps.map { it.key })

        ui.back()
        ui.await("Dirty Back omitted the decision sheet") { ui.has("Save app order changes?") }
        ui.click("Cancel")
        ui.await("Cancel exited the editor") { model.reorderMode && !model.showReorderExit }
        assertEquals(draft, model.orderedApps.map { it.key })
        assertEquals(saved, model.prefs.loadAppOrder())

        ui.back()
        ui.outside("Save app order changes?")
        ui.await("Outside dismissal did not cancel") { model.reorderMode && !model.showReorderExit }
        assertEquals(draft, model.orderedApps.map { it.key })
        assertEquals(saved, model.prefs.loadAppOrder())

        context.startActivity(Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            setClassName(context.packageName, MainActivity::class.java.name)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        })
        ui.await("New HOME intent silently resolved dirty work") { ui.has("Save app order changes?") }
        ui.click("Cancel")
        ui.await("HOME-intent Cancel lost editor") { model.reorderMode && !model.showReorderExit }
        assertEquals(Screen.ALL_APPS, model.screen)
        assertEquals(draft, model.orderedApps.map { it.key })

        ui.back()
        ui.click("Discard")
        ui.await("Discard did not exit reorder") { !model.reorderMode }
        assertEquals(baseline, model.orderedApps.map { it.key })
        assertEquals("Discard wrote saved order", saved, model.prefs.loadAppOrder())

        ui.click(first.label, longPress = true)
        ui.click("Reorder")
        ui.click(second.label)
        ui.back()
        ui.click("Save")
        ui.await("Save did not persist draft") { !model.reorderMode && model.prefs.loadAppOrder() == draft }
        assertEquals(draft, model.orderedApps.map { it.key })
        ui.await("Saved order did not reach the visible grid") { ui.has(first.label) && ui.has(second.label) }

        ui.click(second.label, longPress = true)
        ui.click("Reorder")
        ui.await("Clean editor did not render before Back") { model.reorderMode && ui.has("SAVE") }
        ui.back()
        ui.await("Clean reorder exit prompted or stayed editing") { !model.reorderMode && !model.showReorderExit }
        assertEquals(draft, model.prefs.loadAppOrder())
        assertFalse(DeviceHelper.isDefaultLauncher(context))
        assertEquals(PackageManager.PERMISSION_DENIED, context.checkSelfPermission(Manifest.permission.READ_SMS))

        fun gridReadback() {
            val labels = model.orderedApps.take(model.appsPerPage).map { it.label }
            ui.await("Working order did not reach the rendered grid") { ui.gridLabels(labels) == labels }
        }
        fun enterEditor() {
            val label = model.orderedApps[0].label
            ui.await("Grid is not ready to enter editor") { ui.has(label) && !model.reorderMode }
            ui.click(label, longPress = true)
            ui.click("Reorder")
            ui.await("Editor footer missing") { model.reorderMode && ui.has("SAVE") }
        }
        fun case(name: String) { Log.i("KotatsuAcceptance", "PASS $name") }

        // Selecting and reversing swaps are clean, and must not write an order.
        enterEditor()
        ui.click(model.orderedApps[1].label)
        ui.await("First reversed-swap step missing") { model.orderedApps.map { it.key } != draft }
        ui.click(model.orderedApps[0].label)
        ui.click(model.orderedApps[1].label)
        ui.await("Reversed swap did not restore baseline") { model.orderedApps.map { it.key } == draft }
        gridReadback()
        ui.back()
        ui.await("Reversed swaps prompted or left editor active") { !model.reorderMode && !model.showReorderExit }
        assertEquals(draft, model.prefs.loadAppOrder())
        case("reversed-swaps-clean-exit")

        // Reset confirmation changes only the working draft until Save is chosen.
        // Establish a non-alphabetical saved baseline through the public Save action.
        val resetNeedsTemporaryOrder = draft == model.orderedApps.sortedBy { it.label.lowercase() }.map { it.key }
        if (resetNeedsTemporaryOrder) {
            enterEditor()
            ui.click(model.orderedApps[1].label)
            ui.await("Temporary Reset baseline swap missing") { model.orderedApps.map { it.key } != draft }
            ui.click("SAVE")
            ui.await("Temporary Reset baseline Save failed") { !model.reorderMode && model.prefs.loadAppOrder() != draft }
        }
        val resetBaseline = model.orderedApps.map { it.key }
        enterEditor()
        ui.click("Reset order")
        ui.await("Reset confirmation missing") { ui.has("Reset app order to alphabetical?") }
        ui.back()
        ui.await("Reset dialog Back left the editor") { model.reorderMode && !ui.has("Reset app order to alphabetical?") }
        assertEquals(resetBaseline, model.orderedApps.map { it.key })
        ui.click("Reset order")
        ui.click("Reset")
        val alphabetical = model.orderedApps.sortedBy { it.label.lowercase() }.map { it.key }
        ui.await("Reset did not update draft") { model.orderedApps.map { it.key } == alphabetical }
        assertNotEquals(resetBaseline, alphabetical)
        assertEquals("Reset persisted before explicit Save", resetBaseline, model.prefs.loadAppOrder())
        gridReadback()
        ui.back()
        ui.await("Dirty Reset omitted resolution") { ui.has("Save app order changes?") }
        ui.back()
        ui.await("Decision-sheet Back discarded work") { model.reorderMode && !model.showReorderExit }
        assertEquals(alphabetical, model.orderedApps.map { it.key })
        ui.back()
        ui.click("Discard")
        ui.await("Reset Discard failed") { !model.reorderMode && model.orderedApps.map { it.key } == resetBaseline }
        assertEquals(resetBaseline, model.prefs.loadAppOrder())
        if (resetNeedsTemporaryOrder) {
            enterEditor()
            ui.click(model.orderedApps[1].label)
            ui.await("Reset baseline restoration swap failed") { model.orderedApps.map { it.key } == draft }
            ui.click("SAVE")
            ui.await("Reset baseline restoration Save failed") { !model.reorderMode && model.prefs.loadAppOrder() == draft }
        }
        gridReadback()
        case("reset-draft-confirmation-dialog-back-discard")

        enterEditor()
        ui.click(model.orderedApps[1].label)
        ui.await("Dirty draft missing before swipe") { model.orderedApps.map { it.key } != draft }
        val controlledDraft = model.orderedApps.map { it.key }
        assertFalse("Swipe-to-HOME case needs retained non-wrapping configuration", model.prefs.infiniteScroll)
        assertEquals(0, model.currentPage)
        ui.swipeToHome(model.prefs.verticalAppGestures)
        ui.await("Dirty swipe silently navigated") { ui.has("Save app order changes?") }
        ui.click("Cancel")
        ui.await("Swipe Cancel lost editor") { model.reorderMode && !model.showReorderExit }
        assertEquals(Screen.ALL_APPS, model.screen)
        assertEquals(controlledDraft, model.orderedApps.map { it.key })
        assertEquals(draft, model.prefs.loadAppOrder())
        gridReadback()
        case("dirty-swipe-cancel")

        // An actual external activity pause/resume exercises MainActivity.onResume/loadApps.
        context.startActivity(Intent(android.provider.Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        ui.await("External Settings did not become visible") { ui.packageVisible("com.android.settings") }
        ui.back()
        ui.await("Real resume lost the editor") { ui.packageVisible(context.packageName) && ui.has("SAVE") }
        assertSame(model, activity.viewModel)
        assertEquals(controlledDraft, model.orderedApps.map { it.key })
        assertEquals(draft, model.prefs.loadAppOrder())
        gridReadback()
        case("actual-activity-resume-preserves-draft")

        // Exercise the real IO -> Main callback, not a direct loadApps stand-in.
        val iconApp = model.orderedApps[0]
        assertNull("Preserve any pre-existing custom icon", model.prefs.getIconOverride(iconApp.key))
        val safeIconKey = iconApp.key.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val iconName = if (safeIconKey == iconApp.key) safeIconKey
            else "${safeIconKey.take(60)}_${iconApp.key.hashCode().toUInt().toString(16)}"
        val fixtureIcon = java.io.File(context.filesDir, "icons/$iconName.png")
        assertFalse("Preserve any pre-existing orphaned icon file", fixtureIcon.exists())
        generatedIcon = iconApp to fixtureIcon
        val iconRefresh = model.shortcutRefresh
        val bitmap = android.graphics.Bitmap.createBitmap(32, 32, android.graphics.Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.BLACK)
        instrumentation.runOnMainSync { model.saveMaterialIcon(iconApp.key, bitmap) }
        ui.await("Actual icon worker/Main callback did not complete") {
            model.shortcutRefresh > iconRefresh && model.prefs.getIconOverride(iconApp.key) == "file:${fixtureIcon.absolutePath}"
        }
        bitmap.recycle()
        assertTrue(fixtureIcon.isFile)
        assertEquals(controlledDraft, model.orderedApps.map { it.key })
        assertEquals(draft, model.prefs.loadAppOrder())
        gridReadback()
        case("actual-icon-worker-callback-preserves-rendered-draft")

        instrumentation.runOnMainSync { model.startScreensaver(activity) }
        ui.await("App-controlled screensaver exit omitted the dirty decision") { ui.has("Save app order changes?") }
        assertFalse(ScreensaverActivity.isShowing)
        ui.click("Cancel")
        ui.await("Screensaver Cancel did not retain editor") { model.reorderMode && !model.showReorderExit }
        assertEquals(controlledDraft, model.orderedApps.map { it.key })
        assertEquals(draft, model.prefs.loadAppOrder())
        assertFalse(ScreensaverActivity.isShowing)
        gridReadback()
        case("app-controlled-screensaver-cancel-preserves-draft")

        // Launch the installed shortcut's real compiled Intent, rather than guessed source text.
        val info = context.packageManager.getActivityInfo(ComponentName(context, MainActivity::class.java), PackageManager.GET_META_DATA)
        val shortcutXml = context.resources.getXml(info.metaData.getInt("android.app.shortcuts"))
        var packagedIntent: Intent? = null
        shortcutXml.use { xml ->
            while (xml.eventType != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                if (xml.eventType == org.xmlpull.v1.XmlPullParser.START_TAG && xml.name == "intent") {
                    val namespace = "http://schemas.android.com/apk/res/android"
                    assertEquals(expected, xml.getAttributeValue(namespace, "targetPackage"))
                    assertEquals(ScreensaverActivity::class.java.name, xml.getAttributeValue(namespace, "targetClass"))
                    packagedIntent = Intent(xml.getAttributeValue(namespace, "action")).setClassName(
                        xml.getAttributeValue(namespace, "targetPackage"), xml.getAttributeValue(namespace, "targetClass"))
                }
                xml.next()
            }
        }
        assertNotNull("Installed static shortcut omitted Intent", packagedIntent)
        context.startActivity(packagedIntent!!.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        ui.await("Packaged shortcut did not show Kotatsu screensaver") { ScreensaverActivity.isShowing && ui.packageVisible(context.packageName) && !ui.has("SAVE") }
        ui.back()
        ui.await("Packaged shortcut return lost launcher task") { !ScreensaverActivity.isShowing && ui.has("SAVE") }
        assertSame(model, activity.viewModel)
        assertEquals(controlledDraft, model.orderedApps.map { it.key })
        gridReadback()
        context.startActivity(Intent().setClassName(context.packageName, ScreensaverLaunchActivity::class.java.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        ui.await("Screensaver launch task did not render") { ScreensaverActivity.isShowing && !ui.has("SAVE") }
        ui.back()
        ui.await("Screensaver task return lost editor") { !ScreensaverActivity.isShowing && ui.has("SAVE") }
        assertSame(model, activity.viewModel)
        assertEquals(controlledDraft, model.orderedApps.map { it.key })
        assertEquals(draft, model.prefs.loadAppOrder())
        gridReadback()
        case("packaged-shortcut-and-launch-task-return-preserve-draft")
        ui.back()
        ui.click("Discard")
        ui.await("Controlled draft Discard failed") { !model.reorderMode }
        ui.await("Grid missing before restoring the synthetic icon") { ui.has(iconApp.label) }
        ui.click(iconApp.label, longPress = true)
        ui.click("Change Icon")
        ui.click("Reset to Default")
        ui.await("Public reset did not restore the original icon") {
            model.contextMenuApp == null && model.prefs.getIconOverride(iconApp.key) == null && !fixtureIcon.exists()
        }
        gridReadback()
        case("synthetic-icon-restored-through-public-ui")

        // IME Back is handled before dismissing the rename dialog or leaving the grid.
        val renameLabel = model.orderedApps[0].label
        ui.click(renameLabel, longPress = true)
        ui.click("Rename")
        ui.await("Rename IME did not render") { model.showRenameDialog && ui.imeVisible() }
        ui.back()
        ui.await("IME Back dismissed the dialog") { !ui.imeVisible() && model.showRenameDialog }
        assertEquals(Screen.ALL_APPS, model.screen)
        ui.back()
        ui.await("Dialog Back left grid or remained open") { !model.showRenameDialog && model.contextMenuApp == null }
        assertEquals(Screen.ALL_APPS, model.screen)
        assertEquals(renameLabel, model.orderedApps[0].label)
        case("ime-and-dialog-back-priority")

        ui.back()
        ui.await("Internal grid Back did not reach HOME") { ui.has("Apps") }
        val activation = listOf(model.prefs.appSortMode, model.prefs.doubleTapAction,
            model.prefs.lockscreenWidget, model.prefs.lockscreenMusicWidget, model.prefs.screensaverEnabled)
        assertEquals(listOf(PrefsManager.APP_SORT_MANUAL, PrefsManager.DOUBLE_TAP_OFF, false, false, false), activation)
        ui.homeMenu()
        ui.click("Settings")
        ui.await("Kotatsu Settings index missing") { model.screen == Screen.SETTINGS && ui.has("Notifications") }
        assertEquals(badgeChoice, model.prefs.notificationIndicators)
        ui.click("Notifications")
        ui.await("Notifications category missing") { ui.has("Notification Indicators") }
        val choiceBefore = model.prefs.notificationIndicators
        ui.click("Notification Indicators")
        ui.await("Explicit badge choice was not stored") { model.prefs.notificationIndicators != choiceBefore }
        if (!choiceBefore) {
            ui.await("Optional notification access screen missing") { ui.packageVisible("com.android.settings") }
            ui.back()
        }
        ui.await("Badge choice UI did not return") { ui.has("Notification Indicators") }
        // Wait through multiple status polling periods; no access is granted.
        android.os.SystemClock.sleep(1700)
        assertEquals(!choiceBefore, model.prefs.notificationIndicators)
        assertFalse(NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName))
        ui.click("Notification Indicators")
        ui.await("Original badge choice was not restored") { model.prefs.notificationIndicators == choiceBefore }
        if (choiceBefore) {
            ui.await("Optional status screen missing on explicit enable") { ui.packageVisible("com.android.settings") }
            ui.back()
        }
        ui.await("Settings category did not return") { ui.has("Notification Indicators") }
        android.os.SystemClock.sleep(1700)
        assertEquals(choiceBefore, model.prefs.notificationIndicators)
        ui.back()
        ui.await("Category Back skipped Settings index") { model.screen == Screen.SETTINGS && ui.has("Appearance") && !ui.has("Notification Indicators") }
        assertEquals(activation, listOf(model.prefs.appSortMode, model.prefs.doubleTapAction,
            model.prefs.lockscreenWidget, model.prefs.lockscreenMusicWidget, model.prefs.screensaverEnabled))
        case("badge-choice-without-access-restored-and-category-back-default-fidelity")
        ui.back()
        ui.await("Settings index Back did not reach HOME") { model.screen == Screen.HOME && ui.has("Apps") }
        assertFalse(DeviceHelper.isDefaultLauncher(context))
        for (permission in listOf(Manifest.permission.READ_SMS, Manifest.permission.READ_CALL_LOG, Manifest.permission.WRITE_CALL_LOG)) {
            assertEquals(PackageManager.PERMISSION_DENIED, context.checkSelfPermission(permission))
        }
        } finally {
            instrumentation.runOnMainSync {
                // Every draft in this test follows the initial !reorderMode assertion.
                if (model.reorderMode) model.discardReorderExit()
                generatedIcon?.let { (app, file) ->
                    if (model.prefs.getIconOverride(app.key) == "file:${file.absolutePath}") model.clearIconOverride(app.key)
                }
                val editor = rawPrefs.edit()
                if (originalOrderJson is String) editor.putString("app_order", originalOrderJson)
                else editor.remove("app_order")
                editor.commit()
                model.loadApps()
            }
        }
    }
}
