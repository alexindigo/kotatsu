package com.gezimos.katapult

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ActionServiceSettingsInstrumentedTest {
    @Test fun secureSettingRestoreKeepsMissingEmptyAndNonEmptyDistinct() {
        val commands = mutableListOf<String>()
        for (value in listOf("null", "", "0", "example/service")) {
            restoreSecureSetting("enabled_accessibility_services", value) { commands += it; "" }
        }
        assertEquals(listOf(
            "settings delete secure enabled_accessibility_services",
            "content call --uri content://settings/secure --method PUT_secure --arg enabled_accessibility_services --extra value:s:",
            "settings put secure enabled_accessibility_services 0",
            "settings put secure enabled_accessibility_services example/service",
        ), commands)
    }

    @Test fun persistenceBarrierReadsEnabledRecordRatherThanShortcutTargets() {
        val component = "com.alexindigo.kotatsu.debug/com.gezimos.katapult.lockscreen.LockscreenWidgetService"
        fun xml(enabled: String) = """<settings>
            <setting name="accessibility_shortcut_target_service" value="$component" />
            <setting name="enabled_accessibility_services" value="$enabled" />
        </settings>"""
        var reads = 0
        awaitActionServiceSettingsPersisted(component) {
            reads++
            xml(if (reads == 1) "" else component)
        }
        assertEquals("A shortcut target cannot satisfy enabled-service persistence", 2, reads)
        awaitActionServiceSettingsPersisted("null") { xml("") }
        awaitActionServiceSettingsPersisted("null") { "<settings /><namespaceHashes />" }
    }
}
