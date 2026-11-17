package com.gezimos.katapult.lockscreen

import com.gezimos.katapult.withFixtureCleanup

import com.gezimos.katapult.platformAutomation
import android.appwidget.AppWidgetHost
import androidx.test.platform.app.InstrumentationRegistry
import com.gezimos.katapult.WidgetHostTestFixture
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class LockscreenWidgetReconciliationInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val weather = "com.alexindigo.kotatsu.weather.debug"

    @Test fun externallyRemovedBindingsDoNotReserveSpaceOrBlockNewWidgets() {
        WidgetHostTestFixture(context).use { fixture ->
            val info = fixture.providers.singleProvider(weather)
            val widgets = List(3) { LockscreenWidget(fixture.bind(info), LockscreenWidgetSize.M) }
            instrumentation.runOnMainSync {
                fixture.restoreExternalSelection(LockscreenWidgetList.encode(widgets))
                val settings = com.gezimos.katapult.ownFixtureMain(LockscreenWidgetSettings(context, fixture.prefs))
                withFixtureCleanup(body = {
                    widgets.forEach { fixture.host.deleteAppWidgetId(it.id) }
                    settings.refresh()
                    assertTrue("Removed bindings must not remain as configured rows", settings.rows.isEmpty())
                    assertEquals("[]", fixture.prefs.lockscreenWidgets)
                    var configuredId: Int? = null
                    instrumentation.platformAutomation.adoptShellPermissionIdentity("android.permission.BIND_APPWIDGET")
                    withFixtureCleanup({ instrumentation.platformAutomation.dropShellPermissionIdentity() }) {
                        settings.add(info, { fail("Binding should be allowed") }, { configuredId = it })
                    }
                    assertNotNull("Ghosts must not consume the size budget", configuredId)
                    settings.configured(true)
                    assertEquals(listOf(configuredId), settings.rows.map { it.widget.id })
                }, actions = arrayOf<() -> Unit>({ settings.close() }))
            }
        }
    }

    @Test fun unboundAllocationsAreDeletedButForeignHostsAreNotTouched() {
        WidgetHostTestFixture(context).use { fixture ->
            val info = fixture.providers.singleProvider(weather)
            val unbound = fixture.host.allocateAppWidgetId()
            val otherHost = AppWidgetHost(context, 0x4B5501)
            val foreign = otherHost.allocateAppWidgetId()
            instrumentation.platformAutomation.adoptShellPermissionIdentity("android.permission.BIND_APPWIDGET")
            withFixtureCleanup({ instrumentation.platformAutomation.dropShellPermissionIdentity() }) {
                assertTrue(fixture.manager.bindAppWidgetIdIfAllowed(foreign, info.provider))
            }
            withFixtureCleanup(body = {
                instrumentation.runOnMainSync {
                    fixture.restoreExternalSelection(LockscreenWidgetList.encode(listOf(unbound, foreign, Int.MAX_VALUE)
                        .map { LockscreenWidget(it, LockscreenWidgetSize.M) }))
                    val settings = com.gezimos.katapult.ownFixtureMain(LockscreenWidgetSettings(context, fixture.prefs))
                    withFixtureCleanup(body = {
                        assertTrue(settings.rows.isEmpty())
                        assertEquals("[]", fixture.prefs.lockscreenWidgets)
                        assertFalse(unbound in fixture.host.appWidgetIds)
                        assertTrue("Foreign host binding must survive", foreign in otherHost.appWidgetIds)
                        assertNotNull(fixture.manager.getAppWidgetInfo(foreign))
                    }, actions = arrayOf<() -> Unit>({ settings.close() }))
                }
            }, actions = arrayOf<() -> Unit>({ otherHost.deleteAppWidgetId(foreign) }))
        }
    }

    @Test fun validLocalBindingsSurviveImportAndRestoredForeignIdsAreReconciled() {
        WidgetHostTestFixture(context).use { fixture ->
            val info = fixture.providers.singleProvider(weather)
            val local = listOf(LockscreenWidget(fixture.bind(info), LockscreenWidgetSize.M))
            instrumentation.runOnMainSync {
                fixture.restoreExternalSelection(LockscreenWidgetList.encode(local))
                val settings = com.gezimos.katapult.ownFixtureMain(LockscreenWidgetSettings(context, fixture.prefs))
                withFixtureCleanup(body = {
                    // Import from another install must not replace a local binding, even if the
                    // imported numeric ID happens to exist on this installation in another host.
                    val imported = JSONObject().put("_type", "katapult_config")
                        .put("lockscreen_widgets", "[{\"id\":2147483647,\"size\":\"L\"}]")
                    assertTrue(fixture.prefs.importFromJson(imported.toString()))
                    assertEquals(local, settings.rows.map { it.widget })
                    // OS restore/old local state can still contain IDs that have no binding here.
                    fixture.restoreExternalSelection(LockscreenWidgetList.encode(local + LockscreenWidget(Int.MAX_VALUE, LockscreenWidgetSize.L)))
                    settings.refresh()
                    assertEquals(local, settings.rows.map { it.widget })
                    assertEquals(LockscreenWidgetList.encode(local), fixture.prefs.lockscreenWidgets)
                    assertNotNull(fixture.manager.getAppWidgetInfo(local.single().id))
                }, actions = arrayOf<() -> Unit>({ settings.close() }))
            }
        }
    }

    @Test fun cancelledAndFailedConfigurationReleasePendingBindings() {
        WidgetHostTestFixture(context).use { fixture ->
            val info = fixture.providers.singleProvider(weather)
            instrumentation.runOnMainSync {
                val settings = com.gezimos.katapult.ownFixtureMain(LockscreenWidgetSettings(context, fixture.prefs))
                withFixtureCleanup(body = {
                    for (failed in listOf(false, true)) {
                        var pending: Int? = null
                        instrumentation.platformAutomation.adoptShellPermissionIdentity("android.permission.BIND_APPWIDGET")
                        withFixtureCleanup({ instrumentation.platformAutomation.dropShellPermissionIdentity() }) {
                            settings.add(info, { fail("Binding should be allowed") }, { pending = it })
                        }
                        assertNotNull(pending)
                        if (failed) settings.launchFailed() else settings.configured(false)
                        assertNull(fixture.manager.getAppWidgetInfo(pending!!))
                        assertFalse(pending!! in fixture.host.appWidgetIds)
                        assertTrue(settings.rows.isEmpty())
                        assertEquals("[]", fixture.prefs.lockscreenWidgets)
                    }
                }, actions = arrayOf<() -> Unit>({ settings.close() }))
            }
        }
    }

    @Test fun providerRemovalEventDropsItsStaleSelection() {
        WidgetHostTestFixture(context).use { fixture ->
            val info = fixture.providers.singleProvider(weather)
            val widget = LockscreenWidget(fixture.bind(info), LockscreenWidgetSize.M)
            var settings: LockscreenWidgetSettings? = null
            withFixtureCleanup(body = {
                instrumentation.runOnMainSync {
                    fixture.restoreExternalSelection(LockscreenWidgetList.encode(listOf(widget)))
                    settings = com.gezimos.katapult.ownFixtureMain(LockscreenWidgetSettings(context, fixture.prefs))
                }
                // PackageManager's component-removal event exercises the platform provider-removal
                // path without uninstalling the separate app/data used by the other suites.
                fixture.providers.disable(info)
                assertNull(fixture.manager.getAppWidgetInfo(widget.id))
                instrumentation.runOnMainSync {
                    settings!!.refresh()
                    assertTrue(settings!!.rows.isEmpty())
                    assertEquals("[]", fixture.prefs.lockscreenWidgets)
                }
            }, actions = arrayOf<() -> Unit>({ instrumentation.runOnMainSync { settings?.close() } }))
        }
    }

}
