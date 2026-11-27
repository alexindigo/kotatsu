package com.alexindigo.kotatsu.sync

data class AddressBookChoice(val account: ContactAccount, val label: String) {
    /** The unsuffixed collection name, never the disambiguated picker text. */
    internal val collectionLabel: String get() = addressBookLabel(account)
}

/** A registered DAVx5 collection with no Settings row is not known absent or writable. */
data class AddressBookCatalog(val available: Set<ContactAccount> = emptySet(),
    internal val unacknowledged: Set<ContactAccount> = emptySet(),
) {
    val choices = addressBookChoices(available)

    internal fun automaticDestination(): AddressBookChoice? {
        val candidates = if (unacknowledged.isEmpty()) choices else addressBookChoices(available + unacknowledged)
        val preferred = preferPersonal(candidates, AddressBookChoice::collectionLabel)
        return choices.singleOrNull { it.account == preferred?.account }
    }
}

internal fun addressBookChoices(accounts: Set<ContactAccount>): List<AddressBookChoice> {
    val parsed = accounts.map { AddressBookChoice(it, addressBookLabel(it)) }
    val counts = parsed.groupingBy { it.label }.eachCount()
    return parsed.map { if (counts.getValue(it.label) > 1) it.copy(label = it.account.name) else it }
        .sortedWith(compareBy<AddressBookChoice, String>(String.CASE_INSENSITIVE_ORDER) { it.label }
            .thenBy { it.account.name }.thenBy { it.account.type }.thenBy { it.account.dataSet.orEmpty() })
}

// Verified DAVx5 4.5.21-ose and the Mudita fork's LocalAddressBookStore format.
internal fun addressBookLabel(account: ContactAccount): String {
    if (account.type !in DAVX5_BOOK_TYPES) return account.name
    val suffix = DAVX5_SUFFIX.find(account.name) ?: return account.name
    val base = account.name.substring(0, suffix.range.first)
    if (!base.endsWith(')')) return account.name
    // Find the terminal balanced parent; the collection name and account may
    // both contain parentheses. Leave unrecognizable decoration intact.
    var depth = 0
    for (i in base.lastIndex downTo 0) {
        if (base[i] == ')') depth++
        if (base[i] == '(' && --depth == 0) {
            return if (i >= 2 && base[i - 1] == ' ' && i < base.lastIndex - 1) base.take(i - 1) else account.name
        }
    }
    return account.name
}

internal val DAVX5_BOOK_TYPES = setOf("at.bitfire.davdroid.address_book", "at.bitfire.davdroid.address_book.mudita")
private val DAVX5_SUFFIX = Regex(" #[0-9]+$")
