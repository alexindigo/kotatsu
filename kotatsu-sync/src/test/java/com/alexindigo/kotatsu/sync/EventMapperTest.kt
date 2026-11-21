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
        assertEquals("Location: Home${EventMapper.LOCATION_MARKER}\n${EventMapper.BODY_MARKER}Bring food", phone.description)
        val edited = mapper.toStandard(phone.copy(description = phone.description.replace("Home", "Elsewhere").replace("Bring food", "Changed")), standard, true)
        assertEquals("Changed", edited.description)
        assertEquals("Home", edited.location)
        assertEquals("Location: Home${EventMapper.LOCATION_MARKER}\n${EventMapper.BODY_MARKER}Changed", mapper.toMudita(edited).description)
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
        assertEquals(mapper.muditaFingerprint(phone, true), mapper.muditaFingerprint(phone.copy(description = phone.description.replace("Home", "Other")), true))
    }

    @Test fun multilineLocationNeverLeaksIntoServerDescription() {
        val event = standard.copy(location = "Building\r\nRoom\u2028Floor\rWing")
        val phone = mapper.toMudita(event)
        assertEquals(2, phone.description.lines().size)
        assertEquals(event, mapper.toStandard(phone, event, true))
    }
    @Test fun ordinaryLocationDescriptionIsNeverRemoved() {
        val event = standard.copy(location = "", description = "Location: Gate A\nBring ID")
        val phone = mapper.toMudita(event)
        assertEquals(event, mapper.toStandard(phone, event))
        val changed = phone.copy(description = "Location: Gate B\nBring ID")
        assertNotEquals(mapper.muditaFingerprint(phone), mapper.muditaFingerprint(changed))
        assertEquals(changed.description, mapper.toStandard(changed, event).description)
    }
    @Test fun removingGeneratedLineDoesNotConsumeTheFirstOrdinaryBodyLine() {
        val event = standard.copy(description = "Location: Gate A\nBring ID")
        val phone = mapper.toMudita(event)
        val withoutGeneratedLine = phone.copy(description = phone.description.substringAfter('\n'))
        assertEquals(event.description, mapper.toStandard(withoutGeneratedLine, event, true).description)
    }
    @Test fun replacingTheWholeGeneratedLineStillCannotLeakItIntoTheServerBody() {
        val phone = mapper.toMudita(standard)
        val changed = phone.copy(description = "Location: Elsewhere\n" + phone.description.substringAfter('\n').replace("Bring food", "Changed"))
        assertEquals("Changed", mapper.toStandard(changed, standard, true).description)
        assertEquals("Home", mapper.toStandard(changed, standard, true).location)
    }

    @Test fun timedInstantsRoundTrip() {
        assertEquals(standard, mapper.toStandard(mapper.toMudita(standard), standard, true))
    }

    @Test fun allDayDatesRoundTripAcrossDst() {
        val event = standard.copy(allDay = true, start = Instant.parse("2026-03-08T00:00:00Z").toEpochMilli(),
            end = Instant.parse("2026-03-10T00:00:00Z").toEpochMilli())
        val phone = mapper.toMudita(event)
        assertEquals(Instant.parse("2026-03-08T08:00:00Z").toEpochMilli(), phone.start)
        assertEquals(Instant.parse("2026-03-10T07:00:00Z").toEpochMilli(), phone.end)
        assertEquals(event, mapper.toStandard(phone, event, true))
    }
}
