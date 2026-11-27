package com.alexindigo.kotatsu.sync

import android.accounts.Account
import android.accounts.AccountManager
import android.app.Instrumentation
import android.content.ContentValues
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.ContactsContract.RawContacts
import android.provider.ContactsContract.Settings

/** AccountManager registration is not a ContactsProvider acknowledgement. Publish Settings only after both. */
internal fun Instrumentation.registerContactBooks(books: List<ContactAccount>, added: MutableList<Account>) {
    val manager = AccountManager.get(targetContext)
    val accounts = books.map { book ->
        Account(book.name, book.type).also { account ->
            check(manager.addAccountExplicitly(account, null, null)) { "Could not register $book" }
            added += account
        }
    }
    awaitContactsProviderAccounts(books)
    for (account in accounts) checkNotNull(targetContext.contentResolver.insert(Settings.CONTENT_URI, ContentValues().apply {
        put(Settings.ACCOUNT_NAME, account.name); put(Settings.ACCOUNT_TYPE, account.type)
        put(Settings.SHOULD_SYNC, 1); put(Settings.UNGROUPED_VISIBLE, 1)
    })) { "Could not publish Settings for $account" }
}

internal fun Instrumentation.awaitContactsProviderAccounts(books: List<ContactAccount>) {
    check(Build.HARDWARE == "ranchu" && Build.TYPE == "userdebug" && Build.VERSION.SDK_INT == 31) {
        "ContactsAccount fixture requires the AOSP API 31 CI AVD"
    }
    val args = books.joinToString(" ") { "${it.name}:${it.type}" }
    check(books.all { it.name.matches(ACCOUNT_TOKEN) && it.type.matches(ACCOUNT_TOKEN) })
    val resolver = targetContext.contentResolver
    // Ensure provider initialization opened the contacts DB before launching the read-only observer.
    resolver.query(RawContacts.CONTENT_URI, arrayOf(RawContacts._ID), "0", null, null)!!.close()
    val output = runFixtureTool("ContactsProviderAccounts", args)
    check(output == "accounts reconciled") { "ContactsProvider acknowledgement failed for $books: $output" }
}

internal const val EXTERNAL_FIXTURE_PACKAGE = "com.alexindigo.kotatsu.fakemuditacalendar.test"

internal fun Instrumentation.runFixtureTool(tool: String, args: String): String {
    fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(uiAutomation.executeShellCommand(command))
        .bufferedReader().use { it.readText().trim() }
    // Use shell for APK resolution and setup, not the Sync UID (would mask package visibility).
    val apk = shell("pm path $EXTERNAL_FIXTURE_PACKAGE").removePrefix("package:")
    check(apk.matches(Regex("/data/app/[A-Za-z0-9./_~=-]+/base\\.apk"))) { "Unexpected fixture APK path: $apk" }
    return shell("su 0 timeout 20 env CLASSPATH=$apk app_process /system/bin com.alexindigo.kotatsu.fakesyncadapter.$tool $args")
}

private val ACCOUNT_TOKEN = Regex("[A-Za-z0-9._-]+")
