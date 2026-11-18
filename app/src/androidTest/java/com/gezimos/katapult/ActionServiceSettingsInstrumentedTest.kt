package com.gezimos.katapult

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ActionServiceSettingsInstrumentedTest {
    @Test fun nativeOwnerRestoresMissingAndEmptyPairsEvenWhenTheBodyThrows() {
        ActionServiceSettingsFixture.off().use {
            for ((enabled, accessibility) in listOf("null" to "null", "" to "null", "null" to "0", "" to "0", "" to "")) {
                ActionServiceSettingsFixture.exact(enabled, accessibility).use { original ->
                    val before = original.values()
                    val expected = IllegalStateException("owned body failure")
                    val thrown = runCatching {
                        ActionServiceSettingsFixture.on().use {
                            assertTrue(com.gezimos.katapult.recent.HomeKeyFilterState.active)
                            throw expected
                        }
                    }.exceptionOrNull()
                    assertSame(expected, thrown)
                    assertEquals(before, original.values())
                    assertFalse(com.gezimos.katapult.recent.HomeKeyFilterState.active)
                }
            }
        }
    }

    @Test fun offPreconditionRestoresAnOriginallyEnabledService() {
        ActionServiceSettingsFixture.on().use { original ->
            val before = original.values()
            ActionServiceSettingsFixture.off().use { assertFalse(com.gezimos.katapult.recent.HomeKeyFilterState.active) }
            assertEquals(before, original.values())
            assertTrue(com.gezimos.katapult.recent.HomeKeyFilterState.active)
        }
    }
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

    @Test fun exactPersistenceKeepsReconcilingAfterAFrameworkRewrite() {
        for (expectedFlag in listOf("0", "", "null")) {
            var live = expectedFlag
            var stored = "1"
            var rewrites = 0
            var repairs = 0
            awaitExactActionServiceSettingsPersisted(ActionServiceSettingsFixture.Values("", expectedFlag)) { command ->
                when {
                    command == "settings get secure enabled_accessibility_services" -> ""
                    command == "settings get secure accessibility_enabled" -> live
                    command.startsWith("su 0 abx2xml ") -> {
                        if (rewrites++ == 0) live = "1" // Arrives after the first matching live read.
                        """<settings>
                            <setting name="enabled_accessibility_services" value="" />
                            ${if (stored == "null") "" else "<setting name=\"accessibility_enabled\" value=\"$stored\" />"}
                        </settings>"""
                    }
                    else -> {
                        repairs++
                        restoreSecureSetting("accessibility_enabled", expectedFlag) { expectedCommand ->
                            assertEquals(expectedCommand, command)
                            ""
                        }
                        live = expectedFlag
                        stored = expectedFlag
                        ""
                    }
                }
            }
            assertTrue("The late framework write must be repaired", repairs > 0)
            assertEquals(expectedFlag, live)
            assertEquals(expectedFlag, stored)
        }
    }

    @Test fun exactPersistenceRechecksLiveStateAfterReadingMatchingDisk() {
        var live = "0"
        var reads = 0
        var repairs = 0
        awaitExactActionServiceSettingsPersisted(ActionServiceSettingsFixture.Values("", "0")) { command ->
            when (command) {
                "settings get secure enabled_accessibility_services" -> ""
                "settings get secure accessibility_enabled" -> live
                "settings put secure accessibility_enabled 0" -> { repairs++; live = "0"; "" }
                else -> {
                    assertEquals("su 0 abx2xml /data/system/users/0/settings_secure.xml -", command)
                    if (reads++ == 0) live = "1"
                    """<settings>
                        <setting name="enabled_accessibility_services" value="" />
                        <setting name="accessibility_enabled" value="0" />
                    </settings>"""
                }
            }
        }
        assertEquals("Matching stale disk cannot hide a late live rewrite", 1, repairs)
        assertEquals("0", live)
        assertTrue(reads >= 2)
    }
}
