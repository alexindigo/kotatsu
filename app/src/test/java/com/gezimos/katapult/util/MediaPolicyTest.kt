package com.gezimos.katapult.util

import org.junit.Assert.*
import org.junit.Test

class MediaPolicyTest {
    @Test fun badgeOffMediaOnAndControlsOffMetadataOnAreIndependent() {
        val values = SensitivePolicy.effectiveChoices(mapOf(
            SensitiveChoice.NOTIFICATION_BADGES.key to false,
            SensitiveChoice.MEDIA_INFO.key to true,
            SensitiveChoice.MEDIA_CONTROLS.key to false))
        val metadata = MediaPolicy.metadata(values.getValue(SensitiveChoice.MEDIA_INFO.key), true, true)
        assertTrue(metadata)
        assertFalse(values.getValue(SensitiveChoice.NOTIFICATION_BADGES.key))
        assertFalse(MediaPolicy.controls(metadata, values.getValue(SensitiveChoice.MEDIA_CONTROLS.key)))
        var commands = 0
        assertFalse(MediaPolicy.command({ MediaPolicy.controls(metadata, false) }) { commands++; true })
        assertEquals(0, commands)
        assertTrue(MediaPolicy.command({ MediaPolicy.controls(metadata, true) }) { commands++; true })
        assertEquals(1, commands)
    }

    @Test fun revokedOrInactiveMetadataCannotAuthorizeTransport() {
        for ((access, demand) in listOf(false to true, true to false, false to false)) {
            val metadata = MediaPolicy.metadata(true, access, demand)
            var commands = 0
            assertFalse(MediaPolicy.command({ MediaPolicy.controls(metadata, true) }) { commands++; true })
            assertEquals(0, commands)
        }
    }

    @Test fun oldSessionCannotPublishAfterCleanupOrNewSessionSelection() {
        val display = SourceGeneration<String>()
        display.reconcile(true)
        val old = display.epoch
        assertTrue(SourceRead(display).run(old, { true }) { "synthetic first session" })
        display.reconcile(false)
        assertNull(display.value)
        display.reconcile(true)
        assertFalse(SourceRead(display).run(old, { true }) { "late old callback" })
        assertTrue(SourceRead(display).run(display.epoch, { true }) { "synthetic new session" })
        assertEquals("synthetic new session", display.value)
    }
}
