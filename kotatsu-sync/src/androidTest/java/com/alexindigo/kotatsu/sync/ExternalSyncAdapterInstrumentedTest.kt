package com.alexindigo.kotatsu.sync

import android.Manifest
import android.app.job.JobScheduler
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Intent
import android.os.ParcelFileDescriptor
import android.provider.CalendarContract
import android.provider.CalendarContract.Calendars
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.StructuredName
import android.provider.ContactsContract.Data
import android.provider.ContactsContract.RawContacts
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gezimos.katapult.withFixtureCleanup
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class ExternalSyncAdapterInstrumentedTest {
    @Test fun externalContactsAndCalendarSyncAdaptersAndTheirMetadataAreVisible() = scenario { f ->
        val adapters = ContentResolver.getSyncAdapterTypes().filter { it.accountType == TYPE }
        assertEquals("Both external adapters must survive package-visibility filtering",
            setOf(ContactsContract.AUTHORITY, CalendarContract.AUTHORITY), adapters.map { it.authority }.toSet())
        assertTrue(adapters.all { it.supportsUploading() && it.isUserVisible })
        val services = f.context.packageManager.queryIntentServices(Intent("android.content.SyncAdapter"), 0)
            .filter { it.serviceInfo.packageName == PACKAGE }
        assertEquals(2, services.size)
        assertTrue(android.accounts.AccountManager.get(f.context).authenticatorTypes.any { it.type == TYPE })
        assertTrue(AndroidContacts(f.context).addressBooks().contains(f.account))
    }

    @Test fun externalAccountCalendarRoundTripsAndPhoneContactMovesToItsAddressBook() = scenario { f ->
        // Calendar selection reads account fields from CalendarContract, not
        // AccountManager or adapter metadata: prove this independently first.
        val standard = StandardCalendar(f.resolver)
        assertTrue(standard.calendars().single { it.id == f.calendarId }.eligible)
        val event = standard.events(setOf(f.calendarId)).single()
        assertEquals("External calendar event", event.title)
        assertTrue(standard.update(event.key, event.copy(title = "Phone calendar edit")))
        assertEquals("Phone calendar edit", standard.read(event.key)!!.title)
        f.localId = ContentUris.parseId(f.resolver.insert(RawContacts.CONTENT_URI, ContentValues())!!)
        f.resolver.insert(Data.CONTENT_URI, ContentValues().apply {
            put(Data.RAW_CONTACT_ID, f.localId); put(Data.MIMETYPE, StructuredName.CONTENT_ITEM_TYPE)
            put(StructuredName.DISPLAY_NAME, "External destination contact")
        })!!
        f.unrelatedId = ContentUris.parseId(f.resolver.insert(RawContacts.CONTENT_URI, ContentValues())!!)
        f.resolver.insert(Data.CONTENT_URI, ContentValues().apply {
            put(Data.RAW_CONTACT_ID, f.unrelatedId); put(Data.MIMETYPE, StructuredName.CONTENT_ITEM_TYPE)
            put(StructuredName.DISPLAY_NAME, "Unrelated local contact")
        })!!
        val unrelated = AndroidContacts(f.context).sources().single { it.id == f.unrelatedId }
        assertEquals(ContactsResult(1), ContactsMover(AndroidContacts(f.context)).pass(f.account, setOf(f.localId)))
        assertEquals(unrelated, AndroidContacts(f.context).sources().single { it.id == f.unrelatedId })
        f.resolver.query(RawContacts.CONTENT_URI, arrayOf(RawContacts._ID, RawContacts.DIRTY),
            "account_name = ? AND account_type = ? AND deleted = 0", arrayOf(f.account.name, TYPE), null)!!.use {
            assertTrue(it.moveToFirst()); assertEquals(1, it.count); assertEquals(1, it.getInt(1))
            f.destinationId = it.getLong(0)
        }
        f.resolver.query(ContentUris.withAppendedId(RawContacts.CONTENT_URI, f.localId), arrayOf(RawContacts._ID), null, null, null)!!.use {
            assertEquals(0, it.count)
        }
    }

    @Test fun automaticMoveWaitsForThePreferredDavx5BookToPublishSettings() = scenario { f ->
        requireIsolatedPhoneBook(f.context)
        val personal = ContactAccount("kotatsu-external-test-Personal-${UUID.randomUUID()}", TYPE)
        f.register(personal)
        val port = AndroidContacts(f.context)
        assertTrue(android.accounts.AccountManager.get(f.context).getAccountsByType(TYPE).any { it.name == personal.name })
        assertEquals(setOf(f.account), port.addressBooks())
        f.localId = ContentUris.parseId(f.resolver.insert(RawContacts.CONTENT_URI, ContentValues())!!)
        val store = object : ContactsMoveStore { override var pendingContactsMove: ContactsMove? = null }
        val engine = ContactsTransferEngine(port, store)
        val before = engine.pass(ContactsSettings())
        assertEquals("A registered Personal book has not acknowledged eligibility; do not move to Family", 0, before.changes)
        assertEquals(setOf(SyncStopReason.ADDRESS_BOOK_REQUIRED), before.stopReasons)
        assertTrue(phoneSourceIds(f.context).contains(f.localId))

        f.publishBook(personal)
        assertEquals(ContactsResult(1), engine.pass(ContactsSettings()))
        assertTrue(phoneSourceIds(f.context).isEmpty())
        assertEquals(listOf(personal), port.sources().map { it.account })
    }

    private fun scenario(body: (Fixture) -> Unit) {
        val f = Fixture()
        withFixtureCleanup(
            { f.context.getSystemService(JobScheduler::class.java).cancelAll(); SyncRuntime.enqueue {}.get(15, TimeUnit.SECONDS) },
            { for (id in listOf(f.localId, f.unrelatedId, f.destinationId).filter { it >= 0 }) f.resolver.delete(ContentUris.withAppendedId(RawContacts.CONTENT_URI, id), null, null) },
            { f.cleanupAccounts() },
        ) { f.setup(); body(f) }
    }
    private class Fixture {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val resolver = context.contentResolver
        val account = ContactAccount("kotatsu-external-test-Family-${UUID.randomUUID()}", TYPE)
        private val registered = mutableListOf<ContactAccount>()
        var localId = -1L
        var unrelatedId = -1L
        var destinationId = -1L
        var calendarId = -1L
        fun setup() {
            check(android.os.Build.HARDWARE == "ranchu") { "External DAVx5 fixture requires a disposable AVD" }
            context.getSystemService(JobScheduler::class.java).cancelAll(); SyncRuntime.enqueue {}.get(15, TimeUnit.SECONDS)
            for (permission in listOf(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS,
                Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)) {
                instrumentation.uiAutomation.grantRuntimePermission(context.packageName, permission)
                instrumentation.uiAutomation.grantRuntimePermission(PACKAGE, permission)
            }
            // Shell, not the target uid, talks to the fixture. Never grant
            // implicit visibility by binding or sending it a target-uid intent.
            register(account)
            publishBook(account)
            command("seed-calendar")
            calendarId = resolver.query(Calendars.CONTENT_URI, arrayOf(Calendars._ID),
                "account_name = ? AND account_type = ?", arrayOf(account.name, TYPE), null)!!.use {
                check(it.moveToFirst()); it.getLong(0)
            }
        }
        fun register(book: ContactAccount) {
            command("register", book)
            registered += book
        }
        fun cleanupAccounts() = withFixtureCleanup(*registered.map { book -> { command("cleanup", book) } }.toTypedArray()) {}
        fun publishBook(book: ContactAccount) {
            instrumentation.awaitContactsProviderAccounts(listOf(book))
            command("publish-book", book)
        }
        fun command(operation: String, book: ContactAccount = account) {
            val output = ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(
                "am broadcast --receiver-foreground --include-stopped-packages -n $PACKAGE/com.alexindigo.kotatsu.fakesyncadapter.FixtureReceiver --es operation $operation --es name ${book.name}"
            )).bufferedReader().use { it.readText() }
            check(output.contains("result=0") && output.contains("fixture complete")) { output }
        }
    }
    companion object {
        private const val PACKAGE = "com.alexindigo.kotatsu.fakemuditacalendar.test"
        private const val TYPE = "at.bitfire.davdroid.address_book"
    }
}
