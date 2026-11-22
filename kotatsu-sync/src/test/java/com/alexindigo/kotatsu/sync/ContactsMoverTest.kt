package com.alexindigo.kotatsu.sync

import org.junit.Assert.*
import org.junit.Test

class ContactsMoverTest {
    private val personal = ContactAccount("Personal", "dav")
    private val family = ContactAccount("Family", "dav")
    private class Fake : ContactsPort {
        var books = setOf(ContactAccount("Personal", "dav"), ContactAccount("Family", "dav"))
        val rows = mutableListOf<PhoneContact>()
        val moves = mutableListOf<Pair<Long, ContactAccount>>()
        var fail: Long? = null
        override fun addressBooks() = books
        override fun sources() = rows.toList()
        override fun move(source: PhoneContact, destination: ContactAccount): Boolean {
            if (source.id == fail) return false
            moves.add(source.id to destination); rows.remove(source)
            return true
        }
    }
    @Test fun ongoingMovesOnlyPhoneContactsToPersonalAndRepeatingDoesNothing() {
        val f = Fake()
        f.rows.addAll(listOf(PhoneContact(1, 0, null, ContactOrigin.PHONE),
            PhoneContact(2, 0, personal, ContactOrigin.OTHER), PhoneContact(3, 0, null, ContactOrigin.SIM)))
        assertEquals(ContactsResult(1), ContactsMover(f).pass(personal))
        assertEquals(listOf(1L to personal), f.moves)
        assertEquals(ContactsResult(), ContactsMover(f).pass(personal))
    }
    @Test fun oneTimeMovesAllPhoneContactsIncludingSimImportsToFamilyWithoutTouchingSimMirrors() {
        val f = Fake()
        f.rows.addAll((1L..3).map { PhoneContact(it, 0, null, ContactOrigin.PHONE) })
        f.rows.add(PhoneContact(4, 0, ContactAccount("SIM", "sim"), ContactOrigin.SIM))
        assertEquals(ContactsResult(3), ContactsMover(f).moveExisting(family))
        assertTrue(f.moves.all { it.second == family }); assertEquals(1, f.rows.size)
    }
    @Test fun missingOrUnselectedAccountWritesNothing() {
        val f = Fake(); f.rows.add(PhoneContact(1, 0, null, ContactOrigin.PHONE))
        assertNotNull(ContactsMover(f).pass(null).stopReason)
        f.books = emptySet()
        assertNotNull(ContactsMover(f).pass(personal).stopReason); assertTrue(f.moves.isEmpty())
    }
    @Test fun failedItemRetriesWithoutDuplicatingCompletedMovesOrBlockingOtherItems() {
        val f = Fake(); f.rows.addAll((1L..3).map { PhoneContact(it, 0, null, ContactOrigin.PHONE) }); f.fail = 2
        assertEquals(ContactsResult(2, "contacts move incomplete; retry required"), ContactsMover(f).pass(personal))
        f.fail = null
        assertEquals(ContactsResult(1), ContactsMover(f).pass(personal))
        assertEquals(setOf(1L, 2L, 3L), f.moves.map { it.first }.toSet()); assertEquals(3, f.moves.size)
    }
}
