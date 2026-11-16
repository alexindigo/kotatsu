package com.gezimos.katapult

import android.content.Intent
import android.os.PowerManager
import androidx.lifecycle.ViewModel
import androidx.test.platform.app.InstrumentationRegistry
import com.gezimos.katapult.lockscreen.LockscreenWidgetStore
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ConnectedUiStateFixtureInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val raw = context.getSharedPreferences("katapult_prefs", 0)

    @Test fun offUiScopeNeutralizesEveryRecoveryRequestAndRestoresTypedPresence() {
        ConnectedUiStateFixture().use {
            val neutral = ServiceActivationPreferencesFixture.neutral
            val requested = neutral.mapValues { (_, value) -> when (value) {
                is Boolean -> true
                is Int -> com.gezimos.katapult.util.PrefsManager.DOUBLE_TAP_LOCK
                else -> "Incoming message"
            } }
            for (present in listOf(false, true)) for (restoreAll in listOf(false, true)) {
                fixtureMain { synchronized(raw) {
                    val editor = raw.edit()
                    neutral.keys.forEach { key -> editor.restoreValue(key, if (present) requested[key] else null) }
                    check(editor.commit())
                } }
                val before = preferenceSnapshot(raw)
                ConnectedUiStateFixture(restorePreferences = restoreAll).use {
                    neutral.forEach { (key, value) -> assertEquals(key, value, raw.all[key]) }
                    val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
                    val ui = PlatformUiDriver(instrumentation)
                    ui.await("Neutral OFF scope must render HOME, not service recovery") {
                        ui.has(context.getString(R.string.all_apps))
                    }
                    fixtureMain {
                        assertFalse(activity.viewModel.showLockscreenReEnable)
                        assertTrue(activity.viewModel.lockscreenServiceFeatureTitles().isEmpty())
                    }
                }
                val disk = persistedPreferenceSnapshot(context, "katapult_prefs")
                neutral.keys.forEach { key ->
                    assertEquals("Cached presence: $key", before.containsKey(key), raw.contains(key))
                    assertEquals("Cached value: $key", before[key], raw.all[key])
                    assertEquals("Disk presence: $key", before.containsKey(key), disk.containsKey(key))
                    assertEquals("Disk value: $key", before[key], disk[key])
                }
            }
        }
    }

    @Test fun innerPreferenceRestoreWaitsForActivityAndModelBeforePublishingRestoredValues() {
        ConnectedUiStateFixture().use {
            val before = preferenceSnapshot(raw)
            val cleared = CountDownLatch(1)
            val marker = "fixture-restore-order-${java.util.UUID.randomUUID()}"
            val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            fixtureMain {
                activity.viewModelStore.put("restore-order-probe", object : ViewModel() {
                    override fun onCleared() { cleared.countDown() }
                })
                check(raw.edit().putString(marker, "changed").commit())
            }
            val observed = CountDownLatch(1)
            var restoredAfterDestruction = false
            val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                if ((key == null || key == marker) && !raw.contains(marker)) {
                    restoredAfterDestruction = activity.isDestroyed && cleared.count == 0L
                    observed.countDown()
                }
            }
            raw.registerOnSharedPreferenceChangeListener(listener)
            try {
                restoreFixturePreferences(raw, before)
                assertTrue(observed.await(5, TimeUnit.SECONDS))
                assertTrue("Restoration notified a still-live Activity/model", restoredAfterDestruction)
                assertEquals(before, preferenceSnapshot(raw))
            } finally { raw.unregisterOnSharedPreferenceChangeListener(listener) }
        }
    }

    @Test fun exceptionalBodyDestroysActivityAndClearsItsModelsBeforeRestoringPreferences() {
        val before = preferenceSnapshot(raw)
        val cleared = CountDownLatch(1)
        lateinit var activity: MainActivity
        val expected = AssertionError("owned body failure")
        val error = runCatching {
            ConnectedUiStateFixture().use {
                activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
                fixtureMain {
                    activity.viewModelStore.put("fixture-probe", object : ViewModel() {
                        override fun onCleared() { cleared.countDown() }
                    })
                    raw.edit().putString("fixture-owned-marker", "changed").commit()
                }
                throw expected
            }
        }.exceptionOrNull()
        assertSame(expected, error)
        assertTrue(activity.isDestroyed)
        assertEquals(0L, cleared.count)
        assertEquals(before, preferenceSnapshot(raw))
    }

    @Test fun serviceAndNativeDisplayScopesRestoreEnabledAndSleepingStarts() {
        ActionServiceSettingsFixture.off().use {
            val native = NativeKeyguardFixture()
            try {
                native.unlock()
                for (on in listOf(false, true)) {
                    val settings = if (on) ActionServiceSettingsFixture.on() else ActionServiceSettingsFixture.off()
                    settings.use {
                        for (asleep in listOf(false, true)) {
                            if (asleep) native.sleep() else native.unlock()
                            val original = settings.values()
                            ConnectedUiStateFixture().use {
                                assertFalse(com.gezimos.katapult.recent.HomeKeyFilterState.active)
                                assertTrue(context.getSystemService(PowerManager::class.java).isInteractive)
                            }
                            assertEquals(original, settings.values())
                            assertEquals(!asleep, context.getSystemService(PowerManager::class.java).isInteractive)
                        }
                    }
                }
            } finally { native.close() }
        }
    }

    @Test fun widgetRestorationPreservesMissingAndEmptyAgainstAnOriginallyEnabledService() {
        ConnectedUiStateFixture().use {
            for (original in listOf(null, "[]")) {
                fixtureMain { synchronized(raw) { raw.edit().restoreValue(LockscreenWidgetStore.KEY, original).commit() } }
                ActionServiceSettingsFixture.on().use {
                    WidgetHostTestFixture(context, applicationPreferences = true).use { fixture ->
                        val id = fixture.host.allocateAppWidgetId()
                        fixture.raw.edit().putString(LockscreenWidgetStore.KEY, "[{\"id\":$id,\"size\":\"M\"}]").apply()
                    }
                    instrumentation.waitForIdleSync()
                    assertEquals(original != null, raw.contains(LockscreenWidgetStore.KEY))
                    assertEquals(original, raw.all[LockscreenWidgetStore.KEY])
                    assertTrue(com.gezimos.katapult.recent.HomeKeyFilterState.active)
                }
            }
        }
    }

    @Test fun widgetTeardownCannotPassAReaderHoldingTheStoreMonitor() {
        ConnectedUiStateFixture().use {
            fixtureMain { raw.edit().remove(LockscreenWidgetStore.KEY).commit() }
            val fixture = WidgetHostTestFixture(context, applicationPreferences = true)
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val closing = CountDownLatch(1)
            val closed = CountDownLatch(1)
            val executor = Executors.newFixedThreadPool(2)
            try {
                val reader = executor.submit { synchronized(fixture.raw) {
                    fixture.prefs.lockscreenWidgetStore.read()
                    entered.countDown()
                    check(release.await(10, TimeUnit.SECONDS))
                } }
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                val teardown = executor.submit { closing.countDown(); try { fixture.close() } finally { closed.countDown() } }
                assertTrue(closing.await(5, TimeUnit.SECONDS))
                assertFalse("Restore escaped the store monitor", closed.await(100, TimeUnit.MILLISECONDS))
                release.countDown()
                reader.get(10, TimeUnit.SECONDS)
                teardown.get(10, TimeUnit.SECONDS)
                instrumentation.waitForIdleSync()
                assertFalse(raw.contains(LockscreenWidgetStore.KEY))
            } finally {
                release.countDown()
                executor.shutdown()
                assertTrue(executor.awaitTermination(15, TimeUnit.SECONDS))
                fixture.close()
            }
        }
    }

    @Test fun nestedWidgetScopeRetainsOriginalBoundBoxAndSideSelectionExactly() {
        WidgetHostTestFixture(context, applicationPreferences = true).use { parent ->
            val info = parent.providers.singleProvider(instrumentation.context.packageName)
            val box = parent.bind(info)
            val side = parent.bind(info)
            fixtureMain {
                assertEquals(LockscreenWidgetStore.Result.APPLIED, parent.prefs.lockscreenWidgetStore.addAfterConfigure(
                    com.gezimos.katapult.lockscreen.LockscreenWidget(box, com.gezimos.katapult.lockscreen.LockscreenWidgetSize.S)))
                assertEquals(LockscreenWidgetStore.Result.APPLIED, parent.prefs.lockscreenWidgetStore.placeAfterConfigure(0, side))
            }
            val selection = parent.raw.all[LockscreenWidgetStore.KEY]
            val screens = parent.raw.all[LockscreenWidgetStore.SCREENS]
            val ids = parent.host.appWidgetIds.toSet()
            WidgetHostTestFixture(context, applicationPreferences = true).use { inner -> inner.bind(info) }
            assertEquals(selection, parent.raw.all[LockscreenWidgetStore.KEY])
            assertEquals(screens, parent.raw.all[LockscreenWidgetStore.SCREENS])
            assertEquals(ids, parent.host.appWidgetIds.toSet())
            assertNotNull(parent.manager.getAppWidgetInfo(box))
            assertNotNull(parent.manager.getAppWidgetInfo(side))
        }
    }

    @Test fun failingProducerCleanupStillRestoresOtherOwnedResources() {
        val before = preferenceSnapshot(raw)
        val host = com.gezimos.katapult.lockscreen.LockscreenWidgetHost.get(context)
        val ids = host.appWidgetIds.toSet()
        val expected = AssertionError("fixture producer close")
        val error = runCatching {
            WidgetHostTestFixture(context, applicationPreferences = true).use { fixture ->
                fixture.own(AutoCloseable { throw expected })
                fixture.host.allocateAppWidgetId()
                fixture.raw.edit().putString("fixture-cleanup-marker", "changed").commit()
            }
        }.exceptionOrNull()
        assertSame(expected, error)
        assertEquals(before, preferenceSnapshot(raw))
        assertEquals(ids, host.appWidgetIds.toSet())
    }

    @Test fun recreationRetainsTheModelUntilFinalScenarioDestruction() {
        ConnectedUiStateFixture().use {
            ActivityLifetimeFixture().use { lifetime ->
                val cleared = CountDownLatch(1)
                lateinit var original: MainActivity
                lateinit var current: MainActivity
                val scenario = androidx.test.core.app.ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java))
                try {
                    scenario.onActivity {
                        original = it
                        it.viewModelStore.put("recreation-probe", object : ViewModel() {
                            override fun onCleared() { cleared.countDown() }
                        })
                    }
                    scenario.recreate()
                    scenario.onActivity { current = it; assertNotSame(original, current) }
                    assertTrue(original.isDestroyed)
                    assertEquals(1L, cleared.count)
                    lifetime.closeAndAwaitDestroyed(scenario)
                    assertTrue(current.isDestroyed)
                    assertEquals(0L, cleared.count)
                } finally { scenario.close() }
            }
        }
    }

    @Test fun initiallySleepingEnabledServiceCannotPreventTheOwnedScreensaverRendering() {
        ConnectedUiStateFixture().use {
            ConnectedUiStateFixture.prepareForegroundService()
            val native = NativeKeyguardFixture()
            try {
                ActionServiceSettingsFixture.on().use {
                    native.sleep()
                    lateinit var activity: com.gezimos.katapult.lockscreen.ScreensaverActivity
                    ConnectedUiStateFixture().use {
                        fixtureMain { raw.edit().putBoolean("screensaver_enabled", true).putBoolean("screensaver_show_clock", true)
                            .putString("clock_format", "'Owned clock'").commit() }
                        activity = instrumentation.startActivitySync(Intent(context, com.gezimos.katapult.lockscreen.ScreensaverActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as com.gezimos.katapult.lockscreen.ScreensaverActivity
                        PlatformUiDriver(instrumentation).await("Owned screensaver clock did not render") { PlatformUiDriver(instrumentation).has("Owned clock") }
                        assertTrue(context.getSystemService(PowerManager::class.java).isInteractive)
                    }
                    assertTrue(activity.isDestroyed)
                    assertFalse(context.getSystemService(PowerManager::class.java).isInteractive)
                }
            } finally { native.close() }
        }
    }
}
