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
        assertEquals(ContactsResult(1), ContactsMover(AndroidContacts(f.context)).pass(f.account))
        f.resolver.query(RawContacts.CONTENT_URI, arrayOf(RawContacts._ID, RawContacts.DIRTY),
            "account_name = ? AND account_type = ? AND deleted = 0", arrayOf(f.account.name, TYPE), null)!!.use {
            assertTrue(it.moveToFirst()); assertEquals(1, it.count); assertEquals(1, it.getInt(1))
            f.destinationId = it.getLong(0)
        }
        f.resolver.query(ContentUris.withAppendedId(RawContacts.CONTENT_URI, f.localId), arrayOf(RawContacts._ID), null, null, null)!!.use {
            assertEquals(0, it.count)
        }
    }

    private fun scenario(body: (Fixture) -> Unit) {
        val f = Fixture()
        withFixtureCleanup(
            { f.context.getSystemService(JobScheduler::class.java).cancelAll(); SyncRuntime.enqueue {}.get(15, TimeUnit.SECONDS) },
            { for (id in listOf(f.localId, f.destinationId).filter { it >= 0 }) f.resolver.delete(ContentUris.withAppendedId(RawContacts.CONTENT_URI, id), null, null) },
            { if (f.seedAttempted) f.command("cleanup") },
        ) { f.setup(); body(f) }
    }
    private class Fixture {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val resolver = context.contentResolver
        val account = ContactAccount("kotatsu-external-test-${UUID.randomUUID()}", TYPE)
        var seedAttempted = false
        var localId = -1L
        var destinationId = -1L
        var calendarId = -1L
        fun setup() {
            context.getSystemService(JobScheduler::class.java).cancelAll(); SyncRuntime.enqueue {}.get(15, TimeUnit.SECONDS)
            for (permission in listOf(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS,
                Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)) {
                instrumentation.uiAutomation.grantRuntimePermission(context.packageName, permission)
                instrumentation.uiAutomation.grantRuntimePermission(PACKAGE, permission)
            }
            // Shell, not the target uid, talks to the fixture. Never grant
            // implicit visibility by binding or sending it a target-uid intent.
            seedAttempted = true; command("seed")
            calendarId = resolver.query(Calendars.CONTENT_URI, arrayOf(Calendars._ID),
                "account_name = ? AND account_type = ?", arrayOf(account.name, TYPE), null)!!.use {
                check(it.moveToFirst()); it.getLong(0)
            }
        }
        fun command(operation: String) {
            val output = ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(
                "am broadcast --receiver-foreground --include-stopped-packages -n $PACKAGE/com.alexindigo.kotatsu.fakesyncadapter.FixtureReceiver --es operation $operation --es name ${account.name}"
            )).bufferedReader().use { it.readText() }
            check(output.contains("result=0") && output.contains("fixture complete")) { output }
        }
    }
    companion object {
        private const val PACKAGE = "com.alexindigo.kotatsu.fakemuditacalendar.test"
        private const val TYPE = "com.alexindigo.kotatsu.fakesyncadapter.account"
    }
}
