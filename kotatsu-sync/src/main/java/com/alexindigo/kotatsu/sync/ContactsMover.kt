package com.alexindigo.kotatsu.sync

/** The account of a DAVx5 address book, not its parent login account. */
data class ContactAccount(val name: String, val type: String, val dataSet: String? = null)
enum class ContactOrigin { PHONE, SIM, OTHER }
data class PhoneContact(val id: Long, val version: Long, val account: ContactAccount?, val origin: ContactOrigin)
data class ContactsSettings(val personal: ContactAccount? = null, val family: ContactAccount? = null)
data class ContactsResult(val changes: Int = 0, val stopReason: String? = null, val retry: Boolean = false)
data class ContactsMove(val destination: ContactAccount, val sourceIds: Set<Long>)
interface ContactsMoveStore { var pendingContactsMove: ContactsMove? }

interface ContactsPort {
    fun addressBooks(): Set<ContactAccount>
    fun sources(): List<PhoneContact>
    /** Version-checked, atomic insert-data-aggregate-delete. Never writes to a SIM. */
    fun move(source: PhoneContact, destination: ContactAccount): Boolean
}

/** No second contacts database: a committed move removes its own retry candidate. */
class ContactsMover(private val contacts: ContactsPort, private val changed: () -> Unit = {}) {
    var changes: Int = 0
        private set
    fun pass(destination: ContactAccount?, sourceIds: Set<Long>? = null, excluded: Set<Long> = emptySet()): ContactsResult {
        changes = 0
        if (destination == null) return ContactsResult(stopReason = "contacts address book not selected")
        if (destination !in contacts.addressBooks()) return ContactsResult(stopReason = "contacts address book unavailable")
        var failed = false
        for (source in contacts.sources().filter { it.origin == ContactOrigin.PHONE &&
            (sourceIds == null || it.id in sourceIds) && it.id !in excluded }) {
            // Re-read selection availability for each transaction, not just the initial list.
            if (destination !in contacts.addressBooks()) return ContactsResult(changes, "contacts address book unavailable")
            if (contacts.move(source, destination)) { changes++; changed() } else failed = true
        }
        return ContactsResult(changes, if (failed) "contacts move incomplete; retry required" else null, retry = failed)
    }
    // SIM imports are ordinary phone raw contacts. SIM-backed mirrors are never
    // candidates: importing a SIM in Mudita does not transfer ownership of the SIM.
    fun moveExisting(destination: ContactAccount?, sourceIds: Set<Long>? = null): ContactsResult = pass(destination, sourceIds)
}

/** Durable one-time intent reserves only its original phone ids. A failed Family
 * move must not silently fall through to Personal on the next automatic pass. */
class ContactsTransferEngine(private val contacts: ContactsPort, private val store: ContactsMoveStore) {
    var changes = 0
        private set
    private val mover = ContactsMover(contacts) { changes++ }
    fun pass(settings: ContactsSettings): ContactsResult {
        changes = 0
        val pending = store.pendingContactsMove
        val results = mutableListOf<ContactsResult>()
        if (pending != null) results.add(resume(pending))
        if (settings.personal != null) results.add(mover.pass(settings.personal, excluded = pending?.sourceIds.orEmpty()))
        return combine(results)
    }
    fun moveExisting(settings: ContactsSettings): ContactsResult {
        changes = 0
        val pending = store.pendingContactsMove ?: run {
            val target = settings.family ?: return ContactsResult(stopReason = "contacts address book not selected")
            if (target !in contacts.addressBooks()) return ContactsResult(stopReason = "contacts address book unavailable")
            ContactsMove(target, contacts.sources().filter { it.origin == ContactOrigin.PHONE }.map { it.id }.toSet())
                .also { store.pendingContactsMove = it }
        }
        return resume(pending)
    }
    private fun resume(move: ContactsMove): ContactsResult = mover.moveExisting(move.destination, move.sourceIds).also {
        if (it.stopReason == null) store.pendingContactsMove = null
    }
    private fun combine(results: List<ContactsResult>) = ContactsResult(changes,
        results.mapNotNull { it.stopReason }.distinct().takeIf { it.isNotEmpty() }?.joinToString("; "), results.any { it.retry })
}
