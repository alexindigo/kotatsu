package com.alexindigo.kotatsu.sync

import org.junit.Assert.*
import org.junit.Test

class SyncStatusTest {
    @Test fun stopReasonsAreNotDisplayText() {
        val field = SyncStatus::class.java.declaredFields.single { it.name == "stopReasons" }
        assertEquals(Set::class.java, field.type)
        assertTrue("Stop reasons must be a closed, typed contract, never free text",
            field.genericType.typeName.contains(SyncStopReason::class.java.name))
        assertTrue(SyncStopReason::class.java.isEnum)
    }

    @Test fun everyReasonHasOneLocalizedPhraseOrBelongsToTheAccessRows() {
        val expected = mapOf(
            SyncStopReason.MUDITA_CALENDAR_CHANGED to R.string.stopped_calendar_changed,
            SyncStopReason.CALENDAR_SETTINGS_REQUIRED to R.string.stopped_calendar_settings,
            SyncStopReason.ADDRESS_BOOK_REQUIRED to R.string.stopped_address_book,
            SyncStopReason.RETRY_REQUIRED to R.string.stopped_retry,
            SyncStopReason.REVIEW_REQUIRED to R.string.stopped_review,
            SyncStopReason.SCHEDULING_FAILED to R.string.stopped_scheduling,
        )
        for (reason in SyncStopReason.entries) {
            assertEquals(reason.name, expected[reason], reason.textResource())
            assertEquals(reason, SyncStopReason.fromCode(reason.code))
        }
        assertEquals(SyncStopReason.entries.size, SyncStopReason.entries.map { it.code }.toSet().size)
    }

    @Test fun accessCannotOverwriteAnyRealPassResult() {
        val real = SyncStatus(123, 2, setOf(SyncStopReason.REVIEW_REQUIRED))
        for (access in listOf(SyncStopReason.CALENDAR_ACCESS_REQUIRED, SyncStopReason.CONTACTS_ACCESS_REQUIRED)) {
            assertEquals(real, real.afterAttempt(456, 0, setOf(access)))
            assertEquals(real, real.afterAttempt(456, 1, setOf(access, SyncStopReason.RETRY_REQUIRED)))
        }
        assertEquals(SyncStatus(456, 0), real.afterAttempt(456, 0, emptySet()))
        assertNull(SyncStatus(availabilityStop = SyncStopReason.SCHEDULING_FAILED).forDisplay(false, true))
        assertEquals(real, real.copy(availabilityStop = SyncStopReason.SCHEDULING_FAILED).forDisplay(true, false))
    }

    @Test fun displayCopyUsesShortCompletePhrases() {
        val root = generateSequence(java.io.File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
            .first { java.io.File(it, "kotatsu-sync/src/main").isDirectory }
        val strings = javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(java.io.File(root, "kotatsu-sync/src/main/res/values/strings.xml")).getElementsByTagName("string")
        val phrases = (0 until strings.length).associate {
            val node = strings.item(it) as org.w3c.dom.Element
            node.getAttribute("name") to node.textContent
        }
        assertEquals(mapOf(
            "stopped_calendar_changed" to "Stopped: Mudita Calendar changed",
            "stopped_calendar_settings" to "Stopped: check calendar settings",
            "stopped_address_book" to "Stopped: choose an address book",
            "stopped_retry" to "Stopped: will try again",
            "stopped_review" to "Stopped: will try again",
            "stopped_scheduling" to "Sync could not start",
        ), phrases.filterKeys { it.startsWith("stopped_") })
    }

    @Test fun legacyDiagnosticsBecomeCodesAndUnknownTextNeverReachesTheUi() {
        val cases = mapOf(
            "calendar permission not granted" to SyncStopReason.CALENDAR_ACCESS_REQUIRED,
            "contacts permission not granted" to SyncStopReason.CONTACTS_ACCESS_REQUIRED,
            "Mudita Calendar missing or incompatible schema" to SyncStopReason.MUDITA_CALENDAR_CHANGED,
            "invalid calendar horizon" to SyncStopReason.CALENDAR_SETTINGS_REQUIRED,
            "contacts address book not selected" to SyncStopReason.ADDRESS_BOOK_REQUIRED,
            "contacts address book unavailable" to SyncStopReason.ADDRESS_BOOK_REQUIRED,
            "bridge scheduling: Could not schedule calendar bridge" to SyncStopReason.SCHEDULING_FAILED,
            "calendar pass incomplete: 1 pending writes" to SyncStopReason.REVIEW_REQUIRED,
            "UnexpectedException: internal diagnostics" to SyncStopReason.RETRY_REQUIRED,
        )
        for ((text, reason) in cases) assertEquals(setOf(reason), SyncStopReason.fromLegacy(text))
        assertEquals(SyncStopReason.RETRY_REQUIRED, SyncStopReason.fromCode("unknown future code"))
    }
}
