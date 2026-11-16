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
    private val state = ConnectedUiStateFixture()
    val raw: SharedPreferences = context.applicationContext.getSharedPreferences("katapult_prefs", Context.MODE_PRIVATE)
    private val before = preferenceSnapshot(raw)
    private var closed = false

    fun reset(configure: SharedPreferences.Editor.() -> Unit) {
        onMain { synchronized(raw) {
            assertTrue("Could not establish application fixture preferences", raw.edit().clear().apply(configure).commit())
            invalidateDerivedState()
        } }
    }

    override fun close() {
        if (closed) return
        closed = true
        fixtureCleanup(
            { state.closeProducers() },
            { state.closeActivities() },
            { onMain { synchronized(raw) { restore() } } },
            { state.close() },
        )
    }

    private fun restore() {
        restorePreferenceSnapshot(raw, before)
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
    synchronized(prefs) {
        assertTrue("Could not flush test preferences $name", prefs.edit().commit())
        assertTrue("Could not delete test preferences $name", context.deleteSharedPreferences(name))
    }
}

internal fun preferenceSnapshot(raw: SharedPreferences): Map<String, Any?> = synchronized(raw) {
    raw.all.mapValues { (_, value) -> if (value is Set<*>) value.toSet() else value }
}

/** A native OFF lease must not leave retained requests for service recovery in ordinary UI tests. */
internal class ServiceActivationPreferencesFixture(private val raw: SharedPreferences) : AutoCloseable {
    companion object {
        // All inputs to MainViewModel.lockscreenServiceFeatures / AccessibilityPolicy.serviceFeatures.
        val neutral = mapOf<String, Any>(
            "lockscreen_widget" to false,
            "lockscreen_music_widget" to false,
            "lockscreen_message" to "",
            "hide_status_bar_clock" to false,
            "double_tap_action" to com.gezimos.katapult.util.PrefsManager.DOUBLE_TAP_OFF,
            "screensaver_enabled" to false,
            "screensaver_on_power" to false,
            "lockscreen_weather" to false,
            "lockscreen_widgets_enabled" to false,
            "lockscreen_edge_swipes" to false,
        )
    }
    private val before = preferenceSnapshot(raw).let { snapshot -> neutral.keys.associateWith { snapshot[it] } }
    private var closed = false

    init {
        try { write(neutral) } catch (error: Throwable) {
            try { write(before) } catch (cleanup: Throwable) { error.addSuppressed(cleanup) }
            throw error
        }
    }

    private fun write(values: Map<String, Any?>) = synchronized(raw) {
        val editor = raw.edit()
        values.forEach { (key, value) -> editor.restoreValue(key, value) }
        assertTrue("Could not establish typed service activation preferences", editor.commit())
        values.forEach { (key, value) ->
            assertEquals(value != null, raw.contains(key))
            assertEquals(value, raw.all[key])
        }
    }

    override fun close() {
        if (closed) return
        write(before)
        closed = true
    }
}

internal fun SharedPreferences.Editor.restoreValue(key: String, value: Any?): SharedPreferences.Editor = apply {
    when (value) {
        null -> remove(key)
        is Boolean -> putBoolean(key, value)
        is Int -> putInt(key, value)
        is Long -> putLong(key, value)
        is Float -> putFloat(key, value)
        is String -> putString(key, value)
        is Set<*> -> putStringSet(key, value.map { it as String }.toSet())
        else -> error("Unsupported preference type for $key")
    }
}

internal fun restorePreferenceSnapshot(raw: SharedPreferences, snapshot: Map<String, Any?>) {
    synchronized(raw) {
        val editor = raw.edit().clear()
        snapshot.forEach { (key, value) -> editor.restoreValue(key, value) }
        assertTrue("Could not restore typed preferences", editor.commit())
        assertEquals("Preference presence/value changed", snapshot, preferenceSnapshot(raw))
    }
}

/** End readers before main-thread restoration, not merely at the outer rule's later close. */
internal fun restoreFixturePreferences(raw: SharedPreferences, snapshot: Map<String, Any?>) {
    ConnectedUiStateFixture.quiesceOwnedProducers()
    fixtureMain { restorePreferenceSnapshot(raw, snapshot) }
}

internal fun restoreFixturePreferenceValues(raw: SharedPreferences, snapshot: Map<String, Any?>) {
    ConnectedUiStateFixture.quiesceOwnedProducers()
    fixtureMain { synchronized(raw) {
        val editor = raw.edit()
        snapshot.forEach { (key, value) -> editor.restoreValue(key, value) }
        assertTrue("Could not restore owned preferences", editor.commit())
        snapshot.forEach { (key, value) ->
            assertEquals(value != null, raw.contains(key))
            assertEquals(value, raw.all[key])
        }
    } }
}

/** Observe disk without calling a reconciling product accessor. */
internal fun persistedPreferenceSnapshot(context: Context, name: String): Map<String, Any?> {
    val file = java.io.File(context.applicationInfo.dataDir, "shared_prefs/$name.xml")
    if (!file.exists()) return emptyMap()
    return file.inputStream().use { stream ->
        val parser = android.util.Xml.newPullParser().apply { setInput(stream, "UTF-8") }
        val result = linkedMapOf<String, Any?>()
        while (parser.next() != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
            if (parser.eventType != org.xmlpull.v1.XmlPullParser.START_TAG || parser.name == "map") continue
            val key = parser.getAttributeValue(null, "name") ?: error("Preference without a name")
            result[key] = when (parser.name) {
                "string" -> parser.nextText()
                "boolean" -> parser.getAttributeValue(null, "value").toBooleanStrict()
                "int" -> parser.getAttributeValue(null, "value").toInt()
                "long" -> parser.getAttributeValue(null, "value").toLong()
                "float" -> parser.getAttributeValue(null, "value").toFloat()
                "set" -> buildSet<String> {
                    while (parser.next() != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                        if (parser.eventType == org.xmlpull.v1.XmlPullParser.END_TAG && parser.name == "set") break
                        if (parser.eventType == org.xmlpull.v1.XmlPullParser.START_TAG && parser.name == "string") add(parser.nextText())
                    }
                }
                else -> error("Unknown preference XML type ${parser.name}")
            }
        }
        result
    }
}
