package com.alexindigo.kotatsu.sync

import org.junit.Assert.*
import org.junit.Test

class ContactsTransferEngineTest {
    private val personal = ContactAccount("Personal", "dav")
    private val family = ContactAccount("Family", "dav")
    private class Store : ContactsMoveStore { override var pendingContactsMove: ContactsMove? = null }
    @Test fun duplicateBookDisplayLabelsDoNotOverrideThePersonalCollection() {
        val type = "at.bitfire.davdroid.address_book"
        val familyPersonal = ContactAccount("Family (Personal) #1", type)
        val familyWork = ContactAccount("Family (Work) #2", type)
        val personalWork = ContactAccount("Personal (Work) #3", type)
        val moved = mutableListOf<ContactAccount>()
        val port = object : ContactsPort {
            override fun addressBooks() = setOf(familyPersonal, familyWork, personalWork)
            override fun sources() = listOf(PhoneContact(1, 0, null, ContactOrigin.PHONE))
            override fun move(source: PhoneContact, destination: ContactAccount): Boolean {
                moved.add(destination)
                return true
            }
        }
        val choices = port.addressBookChoices()
        assertEquals(familyPersonal.name, choices.single { it.account == familyPersonal }.label)
        assertEquals(familyPersonal, ContactsSettings(familyPersonal).destination(choices)?.account)
        val engine = ContactsTransferEngine(port, Store())
        assertEquals(1, engine.pass(ContactsSettings()).changes)
        assertEquals(1, engine.moveExisting(ContactsSettings()).changes)
        assertEquals(listOf(personalWork, personalWork), moved)
    }
    @Test fun bulkAutomaticTransferWaitsUntilTheExactMoveIsConfirmed() {
        val rows = (1L..3).map { PhoneContact(it, 0, null, ContactOrigin.PHONE) }.toMutableList()
        val port = object : ContactsPort {
            override fun addressBooks() = setOf(personal)
            override fun sources() = rows.toList()
            override fun move(source: PhoneContact, destination: ContactAccount) = rows.remove(source)
        }
        val store = Store()
        val engine = ContactsTransferEngine(port, store)
        assertEquals(ContactsResult(waiting = 3), engine.pass(ContactsSettings(personal)))
        assertEquals(3, rows.size)
        store.pendingContactsMove = ContactsMove(personal, setOf(1, 2, 3))
        rows.add(PhoneContact(4, 0, null, ContactOrigin.PHONE))
        assertEquals(3, engine.moveExisting(ContactsSettings(personal)).changes)
        assertEquals(listOf(4L), rows.map { it.id })
    }

    @Test fun explicitMoveCountsLaterWaitingContactsWithoutMovingThem() {
        val rows = (1L..5).map { PhoneContact(it, 0, null, ContactOrigin.PHONE) }.toMutableList()
        val port = object : ContactsPort {
            override fun addressBooks() = setOf(personal)
            override fun sources() = rows.toList()
            override fun move(source: PhoneContact, destination: ContactAccount) = rows.remove(source)
        }
        val store = Store().apply { pendingContactsMove = ContactsMove(personal, setOf(1, 2)) }
        val result = ContactsTransferEngine(port, store).moveExisting(ContactsSettings(personal))
        assertEquals(ContactsResult(changes = 2, waiting = 3), result)
        assertEquals(listOf(3L, 4L, 5L), rows.map { it.id })
        assertNull(store.pendingContactsMove)
    }
    @Test fun failedFamilyMoveKeepsItsDestinationAcrossReopenAndNewContactsGoToPersonal() {
        val store = Store()
        val rows = mutableListOf(PhoneContact(1, 0, null, ContactOrigin.PHONE), PhoneContact(2, 0, null, ContactOrigin.PHONE))
        val moved = mutableMapOf<Long, ContactAccount>()
        var fail = true
        val port = object : ContactsPort {
            override fun addressBooks() = setOf(personal, family)
            override fun sources() = rows.toList()
            override fun move(source: PhoneContact, destination: ContactAccount): Boolean {
                if (source.id == 2L && fail) return false
                assertTrue(store.pendingContactsMove != null || destination == personal)
                rows.remove(source); moved[source.id] = destination; return true
            }
        }
        assertTrue(ContactsTransferEngine(port, store).moveExisting(ContactsSettings(family)).retry)
        val settings = ContactsSettings(personal)
        assertNotNull(store.pendingContactsMove)
        rows.add(PhoneContact(3, 0, null, ContactOrigin.PHONE))
        val partial = ContactsTransferEngine(port, store).pass(settings)
        assertTrue(partial.retry); assertEquals(1, partial.changes)
        assertEquals(mapOf(1L to family, 3L to personal), moved)
        fail = false
        assertEquals(ContactsResult(1), ContactsTransferEngine(port, store).pass(settings))
        assertEquals(family, moved[2]); assertNull(store.pendingContactsMove)
    }
    @Test fun lostFamilyCommitResultDoesNotRepeatOrClaimNewLocalContacts() {
        val store = Store(); store.pendingContactsMove = ContactsMove(family, setOf(1))
        val row = PhoneContact(2, 0, null, ContactOrigin.PHONE)
        val moved = mutableListOf<ContactAccount>()
        val port = object : ContactsPort {
            override fun addressBooks() = setOf(personal, family)
            override fun sources() = listOf(row)
            override fun move(source: PhoneContact, destination: ContactAccount): Boolean { moved.add(destination); return true }
        }
        assertEquals(ContactsResult(1), ContactsTransferEngine(port, store).pass(ContactsSettings(personal)))
        assertEquals(listOf(personal), moved); assertNull(store.pendingContactsMove)
    }
}
