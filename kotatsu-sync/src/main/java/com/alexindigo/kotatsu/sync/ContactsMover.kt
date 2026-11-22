package com.alexindigo.kotatsu.sync

/** The account of a DAVx5 address book, not its parent login account. */
data class ContactAccount(val name: String, val type: String, val dataSet: String? = null)
enum class ContactOrigin { PHONE, SIM, OTHER }
data class PhoneContact(val id: Long, val version: Long, val account: ContactAccount?, val origin: ContactOrigin)
data class ContactsSettings(val personal: ContactAccount? = null, val family: ContactAccount? = null)
data class ContactsResult(val changes: Int = 0, val stopReason: String? = null)

interface ContactsPort {
    fun addressBooks(): Set<ContactAccount>
    fun sources(): List<PhoneContact>
    /** Version-checked, atomic insert-data-aggregate-delete. Never writes to a SIM. */
    fun move(source: PhoneContact, destination: ContactAccount): Boolean
}

/** No second contacts database: a committed move removes its own retry candidate. */
class ContactsMover(private val contacts: ContactsPort) {
    fun pass(destination: ContactAccount?): ContactsResult {
        if (destination == null) return ContactsResult(stopReason = "contacts address book not selected")
        if (destination !in contacts.addressBooks()) return ContactsResult(stopReason = "contacts address book unavailable")
        var changes = 0
        var failed = false
        for (source in contacts.sources().filter { it.origin == ContactOrigin.PHONE }) {
            // Re-read selection availability for each transaction, not just the initial list.
            if (destination !in contacts.addressBooks()) return ContactsResult(changes, "contacts address book unavailable")
            if (contacts.move(source, destination)) changes++ else failed = true
        }
        return ContactsResult(changes, if (failed) "contacts move incomplete; retry required" else null)
    }
    // SIM imports are ordinary phone raw contacts. SIM-backed mirrors are never
    // candidates: importing a SIM in Mudita does not transfer ownership of the SIM.
    fun moveExisting(destination: ContactAccount?): ContactsResult = pass(destination)
}
