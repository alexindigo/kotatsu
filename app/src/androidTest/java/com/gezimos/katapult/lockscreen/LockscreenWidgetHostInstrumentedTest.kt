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
