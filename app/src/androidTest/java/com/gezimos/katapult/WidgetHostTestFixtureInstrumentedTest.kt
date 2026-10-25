package com.gezimos.katapult

import androidx.test.platform.app.InstrumentationRegistry
import com.gezimos.katapult.lockscreen.LockscreenWidget
import com.gezimos.katapult.lockscreen.LockscreenWidgetHost
import com.gezimos.katapult.lockscreen.LockscreenWidgetList
import com.gezimos.katapult.lockscreen.LockscreenWidgetSize
import org.junit.Assert.*
import org.junit.Test

class WidgetHostTestFixtureInstrumentedTest {
    @Test fun scopeRestoresItsSelectionAndReleasesEveryNewAllocation() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val raw = context.getSharedPreferences("katapult_prefs", 0)
        val beforeValue = raw.all["lockscreen_widgets"]
        val host = LockscreenWidgetHost.get(context)
        val beforeIds = host.appWidgetIds.toSet()
        WidgetHostTestFixture(context, applicationPreferences = true).use { fixture ->
            val id = fixture.host.allocateAppWidgetId()
            fixture.prefs.lockscreenWidgets = LockscreenWidgetList.encode(listOf(LockscreenWidget(id, LockscreenWidgetSize.M)))
            assertTrue(id in host.appWidgetIds)
            // Deliberately leave allocation and apply write for the shared scope to own at teardown.
        }
        assertEquals(beforeValue, raw.all["lockscreen_widgets"])
        assertEquals(beforeIds, host.appWidgetIds.toSet())
    }
}
