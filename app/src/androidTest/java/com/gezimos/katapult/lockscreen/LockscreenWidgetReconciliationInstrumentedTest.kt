package com.gezimos.katapult.lockscreen

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
                val settings = LockscreenWidgetSettings(context, fixture.prefs)
                try {
                    widgets.forEach { fixture.host.deleteAppWidgetId(it.id) }
                    settings.refresh()
                    assertTrue("Removed bindings must not remain as configured rows", settings.rows.isEmpty())
                    assertEquals("[]", fixture.prefs.lockscreenWidgets)
                    var configuredId: Int? = null
                    instrumentation.uiAutomation.adoptShellPermissionIdentity("android.permission.BIND_APPWIDGET")
                    try { settings.add(info, { fail("Binding should be allowed") }, { configuredId = it }) }
                    finally { instrumentation.uiAutomation.dropShellPermissionIdentity() }
                    assertNotNull("Ghosts must not consume the size budget", configuredId)
                    settings.configured(true)
                    assertEquals(listOf(configuredId), settings.rows.map { it.widget.id })
                } finally { settings.close() }
            }
        }
    }

    @Test fun unboundAllocationsAreDeletedButForeignHostsAreNotTouched() {
        WidgetHostTestFixture(context).use { fixture ->
            val info = fixture.providers.singleProvider(weather)
            val unbound = fixture.host.allocateAppWidgetId()
            val otherHost = AppWidgetHost(context, 0x4B5501)
            val foreign = otherHost.allocateAppWidgetId()
            instrumentation.uiAutomation.adoptShellPermissionIdentity("android.permission.BIND_APPWIDGET")
            try { assertTrue(fixture.manager.bindAppWidgetIdIfAllowed(foreign, info.provider)) }
            finally { instrumentation.uiAutomation.dropShellPermissionIdentity() }
            try {
                instrumentation.runOnMainSync {
                    fixture.restoreExternalSelection(LockscreenWidgetList.encode(listOf(unbound, foreign, Int.MAX_VALUE)
                        .map { LockscreenWidget(it, LockscreenWidgetSize.M) }))
                    val settings = LockscreenWidgetSettings(context, fixture.prefs)
                    try {
                        assertTrue(settings.rows.isEmpty())
                        assertEquals("[]", fixture.prefs.lockscreenWidgets)
                        assertFalse(unbound in fixture.host.appWidgetIds)
                        assertTrue("Foreign host binding must survive", foreign in otherHost.appWidgetIds)
                        assertNotNull(fixture.manager.getAppWidgetInfo(foreign))
                    } finally { settings.close() }
                }
            } finally { otherHost.deleteAppWidgetId(foreign) }
        }
    }

    @Test fun validLocalBindingsSurviveImportAndRestoredForeignIdsAreReconciled() {
        WidgetHostTestFixture(context).use { fixture ->
            val info = fixture.providers.singleProvider(weather)
            val local = listOf(LockscreenWidget(fixture.bind(info), LockscreenWidgetSize.M))
            instrumentation.runOnMainSync {
                fixture.restoreExternalSelection(LockscreenWidgetList.encode(local))
                val settings = LockscreenWidgetSettings(context, fixture.prefs)
                try {
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
                } finally { settings.close() }
            }
        }
    }

    @Test fun cancelledAndFailedConfigurationReleasePendingBindings() {
        WidgetHostTestFixture(context).use { fixture ->
            val info = fixture.providers.singleProvider(weather)
            instrumentation.runOnMainSync {
                val settings = LockscreenWidgetSettings(context, fixture.prefs)
                try {
                    for (failed in listOf(false, true)) {
                        var pending: Int? = null
                        instrumentation.uiAutomation.adoptShellPermissionIdentity("android.permission.BIND_APPWIDGET")
                        try { settings.add(info, { fail("Binding should be allowed") }, { pending = it }) }
                        finally { instrumentation.uiAutomation.dropShellPermissionIdentity() }
                        assertNotNull(pending)
                        if (failed) settings.launchFailed() else settings.configured(false)
                        assertNull(fixture.manager.getAppWidgetInfo(pending!!))
                        assertFalse(pending!! in fixture.host.appWidgetIds)
                        assertTrue(settings.rows.isEmpty())
                        assertEquals("[]", fixture.prefs.lockscreenWidgets)
                    }
                } finally { settings.close() }
            }
        }
    }

    @Test fun providerRemovalEventDropsItsStaleSelection() {
        WidgetHostTestFixture(context).use { fixture ->
            val info = fixture.providers.singleProvider(weather)
            val widget = LockscreenWidget(fixture.bind(info), LockscreenWidgetSize.M)
            var settings: LockscreenWidgetSettings? = null
            try {
                instrumentation.runOnMainSync {
                    fixture.restoreExternalSelection(LockscreenWidgetList.encode(listOf(widget)))
                    settings = LockscreenWidgetSettings(context, fixture.prefs)
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
            } finally {
                instrumentation.runOnMainSync { settings?.close() }
            }
        }
    }

}
