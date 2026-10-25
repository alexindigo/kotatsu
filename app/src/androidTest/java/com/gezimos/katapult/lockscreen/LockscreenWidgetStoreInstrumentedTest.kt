package com.gezimos.katapult.lockscreen

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.platform.app.InstrumentationRegistry
import com.gezimos.katapult.WidgetHostTestFixture
import com.gezimos.katapult.util.PrefsManager
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class LockscreenWidgetStoreInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    /** Pause a real external layout lookup, not the list transaction itself. */
    private class PausedWindowContext(base: Context, private val raw: SharedPreferences) : ContextWrapper(base) {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        private val armed = AtomicBoolean(true)
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = raw
        override fun getSystemService(name: String): Any? {
            if (name == Context.WINDOW_SERVICE && Thread.currentThread().name == "held-widget-editor" && armed.compareAndSet(true, false)) {
                entered.countDown()
                check(release.await(8, TimeUnit.SECONDS)) { "Layout lookup was not released" }
            }
            return super.getSystemService(name)
        }
    }

    private fun interleavedResize(count: Int, other: (WidgetHostTestFixture, LockscreenWidgetSettings, List<Int>) -> List<LockscreenWidget>) {
        WidgetHostTestFixture(context).use { fixture ->
            val info = fixture.providers.singleProvider("com.alexindigo.kotatsu.weather.debug")
            val ids = List(count) { fixture.bind(info) }
            assertTrue(fixture.raw.edit().putString("lockscreen_widgets", LockscreenWidgetList.encode(ids.map {
                LockscreenWidget(it, LockscreenWidgetSize.S)
            })).commit())
            val paused = PausedWindowContext(context, fixture.raw)
            lateinit var held: LockscreenWidgetSettings
            lateinit var second: LockscreenWidgetSettings
            instrumentation.runOnMainSync {
                held = LockscreenWidgetSettings(paused, PrefsManager(paused))
                second = LockscreenWidgetSettings(context, fixture.prefs)
            }
            val executor = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "held-widget-editor") }
            try {
                val operation = executor.submit { held.resize(ids.first(), LockscreenWidgetSize.M) }
                assertTrue("Resize never reached the external layout boundary", paused.entered.await(8, TimeUnit.SECONDS))
                lateinit var expected: List<LockscreenWidget>
                instrumentation.runOnMainSync { expected = other(fixture, second, ids) }
                paused.release.countDown()
                operation.get(8, TimeUnit.SECONDS)
                assertEquals("Held editor must apply to the current stored list", expected,
                    LockscreenWidgetList.decode(fixture.prefs.lockscreenWidgets))
            } finally {
                paused.release.countDown()
                executor.shutdownNow()
                assertTrue(executor.awaitTermination(8, TimeUnit.SECONDS))
                instrumentation.runOnMainSync { held.close(); second.close() }
            }
        }
    }

    @Test fun twoSettingsInstancesDoNotLoseAnInterleavedResize() = interleavedResize(3) { _, second, ids ->
        second.resize(ids.last(), LockscreenWidgetSize.L)
        listOf(LockscreenWidget(ids[0], LockscreenWidgetSize.M), LockscreenWidget(ids[1], LockscreenWidgetSize.S),
            LockscreenWidget(ids[2], LockscreenWidgetSize.L))
    }

    @Test fun aHeldResizeDoesNotLoseANewlyConfiguredWidget() = interleavedResize(2) { fixture, second, ids ->
        val added = configure(fixture, second)
        listOf(LockscreenWidget(ids[0], LockscreenWidgetSize.M), LockscreenWidget(ids[1], LockscreenWidgetSize.S), added)
    }

    @Test fun reconciliationAndSettingsCannotResurrectAGhostOrLoseItsReplacement() = interleavedResize(2) { fixture, second, ids ->
        fixture.host.deleteAppWidgetId(ids.last())
        assertEquals(listOf(ids.first()), fixture.prefs.lockscreenWidgetStore.read().map { it.id })
        val added = configure(fixture, second)
        listOf(LockscreenWidget(ids.first(), LockscreenWidgetSize.M), added)
    }

    @Test fun notificationOperationIsNotLostDuringReconciliation() {
        WidgetHostTestFixture(context).use { fixture ->
            val info = fixture.providers.singleProvider("com.alexindigo.kotatsu.weather.debug")
            val first = fixture.bind(info)
            val added = fixture.bind(info)
            val ghost = fixture.host.allocateAppWidgetId()
            fixture.restoreExternalSelection(LockscreenWidgetList.encode(listOf(first, ghost).map {
                LockscreenWidget(it, LockscreenWidgetSize.S)
            }))
            val armed = AtomicBoolean(true)
            val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                if (key == LockscreenWidgetStore.KEY && armed.compareAndSet(true, false)) {
                    assertEquals(LockscreenWidgetStore.Result.APPLIED,
                        fixture.prefs.lockscreenWidgetStore.addAfterConfigure(LockscreenWidget(added, LockscreenWidgetSize.M)))
                }
            }
            fixture.raw.registerOnSharedPreferenceChangeListener(listener)
            try {
                instrumentation.runOnMainSync {
                    assertEquals(LockscreenWidgetStore.Result.APPLIED, fixture.prefs.lockscreenWidgetStore.resize(first, LockscreenWidgetSize.M))
                    assertEquals(listOf(LockscreenWidget(first, LockscreenWidgetSize.M), LockscreenWidget(added, LockscreenWidgetSize.M)),
                        fixture.prefs.lockscreenWidgetStore.read())
                    assertFalse(ghost in fixture.host.appWidgetIds)
                }
            } finally { fixture.raw.unregisterOnSharedPreferenceChangeListener(listener) }
        }
    }

    @Test fun heldSettingsMoveUsesCurrentOrderAfterResizeAndImport() {
        WidgetHostTestFixture(context).use { fixture ->
            val info = fixture.providers.singleProvider("com.alexindigo.kotatsu.weather.debug")
            val ids = List(3) { fixture.bind(info) }
            val initial = ids.map { LockscreenWidget(it, LockscreenWidgetSize.S) }
            assertEquals(LockscreenWidgetStore.Result.APPLIED, fixture.prefs.lockscreenWidgetStore.replaceImportedSelection(LockscreenWidgetList.encode(initial)))
            instrumentation.runOnMainSync {
                val first = LockscreenWidgetSettings(context, fixture.prefs)
                val second = LockscreenWidgetSettings(context, PrefsManager(object : ContextWrapper(context) {
                    override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = fixture.raw
                }))
                try {
                    second.move(ids.last(), -2)
                    second.resize(ids[1], LockscreenWidgetSize.M)
                    assertTrue(fixture.prefs.importFromJson(JSONObject().put("_type", "katapult_config")
                        .put("lockscreen_widgets", "[{\"id\":2147483647,\"size\":\"L\"}]").toString()))
                    first.move(ids.first(), -1)
                    val expected = listOf(LockscreenWidget(ids[2], LockscreenWidgetSize.S),
                        LockscreenWidget(ids[0], LockscreenWidgetSize.S), LockscreenWidget(ids[1], LockscreenWidgetSize.M))
                    assertEquals(expected, fixture.prefs.lockscreenWidgetStore.read())
                    assertEquals(expected, LockscreenWidgetList.decode(JSONObject(fixture.prefs.exportToJson(context)).getString(LockscreenWidgetStore.KEY)))
                } finally { first.close(); second.close() }
            }
        }
    }

    @Test fun explicitImportAndPortableImportUseTheReconcilingOwner() {
        WidgetHostTestFixture(context).use { fixture ->
            val info = fixture.providers.singleProvider("com.alexindigo.kotatsu.weather.debug")
            val first = fixture.bind(info)
            val second = fixture.bind(info)
            val store = fixture.prefs.lockscreenWidgetStore
            assertEquals(LockscreenWidgetStore.Result.APPLIED, store.replaceImportedSelection(LockscreenWidgetList.encode(listOf(
                LockscreenWidget(first, LockscreenWidgetSize.S), LockscreenWidget(Int.MAX_VALUE, LockscreenWidgetSize.L)))))
            assertEquals(listOf(first), store.read().map { it.id })
            assertEquals(LockscreenWidgetStore.Result.APPLIED, store.addAfterConfigure(LockscreenWidget(second, LockscreenWidgetSize.M)))
            assertEquals(LockscreenWidgetStore.Result.APPLIED, store.resize(second, LockscreenWidgetSize.L))
            assertEquals(LockscreenWidgetStore.Result.APPLIED, store.addAfterConfigure(LockscreenWidget(second, LockscreenWidgetSize.M)))
            assertEquals(LockscreenWidgetSize.L, store.read().single { it.id == second }.size)
            val before = fixture.raw.getString(LockscreenWidgetStore.KEY, null)
            assertEquals(LockscreenWidgetStore.Result.INVALID, store.replaceImportedSelection("not a widget list"))
            assertEquals(before, fixture.raw.getString(LockscreenWidgetStore.KEY, null))
            fixture.host.deleteAppWidgetId(first)
            assertTrue(fixture.prefs.importFromJson(JSONObject().put("_type", "katapult_config")
                .put("lockscreen_widgets", "foreign IDs stay local").put("show_alarm", true).toString()))
            assertEquals(listOf(LockscreenWidget(second, LockscreenWidgetSize.L)), store.read())
            assertTrue(fixture.prefs.showAlarm)
            assertEquals(LockscreenWidgetList.encode(store.read()), fixture.raw.getString(LockscreenWidgetStore.KEY, null))
        }
    }

    private fun configure(fixture: WidgetHostTestFixture, settings: LockscreenWidgetSettings): LockscreenWidget {
        val info = fixture.providers.singleProvider("com.alexindigo.kotatsu.weather.debug")
        var id: Int? = null
        instrumentation.uiAutomation.adoptShellPermissionIdentity("android.permission.BIND_APPWIDGET")
        try { settings.add(info, { fail("Binding should be allowed") }, { id = it }) }
        finally { instrumentation.uiAutomation.dropShellPermissionIdentity() }
        assertNotNull(id)
        settings.configured(true)
        return settings.rows.single { it.widget.id == id }.widget
    }
}
