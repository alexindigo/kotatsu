package com.gezimos.katapult

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.activity.compose.setContent
import androidx.test.platform.app.InstrumentationRegistry
import com.gezimos.katapult.recent.*
import com.gezimos.katapult.ui.RecentAppsScreen
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class RecentAppsInstrumentedTest {
    @get:Rule val usageAccess = UsageAccessRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val ui = PlatformUiDriver(instrumentation)
    private fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)
    private val now = 1_800_000_000_000L

    private inner class Source : RecentAppsSource {
        var access = true
        var accessDestination = true
        var accessLaunchSucceeds = true
        var historyOverride: List<RecentApp>? = null
        val closed = mutableMapOf<String, Long>()
        val kills = mutableListOf<List<String>>()
        val launches = mutableListOf<String>()
        override fun available() = access
        override fun history() = historyOverride ?: listOf(
            RecentApp("weather", "Kotatsu Weather", now - 86_400_000),
            RecentApp("chat", "Delta Chat", now - 3_600_000),
            RecentApp("camera", "Open Camera", now - 1_200_000),
            RecentApp("messages", "Messages", now - 300_000),
            RecentApp("notes", "Mudita Notes", now))
        override fun closed() = closed.toMap()
        override fun close(packages: List<String>, at: Long) { kills += packages; packages.forEach { closed[it] = at } }
        override fun launch(packageName: String) { launches += packageName }
        override fun canOpenAccess() = accessDestination
        override fun openAccess(): Boolean { launches += "access"; return accessLaunchSucceeds }
    }

    private fun withPage(test: (RecentApps, Source, MainActivity) -> Unit) {
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        val source = Source()
        val model = RecentApps(source, context.packageName) { now }
        try {
            main { model.show(); activity.setContent { RecentAppsScreen(model) } }
            ui.await("Recent list not rendered") { ui.has("Mudita Notes") }
            test(model, source, activity)
        } finally { main { activity.finish() } }
    }

    @Test fun homeIntentsCannotOpenRecentAppsWithoutTheService() {
        assertFalse(HomeKeyFilterState.active)
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).setAction(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        try {
            main { activity.viewModel.screen = Screen.HOME }
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                .setClass(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            main { context.startActivity(intent); context.startActivity(intent) }
            instrumentation.waitForIdleSync()
            ui.await("Home not shown") { ui.packageVisible(context.packageName) }
            assertFalse(PlatformUiDriver(instrumentation, context.getString(R.string.recent_apps)).windowVisible())
            assertEquals(Screen.HOME, activity.viewModel.screen)
        } finally { main { activity.finish() } }
    }

    @Test fun overlayScreenOffAndInterruptRemoveItWithoutLaunchingHome() = RecentOverlayFixture().use { fixture ->
        fixture.settings()
        fixture.open()
        fixture.shell("input keyevent 223")
        ui.await("Screen off left the overlay attached") { !fixture.overlay.windowVisible() }
        NativeKeyguardFixture.wakeAndUnlock()
        fixture.settings(); fixture.open()
        main { fixture.service.onInterrupt() }
        ui.await("Interrupt left the overlay attached") { !fixture.overlay.windowVisible() }
        assertTrue(ui.has("Brightness level"))
    }

    @Test fun overlayOverHomeKeepsTheTaskAndSingleHomeClosesIt() = RecentOverlayFixture().use { fixture ->
        ui.await("Home did not gain focus") { ui.packageVisible(context.packageName) }
        val resumed = fixture.resumedTask()
        fixture.open()
        assertEquals(resumed, fixture.resumedTask())
        fixture.back()
        ui.await("Back did not remove overlay over Home") { !fixture.overlay.windowVisible() && ui.packageVisible(context.packageName) }
        fixture.open()
        fixture.homeKey()
        val defaultHome = context.packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
            android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)!!.activityInfo.packageName
        ui.await("Single Home must close the overlay and reach Home") { !fixture.overlay.windowVisible() && ui.packageVisible(defaultHome) }
    }

    @Test fun overlayRowLaunchAndUsageHandoffRemoveTheWindow() = RecentOverlayFixture().use { fixture ->
        val target = "org.chromium.webview_shell"
        val closed = context.getSharedPreferences("recent_apps", 0)
        val previous = closed.all[target]
        closed.edit().remove(target).commit()
        try {
            val intent = checkNotNull(context.packageManager.getLaunchIntentForPackage(target))
            main { context.startActivity(intent) }
            ui.await("Row fixture did not launch") { ui.packageVisible(target) }
            fixture.settings()
            val resumed = fixture.resumedTask()
            fixture.open()
            val label = checkNotNull(context.packageManager.resolveActivity(intent, 0)).loadLabel(context.packageManager).toString()
            fixture.overlay.await("Launchable app not in overlay") { fixture.overlay.has(label) }
            assertEquals(resumed, fixture.resumedTask())
            val windowId = fixture.overlay.find(label)!!.windowId
            val window = instrumentation.getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
                .windows.single { it.id == windowId }
            val frame = Rect().also(window::getBoundsInScreen)
            val metrics = fixture.home.getSystemService(android.view.WindowManager::class.java).maximumWindowMetrics
            val bars = metrics.windowInsets.getInsetsIgnoringVisibility(android.view.WindowInsets.Type.systemBars())
            assertEquals("Overlay must leave the status bar uncovered", metrics.bounds.top + bars.top, frame.top)
            assertEquals("Overlay must leave the navigation bar uncovered", metrics.bounds.bottom - bars.bottom, frame.bottom)
            val directory = File(context.getExternalFilesDir(null), "recent-app-renders").apply { mkdirs() }
            fun captureOverlay(name: String) {
                instrumentation.waitForIdleSync()
                var previous: Bitmap? = null
                var presented: Bitmap? = null
                fixture.overlay.await("Overlay frame not presented: $name") {
                    val candidate = instrumentation.getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).takeScreenshot()
                    val ready = previous?.sameAs(candidate) == true
                    previous?.recycle()
                    if (ready) { presented = candidate; true }
                    else { previous = candidate; false }
                }
                val bitmap = checkNotNull(presented)
                File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
            captureOverlay("recent-overlay-over-settings")
            assertTrue(fixture.overlay.has("Recent apps"))
            assertFalse(fixture.overlay.has("Close all"))
            fixture.overlay.click(context.getString(R.string.recent_dismiss))
            ui.await("Header close did not reveal the same app") { !fixture.overlay.windowVisible() && ui.has("Brightness level") }
            assertEquals(resumed, fixture.resumedTask())
            fixture.open()
            fixture.overlay.click(label)
            ui.await("Row did not launch and remove overlay") { ui.packageVisible(target) && !fixture.overlay.windowVisible() }
            fixture.settings()
            fixture.shell("appops set ${context.packageName} GET_USAGE_STATS ignore")
            fixture.open()
            fixture.overlay.await("Usage access prompt absent") { fixture.overlay.has("Usage access is off") }
            fixture.overlay.click("Open")
            ui.await("Usage handoff left the overlay attached") { !fixture.overlay.windowVisible() }
            ui.await("Usage access destination missing") {
                fixture.shell("dumpsys activity activities").lineSequence().any { it.contains("mResumedActivity:") && it.contains("AppUsageAccessSettingsActivity") }
            }
        } finally {
            fixture.shell("appops set ${context.packageName} GET_USAGE_STATS allow")
            closed.edit().apply { if (previous == null) remove(target) else putLong(target, previous as Long) }.commit()
        }
    }

    @Test fun bottomCloseFromAppReachesHomeHidesUntilReuseAndBackgroundCloseStaysOpen() = RecentOverlayFixture().use { fixture ->
        val target = "org.chromium.webview_shell"
        val adapter = AndroidRecentApps(context)
        // Real Android history and stop/persistence operations, scoped to this test's apps.
        // Other suites can leave unrelated usage records in the future after clock tests.
        val source = object : RecentAppsSource by adapter {
            override fun history() = adapter.history().filter { it.packageName == target || it.packageName == "com.android.settings" }
        }
        main {
            val page = fixture.service.javaClass.getDeclaredField("recentPage").apply { isAccessible = true }.get(fixture.service)!!
            val model = page.javaClass.getDeclaredField("model").apply { isAccessible = true }.get(page) as RecentApps
            RecentApps::class.java.getDeclaredField("source").apply { isAccessible = true }.set(model, source)
        }
        val store = context.getSharedPreferences("recent_apps", 0)
        val saved = store.all
        store.edit().clear().commit()
        val launch = checkNotNull(context.packageManager.getLaunchIntentForPackage(target))
        val label = context.packageManager.resolveActivity(launch, 0)!!.loadLabel(context.packageManager).toString()
        val home = context.packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
            android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)!!.activityInfo.packageName
        fun closeRow(text: String) {
            val row = Rect().also { fixture.overlay.find(text)!!.getBoundsInScreen(it) }
            val close = fixture.overlay.find { node ->
                val bounds = Rect().also(node::getBoundsInScreen)
                node.text?.toString() == "Close" && bounds.centerY() in row.top..row.bottom
            }!!
            assertTrue(close.parent.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
        }
        try {
            main { context.startActivity(launch) }
            ui.await("Close target not foreground") { ui.packageVisible(target) }
            ui.await("Foreground target not newest in history") {
                recentApps(source.history(), context.packageName, source.closed()).lastOrNull()?.packageName == target
            }
            fixture.open()
            fixture.overlay.await("Close target absent") { fixture.overlay.has(label) }
            val tapped = System.currentTimeMillis()
            val marker = "CLOSE BEGIN $tapped"
            android.util.Log.i("RecentCloseProbe", marker)
            closeRow(label)
            ui.await("Bottom Close did not end on Home") { !fixture.overlay.windowVisible() && ui.packageVisible(home) }
            ui.await("Bottom Close not recorded") { (source.closed()[target] ?: 0) >= tapped }
            val trace = fixture.shell("logcat -d -v brief").substringAfter(marker)
            assertTrue("Close must observe Home, not just reach the timeout: $trace", trace.contains("Home observed before background stop"))
            assertFalse("Close used fallback instead of window confirmation", trace.contains("Home observation timed out"))
            main {
                assertArrayEquals(arrayOf("com.android.systemui"), fixture.service.serviceInfo.packageNames)
                assertEquals(100L, fixture.service.serviceInfo.notificationTimeout)
            }
            fixture.open()
            fixture.overlay.await("Closed target still listed") { fixture.overlay.has("Recent apps") && !fixture.overlay.has(label) }
            fixture.back()
            main { context.startActivity(launch) }
            ui.await("Closed target did not reopen") { ui.packageVisible(target) }
            ui.await("Reused target did not reappear") {
                recentApps(source.history(), context.packageName, source.closed()).any { it.packageName == target }
            }
            fixture.settings()
            ui.await("Background-close precondition: reused target must not be newest") {
                val history = recentApps(source.history(), context.packageName, source.closed())
                history.any { it.packageName == target } && history.lastOrNull()?.packageName != target
            }
            fixture.open()
            fixture.overlay.await("Reused background row missing") { fixture.overlay.has(label) }
            fixture.overlay.await("Background-close target must have a rendered row below it") {
                val targetRow = Rect()
                fixture.overlay.find(label)?.getBoundsInScreen(targetRow)
                !targetRow.isEmpty && fixture.overlay.find { node ->
                    val bounds = Rect().also(node::getBoundsInScreen)
                    node.text?.toString() == "Close" && bounds.top >= targetRow.bottom
                } != null
            }
            closeRow(label)
            fixture.overlay.await("Background Close must keep page open and remove row") {
                fixture.overlay.windowVisible() && fixture.overlay.has("Recent apps") && !fixture.overlay.has(label)
            }
            fixture.back()
            ui.await("Background Close changed underlying activity") { ui.has("Brightness level") }
        } finally {
            store.edit().clear().apply { saved.forEach { (key, value) -> putLong(key, value as Long) } }.commit()
        }
    }

    @Test fun androidAdapterKillsBackgroundProcessAndPersistsCloseUntilReuse() {
        val packageName = "org.chromium.webview_shell"
        val launch = checkNotNull(context.packageManager.getLaunchIntentForPackage(packageName))
        val prefs = context.getSharedPreferences("katapult_prefs", 0)
        val choice = prefs.all["collect_usage_stats"]
        val closedStore = context.getSharedPreferences("recent_apps", 0)
        val previousClose = closedStore.all[packageName]
        prefs.edit().putBoolean("collect_usage_stats", true).commit()
        val source = AndroidRecentApps(context)
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        try {
            main { activity.viewModel.screen = Screen.HOME; context.startActivity(launch) }
            ui.await("Real close fixture not foreground") { ui.packageVisible(packageName) }
            ui.await("Real close fixture has no process") { shell("pidof $packageName").isNotBlank() }
            val uid = context.packageManager.getApplicationInfo(packageName, 0).uid
            main { context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            ui.await("Home did not regain foreground") { ui.packageVisible(context.packageName) }
            ui.await("Fixture has not become background") {
                shell("am get-uid-state $uid").substringBefore(' ').toIntOrNull()?.let { it >= 15 } == true
            }
            val before = source.history().single { it.packageName == packageName }
            val at = System.currentTimeMillis()
            source.close(listOf(packageName), at)
            ui.await("killBackgroundProcesses did not stop background fixture") { shell("pidof $packageName").isBlank() }
            val recreated = AndroidRecentApps(context)
            assertEquals(at, recreated.closed()[packageName])
            assertFalse(recentApps(listOf(before), context.packageName, recreated.closed()).any { it.packageName == packageName })
            SystemClock.sleep(50)
            main { recreated.launch(packageName) }
            ui.await("Fixture did not relaunch") { ui.packageVisible(packageName) }
            ui.await("Reused app must reappear") {
                recentApps(recreated.history(), context.packageName, recreated.closed()).any { it.packageName == packageName }
            }
        } finally {
            main { activity.finish() }
            prefs.edit().apply { if (choice == null) remove("collect_usage_stats") else putBoolean("collect_usage_stats", choice as Boolean) }.commit()
            closedStore.edit().apply { if (previousClose == null) remove(packageName) else putLong(packageName, previousClose as Long) }.commit()
        }
    }

    private fun shell(command: String): String = android.os.ParcelFileDescriptor.AutoCloseInputStream(
        instrumentation.platformAutomation.executeShellCommand(command)).bufferedReader().use { it.readText().trim() }

    @Test fun usageOpenTargetsPerAppFromApplicationContextAndGrantRefreshesOnReturn() {
        val prefs = context.getSharedPreferences("katapult_prefs", 0)
        val saved = listOf("onboarding_complete", "collect_usage_stats").associateWith { prefs.all[it] }
        val mode = context.getSystemService(android.app.AppOpsManager::class.java).unsafeCheckOpNoThrow(
            android.app.AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), context.packageName)
        val restore = when (mode) {
            android.app.AppOpsManager.MODE_ALLOWED -> "allow"
            android.app.AppOpsManager.MODE_IGNORED -> "ignore"
            android.app.AppOpsManager.MODE_ERRORED -> "deny"
            android.app.AppOpsManager.MODE_FOREGROUND -> "foreground"
            else -> "default"
        }
        val launches = java.util.concurrent.CopyOnWriteArrayList<Intent>()
        val monitor = object : android.app.Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): android.app.Instrumentation.ActivityResult? {
                if (intent.action == android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS) launches += Intent(intent)
                return null
            }
        }
        instrumentation.addMonitor(monitor)
        var activity: MainActivity? = null
        try {
            prefs.edit().putBoolean("onboarding_complete", true).putBoolean("collect_usage_stats", true).commit()
            shell("appops set ${context.packageName} GET_USAGE_STATS ignore")
            val home = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            activity = home
            val recent = RecentApps(AndroidRecentApps(context), context.packageName)
            main { recent.show(); home.setContent { RecentAppsScreen(recent) } }
            ui.await("Missing access row absent") { ui.has("Usage access is off") }
            ui.click("Open")
            ui.await("Usage-access activity not launched") { launches.isNotEmpty() && ui.packageVisible("com.android.settings") }
            val intent = launches.single()
            assertEquals("package:${context.packageName}", intent.dataString)
            assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
            assertTrue(intent.resolveActivity(context.packageManager)!!.className.endsWith("AppUsageAccessSettingsActivity"))
            assertFalse(recent.available)
            assertFalse("Usage handoff must dismiss", recent.visible)
            shell("appops set ${context.packageName} GET_USAGE_STATS allow")
            // Settings may reuse its existing task with unrelated pages beneath it.
            // The handoff closes the page; reopening must re-read the granted access.
            ui.back()
            instrumentation.waitForIdleSync()
            main { context.getSystemService(android.app.ActivityManager::class.java).appTasks
                .single { it.taskInfo.taskId == home.taskId }.moveToFront() }
            main { recent.show() }
            ui.await("Returning task did not foreground Home") { ui.packageVisible(context.packageName) }
            ui.await("Returning from Settings did not refresh the consumer list") {
                recent.available && recent.apps.isNotEmpty() && !ui.has("Usage access is off")
            }
            assertTrue(recent.visible)
            main { recent.back(); home.finish() }
            val permissions = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            activity = permissions
            main { permissions.viewModel.settingsPageRequest = "permissions"; permissions.viewModel.screen = Screen.SETTINGS }
            val usageLabel = context.getString(R.string.perm_usage_access)
            ui.await("Permissions usage entry missing") { ui.has(usageLabel) }
            ui.scrollTo(usageLabel)
            ui.click(usageLabel)
            ui.await("Permissions did not use the shared owner") { launches.size == 2 }
            assertEquals(intent.data, launches.last().data)
            assertEquals(intent.resolveActivity(context.packageManager), launches.last().resolveActivity(context.packageManager))
            ui.back()
            ui.await("Usage settings fixture did not return to Permissions") { ui.packageVisible(context.packageName) }
        } finally {
            instrumentation.removeMonitor(monitor)
            main { activity?.finish() }
            shell("appops set ${context.packageName} GET_USAGE_STATS $restore")
            prefs.edit().apply { saved.forEach { (key, value) -> if (value == null) remove(key) else putBoolean(key, value as Boolean) } }.commit()
        }
    }

    @Test fun usageSettingsFallsBackAndReportsFailureForAnyContext() {
        val intents = mutableListOf<Intent>()
        var rejectAll = false
        val wrapped = object : android.content.ContextWrapper(context.applicationContext) {
            override fun startActivity(intent: Intent) {
                intents += Intent(intent)
                if (rejectAll || intent.data != null) throw android.content.ActivityNotFoundException("Fixture entry unavailable")
            }
        }
        assertTrue(com.gezimos.katapult.util.UsageHelper.openSettings(wrapped))
        assertEquals(2, intents.size)
        assertEquals("package:${context.packageName}", intents.first().dataString)
        assertNull(intents.last().data)
        assertTrue(intents.all { it.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0 })
        rejectAll = true
        assertFalse(com.gezimos.katapult.util.UsageHelper.openSettings(wrapped))
    }

    @Test fun unavailableUsageDestinationLeavesOnlyPlainStatus() = withPage { model, source, _ ->
        main { source.access = false; source.accessLaunchSucceeds = false; model.refresh() }
        ui.await("Access row missing") { ui.has("Usage access is off") }
        ui.click("Open")
        ui.await("Failed action must become plain status") { !ui.has("Open") && ui.has("Usage access is off") }
        main { source.accessDestination = false; model.refresh() }
        assertFalse(model.canOpenAccess)
        assertNull(ui.findAncestor("Usage access is off") { it.isClickable })
    }

    @Test fun closeSwipeAndSwitchHaveIndependentTargets() = withPage { model, source, _ ->
        val row = Rect().also { ui.find("Open Camera")!!.getBoundsInScreen(it) }
        val close = ui.find { node -> val bounds = Rect().also(node::getBoundsInScreen)
            node.text?.toString() == "Close" && bounds.top in row.top..row.bottom }!!
        assertTrue(close.parent.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
        ui.await("Close must remove row") { !ui.has("Open Camera") }
        assertTrue(model.visible)
        assertEquals(listOf(listOf("camera")), source.kills)
        val messages = Rect().also { ui.find("Messages")!!.getBoundsInScreen(it) }
        val width = context.resources.displayMetrics.widthPixels
        val rowWidth = width - 2 * (16 * context.resources.displayMetrics.density).toInt()
        swipe(width * .85f, width * .85f - rowWidth / 3f + 1, messages.centerY().toFloat())
        assertTrue(ui.has("Messages"))
        swipe(width * .85f, width * .85f - rowWidth / 3f - 1, messages.centerY().toFloat())
        ui.await("Left swipe must remove row") { !ui.has("Messages") }
        assertEquals(listOf("messages"), source.kills.last())
        ui.click("Delta Chat")
        assertEquals(listOf("chat"), source.launches)
        assertFalse(model.visible)
    }

    @Test fun leaderAndHelpSpaceOpenWithSmallDriftButCloseKeepsItsOwnTarget() = withPage { model, source, _ ->
        val rowIdentity = checkNotNull(ui.find("Mudita Notes"))
        fun reopen() {
            main { model.show() }
            instrumentation.waitForIdleSync()
            ui.await("Reopened measured rows not yet presented") { ui.has("Mudita Notes") }
            assertEquals("Refreshing data must retain the row's native identity", rowIdentity, ui.find("Mudita Notes"))
        }
        fun row() = Rect().also { ui.find("Mudita Notes")!!.getBoundsInScreen(it) }
        val first = row()
        val open = checkNotNull(ui.findAncestor("Mudita Notes") { it.isClickable })
        assertEquals("Just now", open.stateDescription?.toString())
        val closeTarget = checkNotNull(ui.find { node -> val rect = Rect().also(node::getBoundsInScreen)
            node.isClickable && rect.top == first.top && rect.left >= first.right })
        val closeBounds = Rect().also(closeTarget::getBoundsInScreen)
        assertEquals("The two button targets must share an edge, not overlap", first.right, closeBounds.left)
        assertEquals(first.height(), closeBounds.height())
        pointerStroke(250f, first.top + 12f, 4f, 2f)
        ui.await("Leader tap with small drift did not open") { source.launches == listOf("notes") }
        assertTrue(source.kills.isEmpty())
        reopen()
        val next = row()
        pointerStroke(250f, next.bottom - 10f, -3f, 2f)
        ui.await("Space beside help did not open") { source.launches == listOf("notes", "notes") }
        reopen()
        val bounds = row()
        val close = ui.find { node -> val rect = Rect().also(node::getBoundsInScreen)
            node.text?.toString() == "Close" && rect.top in bounds.top..bounds.bottom }!!
        val action = Rect().also(close::getBoundsInScreen)
        pointerStroke(action.centerX().toFloat(), action.centerY().toFloat(), 0f, 0f)
        ui.await("Close tap did not close independently") { source.kills == listOf(listOf("notes")) }
        assertEquals(listOf("notes", "notes"), source.launches)
    }

    @Test fun refreshAndReopenAfterReorderingResetToFullyVisibleNewestRow() = withPage { model, source, activity ->
        val history = (0..39).map { RecentApp("history$it", "History $it", now - it * 60_000) }
        main { source.historyOverride = history; model.refresh() }
        ui.await("Long history did not show newest") { ui.has("History 0") }
        // Scroll toward older entries and leave the newest row off-screen.
        pointerStroke(240f, 250f, 0f, 380f)
        ui.await("Fixture did not scroll away from newest") { !ui.has("History 0") }
        main {
            source.historyOverride = history.map { if (it.packageName == "history39") it.copy(lastUsed = now + 1) else it }
            model.refresh()
        }
        fun fullyVisible(label: String): Boolean {
            val rect = Rect()
            val node = ui.findAncestor(label) { it.isClickable } ?: return false
            node.getBoundsInScreen(rect)
            val decor = activity.window.decorView
            val insets = decor.rootWindowInsets.getInsets(android.view.WindowInsets.Type.systemBars())
            val bottom = decor.height - insets.bottom - (9 * context.resources.displayMetrics.density).toInt()
            return rect.height() >= (48 * context.resources.displayMetrics.density).toInt() &&
                rect.bottom <= bottom + 1 && rect.bottom >= bottom - 1 && rect.top >= insets.top
        }
        ui.await("Refresh kept an old scroll anchor") { fullyVisible("History 39") }
        pointerStroke(240f, 250f, 0f, 380f)
        main {
            model.dismiss()
            source.historyOverride = history.map { if (it.packageName == "history20") it.copy(lastUsed = now + 2) else it }
            model.show()
        }
        ui.await("Reopen did not fully reveal newest at the bottom") { fullyVisible("History 20") }
    }

    private fun pointerStroke(x: Float, y: Float, dx: Float, dy: Float) {
        val down = SystemClock.uptimeMillis()
        for (step in 0..10) {
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), when (step) {
                0 -> MotionEvent.ACTION_DOWN; 10 -> MotionEvent.ACTION_UP; else -> MotionEvent.ACTION_MOVE
            }, x + dx * step / 10f, y + dy * step / 10f, 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            assertTrue(instrumentation.platformAutomation.injectInputEvent(event, true)); event.recycle(); SystemClock.sleep(15)
        }
        instrumentation.waitForIdleSync()
    }

    @Test fun titleCloseAndManyOrOneAppMatchTheMockWithoutCloseAll() = withPage { model, source, activity ->
        val positions = listOf("Kotatsu Weather", "Delta Chat", "Open Camera", "Messages", "Mudita Notes")
            .map { label -> Rect().also { ui.find(label)!!.getBoundsInScreen(it) } }
        assertTrue(positions.zipWithNext().all { (a, b) -> a.bottom <= b.top })
        assertTrue("Newest row must sit in the bottom quarter", positions.last().top > activity.window.decorView.height * .7)
        assertTrue(ui.has("Recent apps"))
        assertFalse(ui.has("Close all"))
        assertTrue(source.kills.isEmpty())
        val recent = source.history()
        main {
            source.historyOverride = (0..19).map { RecentApp("older$it", "Older app $it", now - (it + 2) * 86_400_000L) } + recent
            model.refresh()
        }
        val rail = context.getString(R.string.edge_task_scroll)
        ui.await("Overflow must have the shared rail") { ui.has(rail) && ui.has("Mudita Notes") }
        assertFalse(ui.find(context.getString(R.string.edge_task_scroll_down))!!.isEnabled)
        capture(activity, "recent-header-rail-many")
        ui.click(context.getString(R.string.edge_task_scroll_up))
        ui.await("Up arrow did not expose older rows") { !ui.has("Mudita Notes") }
        val track = ui.find(rail)!!
        assertTrue(track.performAction(android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id,
            android.os.Bundle().apply { putFloat(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, 0f) }))
        ui.await("Rail did not reach oldest end") { ui.find(rail)?.rangeInfo?.current == 0f }
        assertFalse(ui.find(context.getString(R.string.edge_task_scroll_up))!!.isEnabled)
        val header = Rect().also { ui.find("Recent apps")!!.getBoundsInScreen(it) }
        for (app in model.apps) {
            val node = ui.findAncestor(app.label) { it.isClickable } ?: continue
            val bounds = Rect().also(node::getBoundsInScreen)
            assertTrue("Partial top row: ${app.label}: $bounds", bounds.top >= header.bottom)
            assertTrue("Whole row target lost height: $bounds", bounds.height() >= (48 * context.resources.displayMetrics.density).toInt())
        }
        main { source.historyOverride = listOf(RecentApp("notes", "Notes", now)); model.refresh() }
        ui.await("One app must not have a rail") { ui.has("Notes") && !ui.has(rail) }
        assertFalse(ui.has("Close all"))
        capture(activity, "recent-header-rail-one")
        ui.click(context.getString(R.string.recent_dismiss))
        assertFalse(model.visible)
        assertTrue(source.kills.isEmpty())
        assertTrue(source.launches.isEmpty())
    }

    @Test fun missingAccessUsesSharedOpenRowAndClosesWithoutAReturnLaunch() = withPage { model, source, _ ->
        main { source.access = false; model.refresh() }
        ui.await("Usage access line missing") { ui.has("Usage access is off") }
        ui.click("Usage access is off")
        assertEquals(listOf("access"), source.launches)
        assertEquals(listOf("access"), source.launches)
        assertFalse(model.visible)
    }

    private fun swipe(from: Float, to: Float, y: Float) {
        val down = SystemClock.uptimeMillis()
        for (step in 0..15) {
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), when (step) {
                0 -> MotionEvent.ACTION_DOWN; 15 -> MotionEvent.ACTION_UP; else -> MotionEvent.ACTION_MOVE
            }, from + (to - from) * step / 15, y, 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            assertTrue(instrumentation.platformAutomation.injectInputEvent(event, true)); event.recycle(); SystemClock.sleep(15)
        }
        instrumentation.waitForIdleSync()
    }

    private fun capture(activity: MainActivity, name: String) {
        instrumentation.waitForIdleSync()
        val committed = CountDownLatch(1)
        main { activity.window.decorView.viewTreeObserver.registerFrameCommitCallback { committed.countDown() }; activity.window.decorView.invalidate() }
        assertTrue("Frame committed", committed.await(10, TimeUnit.SECONDS))
        val decor = activity.window.decorView
        val bitmap = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
        val copied = CountDownLatch(1)
        var status = -1
        android.view.PixelCopy.request(activity.window, bitmap, { status = it; copied.countDown() }, android.os.Handler(android.os.Looper.getMainLooper()))
        assertTrue(copied.await(10, TimeUnit.SECONDS)); assertEquals(android.view.PixelCopy.SUCCESS, status)
        val insets = decor.rootWindowInsets.getInsets(android.view.WindowInsets.Type.systemBars())
        val height = bitmap.height - insets.top - insets.bottom
        val expected = IntArray(bitmap.width * height)
        bitmap.getPixels(expected, 0, bitmap.width, 0, insets.top, bitmap.width, height)
        var frame: Bitmap? = null
        ui.await("Displayed frame must equal committed window") {
            val candidate = instrumentation.platformAutomation.takeScreenshot()
            val pixels = IntArray(expected.size)
            candidate.getPixels(pixels, 0, bitmap.width, 0, insets.top, bitmap.width, height)
            if (expected.contentEquals(pixels)) { frame = candidate; true } else { candidate.recycle(); false }
        }
        val directory = File(context.getExternalFilesDir(null), "recent-app-renders").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { checkNotNull(frame).compress(Bitmap.CompressFormat.PNG, 100, it) }
        frame?.recycle(); bitmap.recycle()
    }
}
