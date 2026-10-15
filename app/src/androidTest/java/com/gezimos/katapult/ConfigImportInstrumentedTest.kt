package com.gezimos.katapult

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gezimos.katapult.util.PrefsManager
import com.gezimos.katapult.util.SensitiveChoice
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ConfigImportInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    private fun withPrefs(test: (PrefsManager, SharedPreferences) -> Unit) {
        val name = "f5-${UUID.randomUUID()}"
        val raw = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        // A nonempty existing store prevents fresh-install defaults obscuring absent-key cases.
        assertTrue(raw.edit().putString("rename_existing", "Keep me").commit())
        val fixture = object : ContextWrapper(context) {
            override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = raw
        }
        try { test(PrefsManager(fixture), raw) }
        finally { assertTrue(context.deleteSharedPreferences(name)) }
    }

    private fun config() = JSONObject().put("_type", "katapult_config")

    private fun rejectsUnchanged(prefs: PrefsManager, raw: SharedPreferences, json: JSONObject) {
        val before = raw.all.toMap()
        assertFalse("Accepted invalid import: $json", prefs.importFromJson(json.toString()))
        assertEquals("Failed import changed stored preferences: $json", before, raw.all)
        assertEquals(before["rename_existing"], prefs.getAppRename("existing"))
    }

    @Test fun allDeclaredBooleansRejectWrongTypesWhenNeverStored() = withPrefs { prefs, raw ->
        val keys = listOf("notification_indicators", "show_battery", "show_alarm", "show_weather", "weather_celsius",
            "rounded_icons", "dark_mode", "hide_status_bar", "hide_status_bar_clock", "eink_refresh_home",
            "double_tap_brightness", "infinite_scroll", "home_extra_row", "disable_music_widget",
            "show_katapult_icon", "hide_app_names", "hide_arrow_buttons", "disable_home_editing",
            "hide_all_apps_button", "swipe_up_all_apps", "home_islands", "vertical_app_gestures",
            "lockscreen_widget", "lockscreen_music_widget", "lockscreen_weather", "lockscreen_widget_latest_first",
            "screensaver_enabled", "screensaver_islands", "screensaver_wallpaper", "screensaver_show_clock",
            "screensaver_show_notifications", "screensaver_double_tap_brightness", "screensaver_eink_refresh",
            "screensaver_on_power")
        for (key in keys) {
            assertFalse(raw.contains(key))
            for (invalid in listOf("false", 0, JSONObject.NULL, JSONArray(), JSONObject())) {
                rejectsUnchanged(prefs, raw, config().put(key, invalid))
            }
        }
    }

    @Test fun allDeclaredIntegersRejectFractionalOverflowAndWrongTypes() = withPrefs { prefs, raw ->
        val keys = listOf("icon_size", "theme_mode", "eink_helper_mode", "double_tap_action",
            "last_brightness", "home_long_press", "app_sort_mode", "lockscreen_widget_y",
            "lockscreen_widget_rows", "screensaver_update_mode", "screensaver_update_minutes", "lockscreen_panel_test")
        for (key in keys) {
            assertFalse(raw.contains(key))
            for (invalid in listOf("1", false, 1.5, 2147483648L, -2147483649L,
                JSONObject.NULL, JSONArray(), JSONObject())) {
                rejectsUnchanged(prefs, raw, config().put(key, invalid))
            }
        }
    }

    @Test fun panelChoiceIsAPortableIntegerWithOnlyOffFourAndEightAllowed() = withPrefs { prefs, raw ->
        assertEquals(0, prefs.lockscreenPanelTest)
        for (invalid in listOf(-1, 1, 2, 5, 16)) {
            rejectsUnchanged(prefs, raw, config().put("lockscreen_panel_test", invalid))
        }
        for (choice in listOf(4, 8, 0)) {
            assertTrue(prefs.importFromJson(config().put("lockscreen_panel_test", choice).toString()))
            assertEquals(choice, prefs.lockscreenPanelTest)
            assertEquals(choice, JSONObject(prefs.exportToJson(context)).getInt("lockscreen_panel_test"))
        }
    }

    @Test fun stringsAndDynamicAppFieldsUseDeclaredTypes() = withPrefs { prefs, raw ->
        val keys = mutableListOf("clock_format", "date_format", "lockscreen_message", "wallpaper_path",
            "screensaver_wallpaper_path", "app_order", "pinned_shortcuts", "rename_com.example",
            "icon_override_com.example|shortcut")
        for (slot in PrefsManager.HOME_SLOTS) for (suffix in listOf("package", "activity", "shortcut_id")) {
            keys.add("${slot}_$suffix")
        }
        for (key in keys) for (invalid in listOf(true, 1, JSONObject.NULL, JSONArray(), JSONObject())) {
            rejectsUnchanged(prefs, raw, config().put(key, invalid))
        }
    }

    @Test fun stringSetsRequireArraysOfStrings() = withPrefs { prefs, raw ->
        for (key in listOf("hidden_apps", "lockscreen_widget_excluded")) {
            for (invalid in listOf("[]", true, 1, JSONObject.NULL, JSONObject(),
                JSONArray().put(1), JSONArray().put(JSONObject.NULL), JSONArray().put(JSONObject()))) {
                rejectsUnchanged(prefs, raw, config().put(key, invalid))
            }
        }
        assertTrue(prefs.importFromJson(config().put("hidden_apps", JSONArray().put("com.example"))
            .put("lockscreen_widget_excluded", JSONArray()).toString()))
        assertEquals(setOf("com.example"), prefs.getHiddenApps())
        assertTrue(prefs.getLockscreenExcludedApps().isEmpty())
    }

    @Test fun malformedDatePatternsAndSerializedArraysFailBeforeAnyChange() = withPrefs { prefs, raw ->
        for (key in listOf("clock_format", "date_format")) for (invalid in listOf("q", "yyyy '")) {
            rejectsUnchanged(prefs, raw, config().put("show_battery", true).put(key, invalid))
        }
        for (key in listOf("app_order", "pinned_shortcuts")) for (invalid in listOf("broken", "{}", "null", "")) {
            rejectsUnchanged(prefs, raw, config().put("show_battery", true).put(key, invalid))
        }
    }

    @Test fun invalidStoredFieldRejectsWholeImportAndEmitsNoPreferenceChanges() = withPrefs { prefs, raw ->
        assertTrue(raw.edit().putBoolean("show_battery", false).putInt("icon_size", 72)
            .putString("date_format", "system").commit())
        instrumentation.waitForIdleSync()
        val changes = mutableListOf<String?>()
        val subscription = prefs.subscribeChanges { changes.add(it) }
        try {
            rejectsUnchanged(prefs, raw, config().put("show_battery", true)
                .put("icon_size", 80).put("date_format", "yyyy '"))
            instrumentation.waitForIdleSync()
            assertTrue("Invalid import notified consumers: $changes", changes.isEmpty())
            assertFalse(prefs.showBattery)
            assertEquals(72, prefs.iconSize)
            assertEquals("system", prefs.dateFormat)
        } finally { subscription.close() }
    }

    @Test fun localStateIsNeverImportedAndOmissionPreservesPresenceAndAbsence() = withPrefs { prefs, raw ->
        assertTrue(raw.edit().putBoolean("onboarding_complete", true).putBoolean("defaults_seeded", false)
            .putString("pending_icon_target", "local-target").commit())
        val incoming = config().put("onboarding_complete", false).put("defaults_seeded", true)
            .put("pending_icon_target", "foreign-target").put("show_battery", true)
        assertTrue(prefs.importFromJson(incoming.toString()))
        assertTrue(prefs.onboardingComplete)
        assertEquals(false, raw.all["defaults_seeded"])
        assertEquals("local-target", prefs.pendingIconTarget)
        assertTrue(prefs.showBattery)
        assertTrue(prefs.importFromJson(config().toString()))
        assertTrue(prefs.onboardingComplete)
        assertEquals(false, raw.all["defaults_seeded"])
        assertEquals("local-target", prefs.pendingIconTarget)
        assertTrue(raw.edit().remove("onboarding_complete").remove("defaults_seeded")
            .remove("pending_icon_target").commit())
        assertTrue(prefs.importFromJson(incoming.toString()))
        for (key in listOf("onboarding_complete", "defaults_seeded", "pending_icon_target")) assertFalse(raw.contains(key))
        // Ignored local values do not enter field validation or replace local state.
        assertTrue(prefs.importFromJson(config().put("onboarding_complete", JSONObject())
            .put("defaults_seeded", JSONObject.NULL).put("pending_icon_target", false).toString()))
        assertFalse(prefs.onboardingComplete)
        assertNull(prefs.pendingIconTarget)
    }

    @Test fun validValuesReachPreferenceConsumersAndTypesDoNotDependOnExistingStorage() = withPrefs { prefs, raw ->
        assertTrue(raw.edit().putString("show_battery", "old-corrupt-type")
            .putBoolean("icon_size", false).commit())
        val shortcuts = JSONArray().put(JSONObject().put("package", "com.example")
            .put("id", "shortcut").put("label", "Shortcut"))
        assertTrue(prefs.importFromJson(config().put("show_battery", true).put("icon_size", 80)
            .put("lockscreen_widget_y", Int.MIN_VALUE).put("last_brightness", Int.MAX_VALUE)
            .put("screensaver_update_minutes", 5.0).put("clock_format", "HH:mm")
            .put("date_format", "yyyy-MM-dd").put("app_order", JSONArray().put("com.example").toString())
            .put("pinned_shortcuts", shortcuts.toString()).put("rename_com.example", "Renamed")
            .put("phone_package", "com.example").put("phone_activity", "Main")
            .put("phone_shortcut_id", "shortcut").put("icon_override_com.example", "material:Home").toString()))
        assertTrue(prefs.showBattery)
        assertEquals(80, prefs.iconSize)
        assertEquals(Int.MIN_VALUE, prefs.lockscreenWidgetY)
        assertEquals(Int.MAX_VALUE, prefs.lastBrightness)
        assertEquals(5, prefs.screensaverUpdateMinutes)
        assertEquals("HH:mm", prefs.clockFormat)
        assertEquals("yyyy-MM-dd", prefs.dateFormat)
        assertEquals(listOf("com.example"), prefs.loadAppOrder())
        assertEquals("Shortcut", prefs.getSavedShortcuts().single().label)
        assertEquals("Renamed", prefs.getAppRename("com.example"))
        assertEquals("com.example" to "Main", prefs.loadShortcut("phone"))
        assertEquals("shortcut", prefs.loadSlotShortcutId("phone"))
        assertEquals("material:Home", prefs.getIconOverride("com.example"))
        assertTrue(prefs.importFromJson(config().put("clock_format", "system").put("date_format", "system")
            .put("app_order", "[]").put("pinned_shortcuts", "[]").toString()))
        assertEquals("system", prefs.clockFormat)
        assertEquals("system", prefs.dateFormat)
        assertTrue(prefs.loadAppOrder()!!.isEmpty())
        assertTrue(prefs.getSavedShortcuts().isEmpty())
    }

    @Test fun sensitiveChoiceConfirmationAndUnknownFieldBehaviorAreUnchanged() = withPrefs { prefs, raw ->
        val choice = SensitiveChoice.SMS_BADGES
        prefs.setSensitiveChoice(choice, false)
        val incoming = config().put(choice.key, true).put("future_bool", true).put("future_int", 1.5)
            .put("future_string", "text").put("future_set", JSONArray().put("a").put(2).put(""))
            .put("future_object", JSONObject()).put("future_null", JSONObject.NULL)
            .put("_version", JSONObject())
        assertTrue(raw.edit().putBoolean("future_mismatch", false).commit())
        incoming.put("future_mismatch", "not-a-boolean")
        assertTrue(prefs.importFromJson(incoming.toString()))
        assertFalse(prefs.sensitiveChoice(choice))
        assertEquals(true, raw.all["future_bool"])
        assertEquals(1, raw.all["future_int"])
        assertEquals("text", raw.all["future_string"])
        assertEquals(setOf("a", "2"), raw.all["future_set"])
        assertFalse(raw.contains("future_mismatch"))
        assertFalse(raw.contains("future_object"))
        assertFalse(raw.contains("future_null"))
        assertFalse(raw.contains("_version"))
        val preview = prefs.previewPolicyImport(incoming.toString())!!
        assertTrue(prefs.importFromJson(incoming.toString(), preview.localChoices))
        assertTrue(prefs.sensitiveChoice(choice))
        assertTrue(prefs.importFromJson(config().toString()))
        assertTrue(prefs.sensitiveChoice(choice))
        val stale = prefs.previewPolicyImport(incoming.toString())!!
        prefs.setSensitiveChoice(choice, false)
        val before = raw.all.toMap()
        assertFalse(prefs.importFromJson(incoming.toString(), stale.localChoices))
        assertEquals(before, raw.all)
        for (invalid in listOf("false", 1, JSONObject.NULL)) {
            rejectsUnchanged(prefs, raw, config().put(choice.key, invalid))
        }
    }
}
