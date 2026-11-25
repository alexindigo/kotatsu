package com.alexindigo.kotatsu.sync

/** The account of a DAVx5 address book, not its parent login account. */
data class ContactAccount(val name: String, val type: String, val dataSet: String? = null)
enum class ContactOrigin { PHONE, SIM, OTHER }
data class PhoneContact(val id: Long, val version: Long, val account: ContactAccount?, val origin: ContactOrigin)
data class ContactsSettings(val target: ContactAccount? = null,
    val mode: TargetMode = if (target == null) TargetMode.AUTOMATIC else TargetMode.EXPLICIT,
) {
    fun destination(choices: List<AddressBookChoice>): AddressBookChoice? = if (mode == TargetMode.AUTOMATIC)
        preferPersonal(choices, AddressBookChoice::collectionLabel) else choices.singleOrNull { it.account == target }
}
data class ContactsResult(val changes: Int = 0, val stopReasons: Set<SyncStopReason> = emptySet(),
    // Null means no inventory was assessed; a fresh zero clears the previous count.
    val retry: Boolean = false, val waiting: Int? = 0)
data class ContactsMove(val destination: ContactAccount, val sourceIds: Set<Long>)
interface ContactsMoveStore { var pendingContactsMove: ContactsMove? }

interface ContactsPort {
    fun addressBooks(): Set<ContactAccount>
    fun addressBookChoices(): List<AddressBookChoice> = addressBookChoices(addressBooks())
    fun sources(): List<PhoneContact>
    /** Version-checked, atomic insert-data-aggregate-delete. Never writes to a SIM. */
    fun move(source: PhoneContact, destination: ContactAccount): Boolean
}

private fun ContactsPort.phoneCandidates(sourceIds: Set<Long>? = null, excluded: Set<Long> = emptySet()) =
    sources().filter { it.origin == ContactOrigin.PHONE && (sourceIds == null || it.id in sourceIds) && it.id !in excluded }

private fun List<PhoneContact>.bulkWaiting() = if (size >= BULK) size else 0

/** No second contacts database: a committed move removes its own retry candidate. */
class ContactsMover(private val contacts: ContactsPort, private val changed: () -> Unit = {}) {
    var changes: Int = 0
        private set
    fun pass(destination: ContactAccount?, sourceIds: Set<Long>? = null, excluded: Set<Long> = emptySet()): ContactsResult {
        return move(destination, sourceIds, excluded, automatic = sourceIds == null)
    }
    private fun move(destination: ContactAccount?, sourceIds: Set<Long>?, excluded: Set<Long>, automatic: Boolean): ContactsResult {
        changes = 0
        if (destination == null) return ContactsResult(stopReasons = setOf(SyncStopReason.ADDRESS_BOOK_REQUIRED))
        if (destination !in contacts.addressBooks()) return ContactsResult(stopReasons = setOf(SyncStopReason.ADDRESS_BOOK_REQUIRED))
        var failed = false
        val sources = contacts.phoneCandidates(sourceIds, excluded)
        val waiting = sources.bulkWaiting()
        if (automatic && waiting > 0) return ContactsResult(waiting = waiting)
        for (source in sources) {
            // Re-read selection availability for each transaction, not just the initial list.
            if (destination !in contacts.addressBooks()) return ContactsResult(changes, setOf(SyncStopReason.ADDRESS_BOOK_REQUIRED))
            if (contacts.move(source, destination)) { changes++; changed() } else failed = true
        }
        return ContactsResult(changes, if (failed) setOf(SyncStopReason.RETRY_REQUIRED) else emptySet(), retry = failed)
    }
    // SIM imports are ordinary phone raw contacts. SIM-backed mirrors are never
    // candidates: importing a SIM in Mudita does not transfer ownership of the SIM.
    fun moveExisting(destination: ContactAccount?, sourceIds: Set<Long>? = null): ContactsResult =
        move(destination, sourceIds, emptySet(), automatic = false)
}

/** A durable move keeps its destination even when the setting changes later. */
class ContactsTransferEngine(private val contacts: ContactsPort, private val store: ContactsMoveStore) {
    var changes = 0
        private set
    private val mover = ContactsMover(contacts) { changes++ }
    fun pass(settings: ContactsSettings): ContactsResult {
        changes = 0
        val pending = store.pendingContactsMove
        val results = mutableListOf<ContactsResult>()
        if (pending != null) results.add(resume(pending))
        val target = settings.destination(contacts.addressBookChoices())?.account
        if (target != null || settings.mode == TargetMode.EXPLICIT)
            results.add(mover.pass(target, excluded = pending?.sourceIds.orEmpty()))
        return combine(results)
    }
    fun moveExisting(settings: ContactsSettings): ContactsResult {
        changes = 0
        val pending = store.pendingContactsMove ?: run {
            val target = settings.destination(contacts.addressBookChoices())?.account
                ?: return ContactsResult(stopReasons = setOf(SyncStopReason.ADDRESS_BOOK_REQUIRED))
            ContactsMove(target, contacts.phoneCandidates().map { it.id }.toSet())
                .also { store.pendingContactsMove = it }
        }
        val result = resume(pending)
        // Confirmation permits only its own IDs. Assess later arrivals without moving them.
        val waiting = if (settings.destination(contacts.addressBookChoices()) == null) 0
            else contacts.phoneCandidates(excluded = store.pendingContactsMove?.sourceIds.orEmpty()).bulkWaiting()
        return result.copy(waiting = waiting)
    }
    private fun resume(move: ContactsMove): ContactsResult = mover.moveExisting(move.destination, move.sourceIds).also {
        if (it.stopReasons.isEmpty()) store.pendingContactsMove = null
    }
    private fun combine(results: List<ContactsResult>) = ContactsResult(changes,
        results.flatMap { it.stopReasons }.toSet(), results.any { it.retry }, results.mapNotNull { it.waiting }.sum())
}
