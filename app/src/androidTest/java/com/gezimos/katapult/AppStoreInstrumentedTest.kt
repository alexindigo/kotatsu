package com.gezimos.katapult

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.view.WindowInsets
import androidx.activity.compose.setContent
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gezimos.katapult.ui.SettingsScreen
import com.gezimos.katapult.ui.settings.AppStorePackages
import com.gezimos.katapult.ui.settings.openAppStoreIntent
import com.gezimos.katapult.util.PrefsManager
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(AndroidJUnit4::class)
class AppStoreInstrumentedTest {
    private class Packages(vararg installed: String) : AppStorePackages {
        @Volatile var installed = installed.toSet()
        val launchRequests = CopyOnWriteArrayList<String>()
        override fun isInstalled(packageName: String) = packageName in installed
        override fun launchIntent(packageName: String): Intent {
            launchRequests.add(packageName)
            return Intent(Intent.ACTION_MAIN).setClassName(packageName, "$packageName.Launcher")
        }
    }

    private fun withSettings(packages: Packages, canOpen: Boolean = true,
        test: (MainActivity, PlatformUiDriver, List<Intent>) -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val shared = context.getSharedPreferences("katapult_prefs", Context.MODE_PRIVATE)
        val before = shared.all
        val intents = CopyOnWriteArrayList<Intent>()
        var activity: MainActivity? = null
        try {
            instrumentation.runOnMainSync {
                shared.edit().putInt("theme_mode", PrefsManager.THEME_LIGHT).commit()
            }
            activity = instrumentation.startActivitySync(Intent(Intent.ACTION_MAIN).apply {
                setClassName(context.packageName, MainActivity::class.java.name)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }) as MainActivity
            val current = activity
            instrumentation.runOnMainSync {
                // The 480×800 Kompakt has hardware navigation, unlike this AVD's bottom bar.
                current.window.insetsController?.hide(WindowInsets.Type.navigationBars())
                val vm = ViewModelProvider(current)[MainViewModel::class.java]
                vm.resolveLockscreenReEnable()
                current.setContent {
                    SettingsScreen(vm, current.configExporter, current.configImporter, current.screensaverWallpaperPicker,
                        packages, openStoreIntent = { intent ->
                            intents.add(Intent(intent))
                            if (canOpen) true else openAppStoreIntent(current, Intent(intent).setPackage("kotatsu.test.no.browser"))
                        })
                }
            }
            val ui = PlatformUiDriver(instrumentation)
            ui.await("App Store is missing from contents") { ui.has(context.getString(R.string.app_store_title)) }
            instrumentation.runOnMainSync {
                current.window.insetsController?.hide(WindowInsets.Type.navigationBars())
            }
            instrumentation.waitForIdleSync()
            ui.await("Neither contents nor the emulator fullscreen hint appeared") {
                ui.has("GOT IT") || ui.has("Got it") ||
                    ui.has(context.getString(if (packages.installed.isEmpty()) R.string.app_store_absent else R.string.app_store_open))
            }
            if (ui.has("GOT IT")) ui.click("GOT IT") else if (ui.has("Got it")) ui.click("Got it")
            ui.await("App Store summary did not render in the phone-sized contents") {
                ui.has(context.getString(if (packages.installed.isEmpty()) R.string.app_store_absent else R.string.app_store_open))
            }
            test(current, ui, intents)
        } finally {
            instrumentation.runOnMainSync {
                activity?.finish()
                val edit = shared.edit().clear()
                before.forEach { (key, value) -> when (value) {
                    is Boolean -> edit.putBoolean(key, value)
                    is Int -> edit.putInt(key, value)
                    is Long -> edit.putLong(key, value)
                    is Float -> edit.putFloat(key, value)
                    is String -> edit.putString(key, value)
                    is Set<*> -> edit.putStringSet(key, value.filterIsInstance<String>().toSet())
                } }
                assertTrue(edit.commit())
            }
        }
    }

    private fun text(id: Int) = InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

    private fun capture(name: String) {
        if (InstrumentationRegistry.getArguments().getString("appStoreEvidence") != "true") return
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "app-store-evidence").apply { mkdirs() }
        val automation = instrumentation.getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        var previous = automation.takeScreenshot()
        var stable: Bitmap? = null
        for (attempt in 0 until 20) {
            android.os.SystemClock.sleep(100)
            val current = automation.takeScreenshot()
            if (current.sameAs(previous)) { previous.recycle(); stable = current; break }
            previous.recycle()
            previous = current
        }
        val image = checkNotNull(stable) { "Screenshot did not settle: $name" }
        File(directory, "$name.png").outputStream().use {
            assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
        image.recycle()
    }

    private fun installedLaunches(packageName: String, packages: Packages) = withSettings(packages) { _, ui, intents ->
        assertTrue(ui.has(text(R.string.app_store_open)))
        val row = ui.findAncestor(text(R.string.app_store_title)) { it.isClickable }!!
        assertTrue(row.isEnabled)
        assertTrue(row.actionList.any { it.label?.toString() == text(R.string.paper_external) })
        ui.click(text(R.string.app_store_title))
        ui.await("Store launch did not reach the handoff") { intents.size == 1 }
        assertEquals(listOf(packageName), packages.launchRequests)
        assertEquals(packageName, intents.single().component?.packageName)
        assertEquals(Intent.ACTION_MAIN, intents.single().action)
        assertFalse(ui.has(text(R.string.app_store_missing_title)))
    }

    @Test fun fullFdroidLaunchIntentIsRequestedWhenBothAreInstalled() =
        installedLaunches("org.fdroid.fdroid", Packages("org.fdroid.fdroid", "org.fdroid.basic"))

    @Test fun basicLaunchesIndependentlyOfTheHiddenAppGrid() =
        installedLaunches("org.fdroid.basic", Packages("org.fdroid.basic"))

    @Test fun missingStateOpensThePageAndCancelClosesWithoutLaunching() = withSettings(Packages()) { _, ui, intents ->
        assertTrue(ui.has(text(R.string.app_store_absent)))
        assertTrue(ui.findAncestor(text(R.string.app_store_title)) { it.isClickable }!!.isEnabled)
        capture("root-missing")
        ui.click(text(R.string.app_store_title))
        ui.await("Missing-app sheet did not open") { ui.has(text(R.string.app_store_missing_title)) }
        assertTrue(ui.has(text(R.string.app_store_missing_body)))
        assertTrue(ui.has(text(R.string.app_store_open_website)))
        capture("sheet-missing")
        ui.click(text(R.string.cancel))
        ui.await("Cancel did not return to contents") { !ui.has(text(R.string.app_store_missing_title)) && ui.has(text(R.string.app_store_title)) }
        assertTrue(intents.isEmpty())
    }

    @Test fun websiteActionHandsOffTheExactHttpsViewIntent() = withSettings(Packages()) { _, ui, intents ->
        ui.click(text(R.string.app_store_title))
        ui.click(text(R.string.app_store_open_website))
        ui.await("Website handoff was not requested") { intents.size == 1 }
        assertEquals(Intent.ACTION_VIEW, intents.single().action)
        assertEquals("https://f-droid.org/", intents.single().dataString)
        assertNull(intents.single().component)
        ui.await("Successful handoff did not close the sheet") { !ui.has(text(R.string.app_store_missing_title)) }
    }

    @Test fun noResolvingBrowserExplainsTheFailureAndStillAllowsCancel() = withSettings(Packages(), false) { _, ui, intents ->
        ui.click(text(R.string.app_store_title))
        ui.click(text(R.string.app_store_open_website))
        ui.await("No-browser failure was not explained in the sheet") { ui.has(text(R.string.app_store_no_browser)) }
        assertEquals(Intent.ACTION_VIEW, intents.single().action)
        assertEquals("https://f-droid.org/", intents.single().dataString)
        assertTrue(ui.has(text(R.string.app_store_missing_title)))
        capture("sheet-no-browser")
        ui.click(text(R.string.cancel))
        ui.await("Failure sheet did not close") { ui.has(text(R.string.app_store_title)) && !ui.has(text(R.string.app_store_no_browser)) }
    }

    @Test fun backDismissesWithoutAWebsiteOrStoreLaunch() = withSettings(Packages()) { _, ui, intents ->
        ui.click(text(R.string.app_store_title))
        ui.await("Missing-app sheet did not open") { ui.has(text(R.string.app_store_missing_title)) }
        ui.back()
        ui.await("Back did not dismiss the sheet") { ui.has(text(R.string.app_store_title)) && !ui.has(text(R.string.app_store_missing_title)) }
        assertTrue(intents.isEmpty())
    }

    @Test fun resumeReevaluatesAvailabilityInBothDirections() {
        val packages = Packages()
        withSettings(packages) { activity, ui, intents ->
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            fun leaveAndReturn(installed: Set<String>) {
                instrumentation.runOnMainSync {
                    activity.startActivity(Intent(android.provider.Settings.ACTION_SETTINGS))
                }
                ui.await("Did not leave Settings") { ui.packageVisible("com.android.settings") }
                packages.installed = installed
                ui.back()
                ui.await("Did not return to Kotatsu Settings") { ui.packageVisible(activity.packageName) && ui.has(text(R.string.app_store_title)) }
                instrumentation.runOnMainSync {
                    activity.window.insetsController?.hide(WindowInsets.Type.navigationBars())
                }
            }
            assertTrue(ui.has(text(R.string.app_store_absent)))
            leaveAndReturn(setOf("org.fdroid.basic"))
            ui.await("Resume did not observe installed F-Droid") { ui.has(text(R.string.app_store_open)) }
            ui.click(text(R.string.app_store_title))
            ui.await("Newly installed store did not launch") { intents.size == 1 }
            assertEquals(listOf("org.fdroid.basic"), packages.launchRequests)
            leaveAndReturn(emptySet())
            ui.await("Resume did not observe removed F-Droid") { ui.has(text(R.string.app_store_absent)) }
            ui.click(text(R.string.app_store_title))
            ui.await("Removed store did not open the missing sheet") { ui.has(text(R.string.app_store_missing_title)) }
            assertEquals(1, intents.size)
        }
    }
}
