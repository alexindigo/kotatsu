package com.gezimos.katapult

import android.Manifest
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gezimos.katapult.lockscreen.ScreensaverActivity
import com.gezimos.katapult.service.BadgeSources
import com.gezimos.katapult.service.DirectBadgeHelper
import com.gezimos.katapult.util.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class SensitiveLifecycleInstrumentedTest {
    @Test fun syntheticDirectConsumerCanReentrantlyReadAndRevokePublishedWorkerResult() =
        DirectBadgeAdapterCases.reentrantConsumersCanReadAndRevokeWithoutNotificationUnderWorkerLocks()
    @Test fun policyRefreshAndUnavailableUsagePreserveReorderDraftAndManualFallback() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val activity = instrumentation.startActivitySync(Intent(Intent.ACTION_MAIN).apply {
            setClassName(context.packageName, MainActivity::class.java.name)
            addCategory(Intent.CATEGORY_LAUNCHER)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }) as MainActivity
        val model = activity.viewModel
        val raw = context.getSharedPreferences("katapult_prefs", 0)
        val stored = listOf("collect_usage_stats", "app_sort_mode", "app_order").associateWith { raw.all[it] }
        assertFalse("Preserve a pre-existing human reorder draft", model.reorderMode)
        val ui = PlatformUiDriver(instrumentation)
        try {
            if (ui.has("Finish")) ui.click("Finish")
            instrumentation.runOnMainSync { model.navigateTo(Screen.ALL_APPS) }
            ui.await("Grid unavailable before policy refresh test") { model.orderedApps.size >= 2 && ui.has(model.orderedApps[0].label) }
            val baseline = model.orderedApps.map { it.key }
            val saved = model.prefs.loadAppOrder()?.toList()
            instrumentation.runOnMainSync {
                model.enterReorderMode(model.orderedApps[0])
                model.reorderTap(1)
            }
            val draft = model.orderedApps.map { it.key }
            assertNotEquals(baseline, draft)
            val tick = model.policyRefresh
            instrumentation.runOnMainSync { model.prefs.setSensitiveChoice(SensitiveChoice.USAGE_STATS, false) }
            ui.await("Policy change did not reach actual ViewModel consumer") { model.policyRefresh > tick }
            assertEquals(draft, model.orderedApps.map { it.key })
            assertEquals(saved, model.prefs.loadAppOrder())
            instrumentation.runOnMainSync {
                model.prefs.appSortMode = PrefsManager.APP_SORT_USED
                model.loadApps()
            }
            assertEquals(draft, model.orderedApps.map { it.key })
            assertTrue(model.reorderMode)
            instrumentation.runOnMainSync { model.discardReorderExit() }
            ui.await("Unavailable usage did not render stable manual fallback") { !model.reorderMode && model.orderedApps.map { it.key } == baseline }
            assertEquals(PrefsManager.APP_SORT_USED, model.prefs.appSortMode)
            assertEquals(saved, model.prefs.loadAppOrder())
            assertNull(UsageHelper.stats(context))
            instrumentation.runOnMainSync { model.prefs.setSensitiveChoice(SensitiveChoice.USAGE_STATS, true) }
            ui.await("Denied Usage Access changed manual fallback") { model.orderedApps.map { it.key } == baseline }
            assertEquals(PrefsManager.APP_SORT_USED, model.prefs.appSortMode)
        } finally {
            instrumentation.runOnMainSync {
                if (model.reorderMode) model.discardReorderExit()
                val editor = raw.edit()
                stored.forEach { (key, value) -> when (value) {
                    is Boolean -> editor.putBoolean(key, value)
                    is Int -> editor.putInt(key, value)
                    is String -> editor.putString(key, value)
                    else -> editor.remove(key)
                } }
                editor.commit(); model.loadApps()
            }
        }
    }
    @Test fun syntheticStaticAndTimedScreensaverRowsClearAtRenderedConsumerAndReleaseWhenInactive() =
        ScreensaverAdapterCases.staticAndTimedConsumersClearImmediatelyAndReleaseWhenInactive()
    @Test fun syntheticOwnMediaSessionTestsActualHelperDisplayCleanupAndTransportWithoutOsGrant() =
        MediaAdapterCases.realOwnSessionThroughInjectedEnumerationAndAccess()
    @Test fun syntheticDirectWorkersKeepSourcesIndependentAndQueriesOffUiThread() =
        DirectBadgeAdapterCases.independentSourcesAndOffThreadReads()
    @Test fun syntheticDirectWorkersCancelDebounceAndRejectLateObserverAndQuery() =
        DirectBadgeAdapterCases.cancelDebounceAndRejectLateAndDetachedCallbacks()
    @Test fun syntheticDirectUnavailableResultsAndDisabledActionsReachNoProviderWrite() =
        DirectBadgeAdapterCases.unavailableIsNotZeroAndDisabledQueuedActionsNeverWrite()
    @Test fun preferenceAdapterOwnsAdditiveListenersThroughGcAndDuplicateClose() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val name = "kotatsu_subscription_synthetic_${UUID.randomUUID()}"
        val raw = context.getSharedPreferences(name, 0)
        val fixture = object : ContextWrapper(context) {
            override fun getSharedPreferences(ignored: String?, mode: Int): SharedPreferences = raw
        }
        val prefs = PrefsManager(fixture)
        var first = 0
        var second = 0
        val one = prefs.subscribeChanges { first++ }
        val two = prefs.subscribeChanges { second++ }
        try {
            System.gc()
            raw.edit().putBoolean("synthetic", true).commit()
            instrumentation.waitForIdleSync()
            assertEquals(1, first); assertEquals(1, second)
            one.close(); one.close()
            raw.edit().putBoolean("synthetic", false).commit()
            instrumentation.waitForIdleSync()
            assertEquals(1, first); assertEquals(2, second)
            two.close(); two.close()
            raw.edit().putBoolean("synthetic", true).commit()
            instrumentation.waitForIdleSync()
            assertEquals(1, first); assertEquals(2, second)
        } finally {
            one.close(); two.close()
            deleteTestPreferences(context, name)
        }
    }
    @Test fun publicChoicesRemainIndependentAndPersistWithoutGrantOrActivationChanges() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val activity = instrumentation.startActivitySync(Intent(Intent.ACTION_MAIN).apply {
            setClassName(context.packageName, MainActivity::class.java.name)
            addCategory(Intent.CATEGORY_LAUNCHER)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }) as MainActivity
        val model = activity.viewModel
        val prefs = model.prefs
        val raw = context.getSharedPreferences("katapult_prefs", 0)
        val stored = SensitiveChoice.entries.associate { it.key to raw.all[it.key] }
        val activation = listOf(prefs.appSortMode, prefs.doubleTapAction, prefs.lockscreenWidget,
            prefs.lockscreenMusicWidget, prefs.screensaverEnabled)
        val ui = PlatformUiDriver(instrumentation)
        try {
            if (ui.has("Finish")) ui.click("Finish")
            ui.await("HOME not observed") { ui.has("Apps") }
            ui.homeMenu(); ui.click("Settings"); ui.click("System")
            ui.await("Sensitive access entry missing") { ui.has("Sensitive access") }
            ui.click("Sensitive access")
            ui.await("Choice/status separation missing") { ui.has("Collect SMS badges") && ui.has("Direct source unsupported on this device") }
            assertTrue("Default missing SMS choice did not render enabled", ui.checked("Collect SMS badges") == prefs.sensitiveChoice(SensitiveChoice.SMS_BADGES))
            val before = prefs.sensitiveChoice(SensitiveChoice.SMS_BADGES)
            ui.click("Collect SMS badges")
            ui.await("Public choice did not reach stored preference and visible switch") {
                prefs.sensitiveChoice(SensitiveChoice.SMS_BADGES) == !before && ui.checked("Collect SMS badges") == !before
            }
            assertEquals(!before, PrefsManager(context).sensitiveChoice(SensitiveChoice.SMS_BADGES))
            assertTrue(ui.packageVisible(context.packageName))
            assertFalse(DeviceHelper.isDefaultLauncher(context))
            assertEquals(activation, listOf(prefs.appSortMode, prefs.doubleTapAction, prefs.lockscreenWidget,
                prefs.lockscreenMusicWidget, prefs.screensaverEnabled))
            for (permission in listOf(Manifest.permission.READ_SMS, Manifest.permission.READ_CALL_LOG, Manifest.permission.WRITE_CALL_LOG)) {
                assertEquals(PackageManager.PERMISSION_DENIED, context.checkSelfPermission(permission))
            }
            val labels = listOf("Collect call badges", "Collect notification badges", "Collect media information",
                "Collect usage statistics", "Allow notification dismissal", "Allow call acknowledgment", "Allow media controls", "Allow accessibility features")
            for (label in labels) { ui.scrollTo(label); assertNotNull("Stored choice not exposed as a switch: $label", ui.checked(label)) }
        } finally {
            instrumentation.runOnMainSync {
                val editor = raw.edit()
                stored.forEach { (key, value) -> if (value is Boolean) editor.putBoolean(key, value) else editor.remove(key) }
                editor.commit()
            }
            ui.back(); ui.back(); ui.back()
        }
    }
    @Test fun strictAtomicImportAndNullableRetainedSubscription() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val name = "kotatsu_policy_synthetic_${UUID.randomUUID()}"
        val raw = context.getSharedPreferences(name, 0)
        val fixture = object : ContextWrapper(context) {
            override fun getSharedPreferences(ignored: String?, mode: Int): SharedPreferences = raw
        }
        val prefs = PrefsManager(fixture)
        val keys = mutableListOf<String?>()
        val subscription = prefs.subscribeChanges { keys.add(it) }
        try {
            val before = raw.all.toMap()
            for (choice in SensitiveChoice.entries) for (invalid in listOf<Any>("false", 0, 1, 0.5, JSONObject.NULL, org.json.JSONArray(), JSONObject())) {
                val json = JSONObject().put("_type", "katapult_config").put(choice.key, invalid)
                assertFalse(prefs.importFromJson(json.toString()))
                assertEquals(before, raw.all)
            }
            assertTrue(keys.isEmpty())
            val allFalse = JSONObject().put("_type", "katapult_config")
            SensitiveChoice.entries.forEach { allFalse.put(it.key, false) }
            assertTrue(prefs.importFromJson(allFalse.toString()))
            // Drain the preceding unconfirmed import's Android main-thread
            // notifications before observing the next editor transaction.
            instrumentation.waitForIdleSync()
            assertTrue(prefs.sensitiveChoices().values.all { it }) // Unconfirmed imports preserve local choices.
            val preview = prefs.previewPolicyImport(allFalse.toString())!!
            val atomicReadback = mutableListOf<Map<String, Boolean>>()
            val atomic = prefs.subscribeChanges { atomicReadback.add(prefs.sensitiveChoices()) }
            assertTrue(prefs.importFromJson(allFalse.toString(), preview.localChoices))
            instrumentation.waitForIdleSync()
            atomic.close()
            assertTrue(prefs.sensitiveChoices().values.none { it })
            assertTrue(atomicReadback.isNotEmpty())
            assertTrue("Confirmed editor callbacks observed a partial policy snapshot: $atomicReadback",
                atomicReadback.all { snapshot -> snapshot.values.none { it } })
            val persisted = mutableMapOf<String, Boolean>()
            java.io.File(context.applicationInfo.dataDir, "shared_prefs/$name.xml").inputStream().use { stream ->
                val parser = android.util.Xml.newPullParser()
                parser.setInput(stream, "UTF-8")
                while (parser.eventType != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                    if (parser.eventType == org.xmlpull.v1.XmlPullParser.START_TAG && parser.name == "boolean") {
                        persisted[parser.getAttributeValue(null, "name")] = parser.getAttributeValue(null, "value").toBooleanStrict()
                    }
                    parser.next()
                }
            }
            assertTrue(SensitiveChoice.entries.all { persisted[it.key] == false })
            assertTrue(prefs.importFromJson("{\"_type\":\"katapult_config\"}"))
            assertTrue(prefs.sensitiveChoices().values.none { it })
            val incomingTrue = JSONObject().put("_type", "katapult_config")
            SensitiveChoice.entries.forEach { incomingTrue.put(it.key, true) }
            val stale = prefs.previewPolicyImport(incomingTrue.toString())!!
            prefs.setSensitiveChoice(SensitiveChoice.SMS_BADGES, true)
            val changed = raw.all.toMap()
            assertFalse(prefs.importFromJson(incomingTrue.toString(), stale.localChoices))
            assertEquals(changed, raw.all)
            keys.clear()
            raw.edit().clear().commit()
            instrumentation.waitForIdleSync()
            assertTrue("API31 clear event did not deliver nullable key", keys.any { it == null })
            assertTrue(prefs.sensitiveChoices().values.all { it })
        } finally {
            subscription.close(); subscription.close()
            deleteTestPreferences(context, name) // Only this uniquely named synthetic fixture.
        }
    }

    @Test fun deniedAdaptersAndDisabledEntryPreserveConsumerAndPreferences() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assertEquals("com.alexindigo.kotatsu.debug", context.packageName)
        for (permission in listOf(Manifest.permission.READ_SMS, Manifest.permission.READ_CALL_LOG, Manifest.permission.WRITE_CALL_LOG)) {
            assertEquals(PackageManager.PERMISSION_DENIED, context.checkSelfPermission(permission))
        }
        assertFalse(DeviceHelper.isMuditaKompakt())
        val prefs = PrefsManager(context)
        val raw = context.getSharedPreferences("katapult_prefs", 0)
        val stored = SensitiveChoice.entries.associate { it.key to raw.all[it.key] }
        val initial = prefs.sensitiveChoices()
        val screensaver = prefs.screensaverEnabled
        val saverPresent = raw.contains("screensaver_enabled")
        val sort = prefs.appSortMode
        try {
            instrumentation.runOnMainSync {
                SensitiveAccess.initialize(context)
                prefs.setSensitiveChoice(SensitiveChoice.NOTIFICATION_BADGES, false)
                prefs.setSensitiveChoice(SensitiveChoice.MEDIA_INFO, true)
                prefs.setSensitiveChoice(SensitiveChoice.MEDIA_CONTROLS, false)
                prefs.setSensitiveChoice(SensitiveChoice.USAGE_STATS, false)
                prefs.screensaverEnabled = false
            }
            assertTrue(BadgeSources.counts(context).isEmpty())
            assertTrue(DirectBadgeHelper.getInstance(context).getCounts().isEmpty())
            assertNull(UsageHelper.stats(context))
            val audio = AudioWidgetHelper.getInstance(context)
            instrumentation.runOnMainSync {
                audio.reconcile()
                assertNull(audio.state.value)
                assertFalse(audio.playPause())
                assertFalse(audio.skipNext())
                assertFalse(audio.skipPrevious())
                assertFalse(audio.stop())
            }
            context.startActivity(Intent(context, ScreensaverActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            instrumentation.waitForIdleSync()
            assertFalse(ScreensaverActivity.isShowing)
            assertEquals(sort, prefs.appSortMode)
            assertFalse(SensitiveAccess.notificationBadges())
            assertFalse(SensitiveAccess.mediaControls())
            assertEquals(initial[SensitiveChoice.SMS_BADGES.key], prefs.sensitiveChoice(SensitiveChoice.SMS_BADGES))
        } finally {
            instrumentation.runOnMainSync {
                val editor = raw.edit()
                stored.forEach { (key, value) -> if (value is Boolean) editor.putBoolean(key, value) else editor.remove(key) }
                if (saverPresent) editor.putBoolean("screensaver_enabled", screensaver) else editor.remove("screensaver_enabled")
                editor.commit()
            }
        }
    }

    @Test fun exportedPowerScreensaverCannotBypassDisabledAccessibilityOrPowerActivation() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val raw = context.getSharedPreferences("katapult_prefs", 0)
        val keys = listOf("screensaver_enabled", "screensaver_on_power", "allow_accessibility_features")
        val stored = keys.associateWith { raw.all[it] }
        try {
            instrumentation.runOnMainSync {
                raw.edit().putBoolean("screensaver_enabled", true).putBoolean("screensaver_on_power", true)
                    .putBoolean("allow_accessibility_features", false).commit()
            }
            context.startActivity(Intent(Intent.ACTION_VIEW).setClass(context, ScreensaverActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra(ScreensaverActivity.EXTRA_FROM_POWER, true))
            instrumentation.waitForIdleSync()
            assertFalse("Exported power entry showed UI while accessibility choice was off", ScreensaverActivity.isShowing)
            assertFalse(SensitiveAccess.accessibility())
            instrumentation.runOnMainSync {
                raw.edit().putBoolean("screensaver_on_power", false).putBoolean("allow_accessibility_features", true).commit()
            }
            context.startActivity(Intent(Intent.ACTION_VIEW).setClass(context, ScreensaverActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra(ScreensaverActivity.EXTRA_FROM_POWER, true))
            instrumentation.waitForIdleSync()
            assertFalse("Exported power entry bypassed selected power activation", ScreensaverActivity.isShowing)
        } finally {
            instrumentation.runOnMainSync {
                val editor = raw.edit()
                stored.forEach { (key, value) -> if (value is Boolean) editor.putBoolean(key, value) else editor.remove(key) }
                editor.commit()
            }
        }
    }

    @Test fun syntheticAndroidCallbackCannotRepopulateAfterOptOutAndReenable() {
        val source = SourceGeneration<Int>()
        source.reconcile(true)
        val old = source.epoch
        val done = CountDownLatch(1)
        var queries = 0
        var visible: Int? = null
        Handler(Looper.getMainLooper()).post {
            source.reconcile(false)
            source.reconcile(true)
            val published = SourceRead(source).run(old, { true }) { queries++; 99 }
            if (published) visible = source.value
            done.countDown()
        }
        assertTrue(done.await(10, TimeUnit.SECONDS))
        assertEquals(0, queries)
        assertNull(visible)
        assertNull(source.value)
    }
}
