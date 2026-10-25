package com.gezimos.katapult

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.platform.app.InstrumentationRegistry
import com.gezimos.katapult.lockscreen.LockscreenWidgetHost
import com.gezimos.katapult.lockscreen.LockscreenWidgetStore
import com.gezimos.katapult.util.PrefsManager
import org.junit.Assert.*
import java.util.UUID

/** Owns test allocations and a preference scope; never clears another fixture's host or bindings. */
internal class WidgetHostTestFixture(private val context: Context, applicationPreferences: Boolean = false) : AutoCloseable {
    val host = LockscreenWidgetHost.get(context)
    val manager = AppWidgetManager.getInstance(context)
    val providers = WidgetProviderTestFixture(context)
    private val initialIds = host.appWidgetIds.toSet()
    private val name = if (applicationPreferences) "katapult_prefs" else "widget-host-${UUID.randomUUID()}"
    val raw: SharedPreferences = context.getSharedPreferences(name, Context.MODE_PRIVATE)
    private val usesApplicationPreferences = applicationPreferences
    private val initialList = raw.all["lockscreen_widgets"]
    val prefs = PrefsManager(object : ContextWrapper(context) {
        override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = raw
    })

    init {
        // Real MainActivity reads the application store. Give its test a temporary empty selection,
        // retaining the exact previous value for restoration after the Activity/producers stop.
        if (applicationPreferences) assertTrue(raw.edit().putString("lockscreen_widgets", "[]").commit())
    }

    fun bind(info: AppWidgetProviderInfo): Int {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.adoptShellPermissionIdentity("android.permission.BIND_APPWIDGET")
        try {
            val id = host.allocateAppWidgetId()
            assertTrue(manager.bindAppWidgetIdIfAllowed(id, info.provider))
            return id
        } finally { automation.dropShellPermissionIdentity() }
    }

    /** Simulate external/legacy restored bytes, deliberately before the owner's reconciling read. */
    fun restoreExternalSelection(serialized: String) {
        assertTrue(raw.edit().putString(LockscreenWidgetStore.KEY, serialized).commit())
    }

    /** Call only after the test has stopped its Activities/models and their subscriptions. */
    override fun close() {
        try { releaseAllocationsAndPreferences() } finally { providers.close() }
    }

    private fun releaseAllocationsAndPreferences() {
        (host.appWidgetIds.toSet() - initialIds).forEach(host::deleteAppWidgetId)
        if (usesApplicationPreferences) {
            val editor = raw.edit()
            if (initialList == null) editor.remove("lockscreen_widgets") else editor.putString("lockscreen_widgets", initialList as String)
            // commit is also the barrier for any preceding production apply writes.
            assertTrue("Could not restore widget fixture preferences", editor.commit())
            assertEquals(initialList, raw.all["lockscreen_widgets"])
        } else deleteTestPreferences(context, name)
        assertEquals("Widget fixture leaked an allocation", initialIds, host.appWidgetIds.toSet())
    }
}
