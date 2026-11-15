package com.gezimos.katapult

import android.content.Context
import android.content.SharedPreferences
import android.os.Looper
import androidx.test.platform.app.InstrumentationRegistry
import com.gezimos.katapult.util.IconUtility
import com.gezimos.katapult.util.UsageHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/** Own the real application store when a fixture exercises process-wide app services.
 * Stop Activities/models before closing; never give those services a disposable store. */
internal class ApplicationPreferencesFixture(context: Context) : AutoCloseable {
    val raw: SharedPreferences = context.applicationContext.getSharedPreferences("katapult_prefs", Context.MODE_PRIVATE)
    private val before = raw.all.mapValues { (_, value) -> if (value is Set<*>) value.toSet() else value }

    fun reset(configure: SharedPreferences.Editor.() -> Unit) {
        onMain {
            assertTrue("Could not establish application fixture preferences", raw.edit().clear().apply(configure).commit())
            invalidateDerivedState()
        }
    }

    override fun close() = onMain { restore() }

    private fun restore() {
        val editor = raw.edit().clear()
        before.forEach { (key, value) -> when (value) {
            is Boolean -> editor.putBoolean(key, value)
            is Int -> editor.putInt(key, value)
            is Long -> editor.putLong(key, value)
            is Float -> editor.putFloat(key, value)
            is String -> editor.putString(key, value)
            is Set<*> -> editor.putStringSet(key, value.map { it as String }.toSet())
            else -> error("Unsupported preference type for $key")
        } }
        assertTrue("Could not restore application fixture preferences", editor.commit())
        invalidateDerivedState()
        assertEquals("Application fixture leaked preference state", before, raw.all)
    }

    private fun invalidateDerivedState() {
        UsageHelper.invalidate()
        IconUtility.invalidateOverrides()
    }

    private fun onMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action()
        else InstrumentationRegistry.getInstrumentation().runOnMainSync(action)
    }
}

/** Call after the fixture has stopped its producers and closed its subscriptions. */
internal fun deleteTestPreferences(context: Context, name: String) {
    // Editor.commit waits for outstanding apply writes on this same store. Deletion alone
    // has no disk-write barrier, so an earlier apply could otherwise recreate XML or .bak.
    // Use the actual named backing store, not a fixture's deliberately failing editor wrapper.
    val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
    assertTrue("Could not flush test preferences $name", prefs.edit().commit())
    assertTrue("Could not delete test preferences $name", context.deleteSharedPreferences(name))
}
