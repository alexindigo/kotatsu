package com.alexindigo.kotatsu.sync

import org.junit.Assert.*
import org.junit.Test

class SyncTargetIntentTest {
    private val family = SyncCalendar(1, "dav", true, "Radicale", "FamilyR", "family")
    private val personal = SyncCalendar(2, "dav", true, "Radicale", "PersonalR", "personal")

    @Test fun onlyAPickerChoicePinsTheCalendarIdentity() {
        val available = listOf(family, personal)
        val first = SyncSettings(setOf(family.id))
        assertEquals(family, first.destination(available))
        val both = CalendarSettingsIntent.Toggle(personal.id).reduce(first, available)
        assertEquals(personal, both.destination(available))
        assertNull(both.newEventCalendar)
        val explicit = CalendarSettingsIntent.Choose(family.id).reduce(both, available)
        assertEquals(family.identity, explicit.targetIdentity)
        assertEquals(family, explicit.destination(available))
        assertNull(explicit.destination(listOf(family.copy(accountName = "Different"), personal)))
        assertNull(explicit.destination(listOf(personal)))
        assertEquals(TargetMode.EXPLICIT, readTargetMode(null, saved = true))
        assertEquals(TargetMode.AUTOMATIC, readTargetMode(null, saved = false))
    }

    @Test fun davx5PresentationNeverChangesTheAccountIdentity() {
        val type = "at.bitfire.davdroid.address_book"
        val family = ContactAccount("Family (Radicale) #3", type)
        val personal = ContactAccount("Personal (Radicale (Home)) #4", type, "set")
        val before = AddressBookCatalog(setOf(family))
        val after = AddressBookCatalog(setOf(personal, family))
        assertEquals(AddressBookChoice(family, "Family"), ContactsSettings().destination(before))
        assertEquals(AddressBookChoice(personal, "Personal"), ContactsSettings().destination(after))
        assertEquals(family, ContactsSettings(family).destination(after)?.account)
        assertNull(ContactsSettings(personal).destination(before))
        assertEquals("Friends (work)", addressBookLabel(ContactAccount("Friends (work) (Radicale) #12", type)))
        assertEquals("Personal (Radicale) #abc", addressBookLabel(ContactAccount("Personal (Radicale) #abc", type)))
        assertEquals("Personal (Radicale) #4", addressBookLabel(ContactAccount("Personal (Radicale) #4", "unrelated")))
    }

    @Test fun onlyAnUnacknowledgedPreferredBookBlocksAutomaticMoves() {
        val personal = ContactAccount("Personal", "dav")
        val family = ContactAccount("Family", "dav")
        val pendingPersonal = AddressBookCatalog(setOf(family), setOf(personal))
        assertEquals(listOf(family), pendingPersonal.choices.map { it.account })
        assertNull(ContactsSettings().destination(pendingPersonal))
        assertEquals(family, ContactsSettings(family).destination(pendingPersonal)?.account)
        assertNull(ContactsSettings(personal).destination(pendingPersonal))
        assertEquals(personal, ContactsSettings().destination(AddressBookCatalog(setOf(personal), setOf(family)))?.account)
    }
}
