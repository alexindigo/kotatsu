package com.gezimos.katapult.lockscreen

import android.appwidget.AppWidgetHostView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LockscreenWidgetHostInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun AppWidgetHostView.padding() = listOf(paddingLeft, paddingTop, paddingRight, paddingBottom)

    @Test fun oneWakeIdentityReachesBothSurfacesAndSurvivesOwnerRestart() {
        com.gezimos.katapult.WidgetHostTestFixture(context).use { fixture ->
            val info = fixture.providers.singleProvider("com.alexindigo.kotatsu.weather.debug")
            val box = LockscreenWidget(fixture.bind(info), LockscreenWidgetSize.M)
            val side = LockscreenWidget(fixture.bind(info), LockscreenWidgetSize.L)
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                val wake = HostedWidgetWake(context)
                val before = wake.current()
                val token = wake.begin()
                assertEquals(before + 1, token)
                val visibility = LockscreenVisibility().apply { beginEpoch(); recheck(true) { false } }
                fun assertTokens() {
                    for (widget in listOf(box, side)) assertEquals(token,
                        fixture.manager.getAppWidgetOptions(widget.id).getLong(LockscreenWidgetHost.OPTION_HOST_WAKE))
                }
                val host = LockscreenWidgetHost.get(context)
                host.showSurface(context, LockscreenWidgetHost.Surface.SIDE, listOf(side))
                repeat(2) { LockscreenWidgetHost.pulseVisible(context, visibility, listOf(box)) }
                assertTokens()
                host.hideWidgets(context)
                visibility.recheck(true) { true }
                LockscreenWidgetHost.pulseVisible(context, visibility, emptyList())
                visibility.recheck(true) { false }
                LockscreenWidgetHost.pulseVisible(context, visibility, listOf(box))
                LockscreenWidgetHost.updateSize(context, box.copy(size = LockscreenWidgetSize.S))
                assertTokens()
                // A new service has a fresh visibility epoch, but reads the same durable wake owner.
                val restarted = LockscreenVisibility().apply { beginEpoch(); recheck(true) { false } }
                LockscreenWidgetHost.pulseVisible(context, restarted, listOf(box))
                assertEquals(token, HostedWidgetWake(context).current())
                assertTokens()
                assertEquals(token + 1, HostedWidgetWake(context).begin())
                host.showSurface(context, LockscreenWidgetHost.Surface.SIDE, emptyList())
                host.hideWidgets(context)
            }
        }
    }

    @Test fun rebindingKeepsTheBoxWidgetViewUnpadded() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            // The platform puts its default padding back on every (re)bind, e.g. after a provider app update.
            val platform = AppWidgetHostView(context).apply { setPadding(0, 0, 0, 0); setAppWidget(1, null) }
            assertNotEquals(listOf(0, 0, 0, 0), platform.padding())

            val box = UnpaddedHostView(context).apply { setAppWidget(1, null) }
            assertEquals(listOf(0, 0, 0, 0), box.padding())
            box.setAppWidget(1, null)
            assertEquals(listOf(0, 0, 0, 0), box.padding())
        }
    }
}
