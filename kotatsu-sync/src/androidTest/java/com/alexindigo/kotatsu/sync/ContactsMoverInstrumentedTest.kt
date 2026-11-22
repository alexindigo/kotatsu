package com.alexindigo.kotatsu.sync

import android.Manifest
import android.accounts.Account
import android.accounts.AccountManager
import android.app.job.JobScheduler
import android.content.ContentProviderOperation
import android.content.ContentUris
import android.content.ContentValues
import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.*
import android.provider.ContactsContract.Contacts
import android.provider.ContactsContract.Data
import android.provider.ContactsContract.Groups
import android.provider.ContactsContract.RawContacts
import android.provider.ContactsContract.Settings
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gezimos.katapult.withFixtureCleanup
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class ContactsMoverInstrumentedTest {
    @Test fun phoneContactMovesAllPortableDataAndKeepsAggregateLookupAndDirtyState() = scenario { f ->
        val source = f.insert("All fields")
        val rows = listOf(
            row(Phone.CONTENT_ITEM_TYPE, "+1 202 555 0101", 2), row(Email.CONTENT_ITEM_TYPE, "example@example.invalid", 1),
            row(StructuredPostal.CONTENT_ITEM_TYPE, "123 Test Street", 1), row(Note.CONTENT_ITEM_TYPE, "Private note"),
            row(Organization.CONTENT_ITEM_TYPE, "Test organisation", 1).apply { put(Organization.TITLE, "Engineer") },
            row(Event.CONTENT_ITEM_TYPE, "2000-01-02", Event.TYPE_BIRTHDAY), row(Website.CONTENT_ITEM_TYPE, "https://example.invalid", 1),
            row("vnd.example/custom", "Opaque", 7).apply { put(Data.DATA15, byteArrayOf(1, 3, 5)); put(Data.DATA10, 42L) },
        )
        for (values in rows) f.resolver.insert(Data.CONTENT_URI, ContentValues(values).apply { put(Data.RAW_CONTACT_ID, source) })!!
        f.resolver.update(ContentUris.withAppendedId(RawContacts.CONTENT_URI, source), ContentValues().apply {
            put(RawContacts.STARRED, 1); put(RawContacts.CUSTOM_RINGTONE, "content://test/ringtone"); put(RawContacts.SEND_TO_VOICEMAIL, 1)
        }, null, null)
        val aggregate = f.aggregate(source)
        val before = f.data(source)
        assertEquals(ContactsResult(1), ContactsMover(f.port).pass(f.personal))
        val target = f.rawIn(f.personal).single()
        assertEquals(before, f.data(target)); assertEquals(aggregate, f.aggregate(target))
        // The account-dependent lookup key is regenerated, but the aggregate id
        // and number lookup used by the dialer/messages consumer stay stable.
        val lookup = Contacts.getLookupUri(f.resolver, ContentUris.withAppendedId(Contacts.CONTENT_URI, aggregate))!!
        assertEquals(ContentUris.withAppendedId(Contacts.CONTENT_URI, aggregate), Contacts.lookupContact(f.resolver, lookup))
        f.resolver.query(ContactsContract.PhoneLookup.CONTENT_FILTER_URI.buildUpon().appendPath("+12025550101").build(),
            arrayOf(ContactsContract.PhoneLookup._ID, ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)!!.use {
            assertTrue(it.moveToFirst()); assertEquals(aggregate, it.getLong(0)); assertEquals("All fields", it.getString(1)); assertEquals(1, it.count)
        }
        assertEquals(1, f.liveAggregateCount()); assertTrue(f.rawIn(null).isEmpty())
        f.resolver.query(ContentUris.withAppendedId(RawContacts.CONTENT_URI, target), arrayOf(RawContacts.DIRTY, RawContacts.SOURCE_ID,
            RawContacts.STARRED, RawContacts.CUSTOM_RINGTONE, RawContacts.SEND_TO_VOICEMAIL), null, null, null)!!.use {
            assertTrue(it.moveToFirst()); assertEquals(1, it.getInt(0)); assertTrue(it.isNull(1)); assertEquals(1, it.getInt(2))
            assertEquals("content://test/ringtone", it.getString(3)); assertEquals(1, it.getInt(4))
        }
        assertEquals(ContactsResult(), ContactsMover(f.port).pass(f.personal))
    }

    @Test fun oneTimeMovesSeveralPhoneAndImportedSimContactsIntoFamilyOnly() = scenario { f ->
        // Mudita SimContact.appendCreateContactOperations emits an ordinary local
        // name/phone raw contact, without an ICC record id or provenance flag.
        f.insert("Imported SIM contact", "2025550102"); f.insert("Phone contact", "2025550103"); f.insert("Another", "2025550104")
        val existing = f.insert("Already personal", "2025550105", f.personal)
        assertEquals(ContactsResult(3), ContactsMover(f.port).moveExisting(f.family))
        assertEquals(3, f.rawIn(f.family).size); assertEquals(listOf(existing), f.rawIn(f.personal))
        assertTrue(f.rawIn(null).isEmpty()); assertEquals(4, f.liveAggregateCount())
        assertEquals(ContactsResult(), ContactsMover(f.port).moveExisting(f.family))
    }

    @Test fun importedSimMoveNeverReadsOrMutatesTheSimProviders() = scenario { f ->
        f.insert("Imported SIM", "2025550123")
        val authorities = mutableListOf<String?>()
        val tracked = ContentResolver.wrap(object : ContentProvider() {
            override fun onCreate() = true
            override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): android.database.Cursor? {
                authorities.add(uri.authority)
                return f.resolver.query(uri, projection, selection, selectionArgs, sortOrder)
            }
            override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
                authorities.add(ContactsContract.AUTHORITY)
                return f.resolver.call(ContactsContract.AUTHORITY_URI, method, arg, extras)
            }
            override fun applyBatch(operations: ArrayList<ContentProviderOperation>): Array<android.content.ContentProviderResult> {
                authorities.addAll(operations.map { it.uri.authority })
                return f.resolver.applyBatch(ContactsContract.AUTHORITY, operations)
            }
            override fun getType(uri: Uri) = null
            override fun insert(uri: Uri, values: ContentValues?) = throw AssertionError("Expected atomic batch")
            override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = throw AssertionError("Expected atomic batch")
            override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = throw AssertionError("No SIM deletion")
        })
        val context = object : ContextWrapper(f.context) { override fun getContentResolver() = tracked }
        assertEquals(ContactsResult(1), ContactsMover(AndroidContacts(context)).moveExisting(f.family))
        assertTrue(authorities.isNotEmpty()); assertEquals(setOf(ContactsContract.AUTHORITY), authorities.toSet())
        assertEquals(1, f.rawIn(f.family).size); assertTrue(f.rawIn(null).isEmpty())
    }

    @Test fun failedVersionAssertLeavesSourceAndTargetUnchangedThenRetries() = scenario { f ->
        val id = f.insert("Concurrent edit")
        val source = f.port.sources().single { it.id == id }
        f.resolver.insert(Data.CONTENT_URI, row(Note.CONTENT_ITEM_TYPE, "Newer data").apply { put(Data.RAW_CONTACT_ID, id) })!!
        val before = f.data(id); val aggregate = f.aggregate(id)
        assertFalse(f.port.move(source, f.personal)); assertEquals(before, f.data(id))
        assertTrue(f.rawIn(f.personal).isEmpty()); assertEquals(aggregate, f.aggregate(id))
        assertEquals(ContactsResult(1), ContactsMover(f.port).pass(f.personal))
        assertEquals(before, f.data(f.rawIn(f.personal).single()))
    }

    @Test fun failingLateOperationRollsBackInsertedRawDataAndGroup() = scenario { f ->
        val source = f.insert("Atomic")
        val before = f.data(source)
        val operations = arrayListOf(
            ContentProviderOperation.newInsert(RawContacts.CONTENT_URI).withValue(RawContacts.ACCOUNT_NAME, f.personal.name)
                .withValue(RawContacts.ACCOUNT_TYPE, f.personal.type).build(),
            ContentProviderOperation.newInsert(Data.CONTENT_URI).withValueBackReference(Data.RAW_CONTACT_ID, 0)
                .withValues(row(Note.CONTENT_ITEM_TYPE, "Temporary")).build(),
            ContentProviderOperation.newDelete(ContentUris.withAppendedId(RawContacts.CONTENT_URI, Long.MAX_VALUE)).withExpectedCount(1).build(),
        )
        try { f.resolver.applyBatch(ContactsContract.AUTHORITY, operations); fail("Expected rollback") }
        catch (_: android.content.OperationApplicationException) { }
        assertEquals(before, f.data(source)); assertTrue(f.rawIn(f.personal).isEmpty())
    }

    @Test fun namelessContactAndExistingJoinedContactKeepTheirAggregateIds() = scenario { f ->
        val nameless = f.insert(null, "2025550106")
        val aggregate = f.aggregate(nameless)
        assertEquals(ContactsResult(1), ContactsMover(f.port).pass(f.personal))
        assertEquals(aggregate, f.aggregate(f.rawIn(f.personal).single()))
        val other = f.insert("Joined", "2025550107", f.family)
        val phone = f.insert("Joined", "2025550107")
        val joined = f.aggregate(phone); assertEquals(joined, f.aggregate(other))
        assertEquals(ContactsResult(1), ContactsMover(f.port).pass(f.personal))
        assertEquals(joined, f.aggregate(other)); assertEquals(joined, f.aggregate(f.rawIn(f.personal).last()))
        assertEquals(2, f.liveAggregateCount())
    }

    @Test fun photoAndAccountOwnedGroupSurviveMove() = scenario { f ->
        val source = f.insert("Photo and group")
        val image = Bitmap.createBitmap(800, 800, Bitmap.Config.ARGB_8888)
        val bytes = ByteArrayOutputStream().use { out -> image.compress(Bitmap.CompressFormat.PNG, 100, out); out.toByteArray() }
        image.recycle()
        f.resolver.insert(Data.CONTENT_URI, ContentValues().apply {
            put(Data.RAW_CONTACT_ID, source); put(Data.MIMETYPE, Photo.CONTENT_ITEM_TYPE); put(Photo.PHOTO, bytes)
        })!!
        val groupId = ContentUris.parseId(f.resolver.insert(Groups.CONTENT_URI, ContentValues().apply {
            put(Groups.TITLE, "Test category"); put(Groups.NOTES, "Details"); put(Groups.GROUP_VISIBLE, 1)
        })!!)
        f.resolver.insert(Data.CONTENT_URI, ContentValues().apply {
            put(Data.RAW_CONTACT_ID, source); put(Data.MIMETYPE, GroupMembership.CONTENT_ITEM_TYPE); put(GroupMembership.GROUP_ROW_ID, groupId)
        })!!
        val beforePhoto = f.displayPhoto(source)
        assertEquals(1, f.visibility(source))
        assertEquals(ContactsResult(1), ContactsMover(f.port).pass(f.personal))
        val target = f.rawIn(f.personal).single()
        assertEquals(1, f.visibility(target))
        assertArrayEquals(beforePhoto, f.displayPhoto(target))
        f.resolver.query(Data.CONTENT_URI, arrayOf(GroupMembership.GROUP_ROW_ID), "${Data.RAW_CONTACT_ID} = ? AND ${Data.MIMETYPE} = ?",
            arrayOf(target.toString(), GroupMembership.CONTENT_ITEM_TYPE), null)!!.use {
            assertTrue(it.moveToFirst()); val newGroup = it.getLong(0); assertNotEquals(groupId, newGroup)
            f.resolver.query(ContentUris.withAppendedId(Groups.CONTENT_URI, newGroup), arrayOf(Groups.ACCOUNT_NAME, Groups.TITLE, Groups.NOTES, Groups.GROUP_VISIBLE), null, null, null)!!.use { g ->
                assertTrue(g.moveToFirst()); assertEquals(f.personal.name, g.getString(0)); assertEquals("Test category", g.getString(1)); assertEquals("Details", g.getString(2))
                assertEquals(1, g.getInt(3))
            }
        }
    }

    @Test fun rawContactChangeJobMovesToConfiguredPersonalAndRearms() = scenario { f ->
        f.config.write(ContactsSettings(personal = f.personal, family = f.family))
        SyncRuntime.ensureArmed(f.context, queuePass = false)
        f.insert("Triggered contact")
        f.await { f.rawIn(f.personal).size == 1 }
        assertTrue(f.rawIn(null).isEmpty())
        f.await { f.context.getSystemService(JobScheduler::class.java).getPendingJob(SyncRuntime.CONTACTS_JOB) != null }
    }

    @Test fun oneTimeRuntimeActionUsesFamilyAndUnavailableTargetDoesNotDeletePhoneData() = scenario { f ->
        val id = f.insert("One-time")
        f.config.write(ContactsSettings(family = f.family))
        assertEquals(ContactsResult(1), SyncRuntime.moveExistingContacts(f.context).get(15, TimeUnit.SECONDS))
        assertEquals(1, f.rawIn(f.family).size)
        val remaining = f.insert("Keep")
        f.resolver.update(Settings.CONTENT_URI, ContentValues().apply { put(Settings.SHOULD_SYNC, 0) }, "account_name = ?", arrayOf(f.family.name))
        assertNotNull(ContactsMover(f.port).moveExisting(f.family).stopReason)
        assertEquals(listOf(remaining), f.rawIn(null)); assertFalse(f.rawIn(null).contains(id))
    }

    private fun row(mime: String, value: String, type: Int? = null) = ContentValues().apply {
        put(Data.MIMETYPE, mime); put(Data.DATA1, value); if (type != null) put(Data.DATA2, type)
    }
    private fun scenario(body: (Fixture) -> Unit) {
        val f = Fixture()
        withFixtureCleanup(
            { f.context.getSystemService(JobScheduler::class.java).cancelAll(); SyncRuntime.enqueue {}.get(15, TimeUnit.SECONDS) },
            { f.config.write(f.previous) },
            { for (id in f.ownedRawIds + f.rawIn(f.personal) + f.rawIn(f.family)) f.resolver.delete(ContentUris.withAppendedId(RawContacts.CONTENT_URI, id), null, null) },
            { for (account in listOf(f.personal, f.family)) {
                f.resolver.delete(Settings.CONTENT_URI, "account_name = ?", arrayOf(account.name))
                f.resolver.delete(Groups.CONTENT_URI, "account_name = ?", arrayOf(account.name))
            } },
            { f.resolver.delete(Groups.CONTENT_URI, "${Groups.TITLE} = ? AND ${Groups.ACCOUNT_NAME} IS NULL", arrayOf("Test category")) },
            { for (account in f.added) check(AccountManager.get(f.context).removeAccountExplicitly(account)) },
        ) { f.setup(); body(f) }
    }
    private class Fixture {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val resolver = context.contentResolver
        val config = PreferenceContactsConfig(context)
        val previous = config.read()
        val personal = ContactAccount("Personal-${UUID.randomUUID()}", "com.alexindigo.kotatsu.sync.test")
        val family = ContactAccount("Family-${UUID.randomUUID()}", "com.alexindigo.kotatsu.sync.test")
        val added = mutableListOf<Account>()
        val ownedRawIds = mutableListOf<Long>()
        val port = AndroidContacts(context)
        fun setup() {
            context.getSystemService(JobScheduler::class.java).cancelAll(); SyncRuntime.enqueue {}.get(15, TimeUnit.SECONDS)
            for (p in listOf(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS, Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR))
                instrumentation.uiAutomation.grantRuntimePermission(context.packageName, p)
            config.write(ContactsSettings())
            for (account in listOf(personal, family)) {
                val a = Account(account.name, account.type); check(AccountManager.get(context).addAccountExplicitly(a, null, null)); added.add(a)
                resolver.insert(Settings.CONTENT_URI, ContentValues().apply {
                    put(Settings.ACCOUNT_NAME, a.name); put(Settings.ACCOUNT_TYPE, a.type); put(Settings.SHOULD_SYNC, 1); put(Settings.UNGROUPED_VISIBLE, 1)
                })!!
            }
            check(personal in port.addressBooks() && family in port.addressBooks())
        }
        fun insert(name: String?, number: String? = null, account: ContactAccount? = null): Long {
            val id = ContentUris.parseId(resolver.insert(RawContacts.CONTENT_URI, ContentValues().apply {
                put(RawContacts.ACCOUNT_NAME, account?.name); put(RawContacts.ACCOUNT_TYPE, account?.type)
            })!!); ownedRawIds.add(id)
            if (name != null) resolver.insert(Data.CONTENT_URI, ContentValues().apply {
                put(Data.RAW_CONTACT_ID, id); put(Data.MIMETYPE, StructuredName.CONTENT_ITEM_TYPE); put(StructuredName.DISPLAY_NAME, name)
            })!!
            if (number != null) resolver.insert(Data.CONTENT_URI, ContentValues().apply {
                put(Data.RAW_CONTACT_ID, id); put(Data.MIMETYPE, Phone.CONTENT_ITEM_TYPE); put(Phone.NUMBER, number); put(Phone.TYPE, Phone.TYPE_MOBILE)
            })!!
            return id
        }
        fun rawIn(account: ContactAccount?): List<Long> = resolver.query(RawContacts.CONTENT_URI, arrayOf(RawContacts._ID),
            "${RawContacts.DELETED} = 0 AND " + if (account == null) "account_name IS NULL AND account_type IS NULL" else "account_name = ? AND account_type = ?",
            account?.let { arrayOf(it.name, it.type) }, RawContacts._ID)!!.use { c -> buildList { while (c.moveToNext()) add(c.getLong(0)) } }
        fun aggregate(rawId: Long): Long = resolver.query(ContentUris.withAppendedId(RawContacts.CONTENT_URI, rawId),
            arrayOf(RawContacts.CONTACT_ID), null, null, null)!!.use { check(it.moveToFirst()); it.getLong(0) }
        fun liveAggregateCount() = resolver.query(Contacts.CONTENT_URI, arrayOf(Contacts._ID), null, null, null)!!.use { it.count }
        fun visibility(raw: Long): Int = resolver.query(ContentUris.withAppendedId(Contacts.CONTENT_URI, aggregate(raw)),
            arrayOf(Contacts.IN_VISIBLE_GROUP), null, null, null)!!.use { check(it.moveToFirst()); it.getInt(0) }
        fun displayPhoto(id: Long): ByteArray = resolver.openInputStream(ContentUris.withAppendedId(RawContacts.CONTENT_URI, id)
            .buildUpon().appendPath(RawContacts.DisplayPhoto.CONTENT_DIRECTORY).build())!!.use { it.readBytes() }
        fun data(id: Long): List<List<String?>> = resolver.query(Data.CONTENT_URI,
            (listOf(Data.MIMETYPE, Data.IS_PRIMARY, Data.IS_SUPER_PRIMARY) + (1..15).map { "data$it" }).toTypedArray(),
            "${Data.RAW_CONTACT_ID} = ?", arrayOf(id.toString()), Data._ID)!!.use { c -> buildList {
                while (c.moveToNext()) add((0 until c.columnCount).map { i ->
                    if (c.isNull(i)) null else if (c.getType(i) == android.database.Cursor.FIELD_TYPE_BLOB) c.getBlob(i).joinToString(",") else c.getString(i)
                })
            } }
        fun await(predicate: () -> Boolean) {
            val deadline = SystemClock.uptimeMillis() + 45000
            while (!predicate()) { check(SystemClock.uptimeMillis() < deadline) { "Contacts consumer did not converge" }; SystemClock.sleep(100) }
        }
    }
}
