package com.alexindigo.kotatsu.sync

import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class EventMapperTest {
    private val mapper = EventMapper(ZoneId.of("America/Los_Angeles"))
    private val standard = StandardEvent(1, 2, "Dinner", "Bring food", "Home", 1000, 2000)

    @Test fun locationIsOneLeadingLineAndNeverWrittenBack() {
        val phone = mapper.toMudita(standard)
        assertEquals("Location: Home\nBring food", phone.description)
        val edited = mapper.toStandard(phone.copy(description = "Location: Elsewhere\nChanged"), standard)
        assertEquals("Changed", edited.description)
        assertEquals("Home", edited.location)
        assertEquals("Location: Home\nChanged", mapper.toMudita(edited).description)
    }

    @Test fun reminderFlagIsPreservedButExcludedFromFingerprint() {
        val phone = mapper.toMudita(standard)
        assertFalse(phone.preReminder)
        val withReminder = phone.copy(preReminder = true)
        assertTrue(mapper.toMudita(standard, withReminder).preReminder)
        assertEquals(mapper.muditaFingerprint(phone), mapper.muditaFingerprint(withReminder))
    }

    @Test fun locationLineEditDoesNotCountAsPhoneChange() {
        val phone = mapper.toMudita(standard)
        assertEquals(mapper.muditaFingerprint(phone), mapper.muditaFingerprint(phone.copy(description = "Location: Other\nBring food")))
    }

    @Test fun timedInstantsRoundTrip() {
        assertEquals(standard, mapper.toStandard(mapper.toMudita(standard), standard))
    }

    @Test fun allDayDatesRoundTripAcrossDst() {
        val event = standard.copy(allDay = true, start = Instant.parse("2026-03-08T00:00:00Z").toEpochMilli(),
            end = Instant.parse("2026-03-10T00:00:00Z").toEpochMilli())
        val phone = mapper.toMudita(event)
        assertEquals(Instant.parse("2026-03-08T08:00:00Z").toEpochMilli(), phone.start)
        assertEquals(Instant.parse("2026-03-10T07:00:00Z").toEpochMilli(), phone.end)
        assertEquals(event, mapper.toStandard(phone, event))
    }
}
