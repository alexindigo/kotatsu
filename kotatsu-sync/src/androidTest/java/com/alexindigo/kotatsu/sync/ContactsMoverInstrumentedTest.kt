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
import android.graphics.BitmapFactory
import android.graphics.Color
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
            row(Phone.CONTENT_ITEM_TYPE, f.number("all-fields"), 2), row(Email.CONTENT_ITEM_TYPE, f.email("all-fields"), 1),
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
        assertEquals(ContactsResult(1), f.pass(f.personal))
        val target = f.rawIn(f.personal).single()
        assertEquals(before, f.data(target)); assertEquals(aggregate, f.aggregate(target))
        // The account-dependent lookup key is regenerated, but the aggregate id
        // and number lookup used by the dialer/messages consumer stay stable.
        val lookup = Contacts.getLookupUri(f.resolver, ContentUris.withAppendedId(Contacts.CONTENT_URI, aggregate))!!
        assertEquals(ContentUris.withAppendedId(Contacts.CONTENT_URI, aggregate), Contacts.lookupContact(f.resolver, lookup))
        f.resolver.query(ContactsContract.PhoneLookup.CONTENT_FILTER_URI.buildUpon().appendPath(f.number("all-fields")).build(),
            arrayOf(ContactsContract.PhoneLookup._ID, ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)!!.use { c ->
            val matches = buildList { while (c.moveToNext()) add(c.getLong(0) to c.getString(1)) }
            assertEquals(f.name("All fields"), matches.single { it.first == aggregate }.second)
        }
        assertEquals(f.baselineAggregates + 1, f.liveAggregateCount()); assertEquals(f.baselinePhone, f.rawIn(null).toSet())
        f.resolver.query(ContentUris.withAppendedId(RawContacts.CONTENT_URI, target), arrayOf(RawContacts.DIRTY, RawContacts.SOURCE_ID,
            RawContacts.STARRED, RawContacts.CUSTOM_RINGTONE, RawContacts.SEND_TO_VOICEMAIL), null, null, null)!!.use {
            assertTrue(it.moveToFirst()); assertEquals(1, it.getInt(0)); assertTrue(it.isNull(1)); assertEquals(1, it.getInt(2))
            assertEquals("content://test/ringtone", it.getString(3)); assertEquals(1, it.getInt(4))
        }
        assertEquals(ContactsResult(), f.pass(f.personal))
    }

    @Test fun oneTimeMovesSeveralPhoneAndImportedSimContactsIntoFamilyOnly() = scenario { f ->
        // Mudita SimContact.appendCreateContactOperations emits an ordinary local
        // name/phone raw contact, without an ICC record id or provenance flag.
        f.insert("Imported SIM contact", "2025550102"); f.insert("Phone contact", "2025550103"); f.insert("Another", "2025550104")
        val existing = f.insert("Already personal", "2025550105", f.personal)
        assertEquals(ContactsResult(3), ContactsMover(f.port).moveExisting(f.family, f.ownedRawIds.toSet()))
        assertEquals(3, f.rawIn(f.family).size); assertEquals(listOf(existing), f.rawIn(f.personal))
        assertEquals(f.baselinePhone, f.rawIn(null).toSet()); assertEquals(f.baselineAggregates + 4, f.liveAggregateCount())
        assertEquals(ContactsResult(), ContactsMover(f.port).moveExisting(f.family, f.ownedRawIds.toSet()))
    }

    @Test fun importedSimMoveNeverReadsOrMutatesTheSimProviders() = scenario { f ->
        f.insert("Imported SIM", "2025550123")
        val authorities = mutableListOf<String?>()
        val context = f.trackedContext(onAuthority = { authorities.add(it) })
        assertEquals(ContactsResult(1), ContactsMover(AndroidContacts(context)).moveExisting(f.family, f.ownedRawIds.toSet()))
        assertTrue(authorities.isNotEmpty()); assertEquals(setOf(ContactsContract.AUTHORITY), authorities.toSet())
        assertEquals(1, f.rawIn(f.family).size); assertEquals(f.baselinePhone, f.rawIn(null).toSet())
    }

    @Test fun failedVersionAssertLeavesSourceAndTargetUnchangedThenRetries() = scenario { f ->
        val id = f.insert("Concurrent edit")
        val source = f.port.sources().single { it.id == id }
        f.resolver.insert(Data.CONTENT_URI, row(Note.CONTENT_ITEM_TYPE, "Newer data").apply { put(Data.RAW_CONTACT_ID, id) })!!
        val before = f.data(id); val aggregate = f.aggregate(id)
        assertFalse(f.port.move(source, f.personal)); assertEquals(before, f.data(id))
        assertTrue(f.rawIn(f.personal).isEmpty()); assertEquals(aggregate, f.aggregate(id))
        assertEquals(ContactsResult(1), f.pass(f.personal))
        assertEquals(before, f.data(f.rawIn(f.personal).single()))
    }

    @Test fun failingLateOperationRollsBackInsertedRawDataAndGroup() = scenario { f ->
        val source = f.insert("Atomic", "2025550191")
        f.resolver.update(Data.CONTENT_URI, ContentValues().apply { put(Data.IS_SUPER_PRIMARY, 1) },
            "${Data.RAW_CONTACT_ID} = ? AND ${Data.MIMETYPE} = ?", arrayOf(source.toString(), Phone.CONTENT_ITEM_TYPE))
        val image = Bitmap.createBitmap(800, 800, Bitmap.Config.ARGB_8888)
        val bytes = withFixtureCleanup({ image.recycle() }) {
            image.eraseColor(Color.RED)
            ByteArrayOutputStream().use { out -> image.compress(Bitmap.CompressFormat.PNG, 100, out); out.toByteArray() }
        }
        f.resolver.insert(Data.CONTENT_URI, ContentValues().apply {
            put(Data.RAW_CONTACT_ID, source); put(Data.MIMETYPE, Photo.CONTENT_ITEM_TYPE); put(Photo.PHOTO, bytes)
            put(Data.IS_SUPER_PRIMARY, 1)
        })!!
        val group = ContentUris.parseId(f.resolver.insert(Groups.CONTENT_URI, ContentValues().apply {
            put(Groups.TITLE, "Test category"); put(Groups.NOTES, "Rollback group"); put(Groups.GROUP_VISIBLE, 1)
        })!!)
        f.ownedGroupIds += group
        f.resolver.insert(Data.CONTENT_URI, ContentValues().apply {
            put(Data.RAW_CONTACT_ID, source); put(Data.MIMETYPE, GroupMembership.CONTENT_ITEM_TYPE); put(GroupMembership.GROUP_ROW_ID, group)
        })!!
        val contact = f.port.sources().single { it.id == source }
        val aggregate = f.aggregate(source); val before = f.data(source)
        val photos = f.photos(source); val fullPhoto = f.displayPhoto(source)
        assertEquals(2, before.count { it[2] == "1" }); assertEquals(1, photos.size)
        val tables = listOf(RawContacts.CONTENT_URI, Data.CONTENT_URI, Groups.CONTENT_URI)
        val ids = tables.associateWith(f::ids)
        var failedAtEnd = false
        val context = f.trackedContext(batch = { operations ->
            // The real move must include group creation and end with both
            // phone/photo defaults restored after deleting the source.
            assertTrue(operations.any { it.isInsert && it.uri == Groups.CONTENT_URI })
            assertTrue(operations.takeLast(2).all { it.isUpdate && it.uri == Data.CONTENT_URI })
            operations.add(ContentProviderOperation.newAssertQuery(RawContacts.CONTENT_URI)
                .withExpectedCount(Int.MAX_VALUE).build())
            try { f.resolver.applyBatch(ContactsContract.AUTHORITY, operations) }
            catch (error: android.content.OperationApplicationException) {
                assertTrue("Must reach the appended failure: $error", error.message.orEmpty().contains(Int.MAX_VALUE.toString()))
                failedAtEnd = true
                throw error
            }
        })
        assertFalse(AndroidContacts(context).move(contact, f.personal)); assertTrue(failedAtEnd)
        assertEquals(contact, f.port.sources().single { it.id == source }); assertEquals(aggregate, f.aggregate(source))
        assertEquals(before, f.data(source)); assertEquals(photos, f.photos(source)); assertArrayEquals(fullPhoto, f.displayPhoto(source))
        assertEquals(ids, tables.associateWith(f::ids)); assertTrue(f.rawIn(f.personal).isEmpty())
    }

    @Test fun namelessContactAndExistingJoinedContactKeepTheirAggregateIds() = scenario { f ->
        val nameless = f.insert(null, "2025550106")
        val aggregate = f.aggregate(nameless)
        assertEquals(ContactsResult(1), f.pass(f.personal))
        assertEquals(aggregate, f.aggregate(f.rawIn(f.personal).single()))
        val other = f.insert("Joined", "2025550107", f.family)
        val phone = f.insert("Joined", "2025550107")
        val joined = f.aggregate(phone); assertEquals(joined, f.aggregate(other))
        assertEquals(ContactsResult(1), f.pass(f.personal))
        assertEquals(joined, f.aggregate(other)); assertEquals(joined, f.aggregate(f.rawIn(f.personal).last()))
        assertEquals(f.baselineAggregates + 2, f.liveAggregateCount())
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
        f.ownedGroupIds += groupId
        f.resolver.insert(Data.CONTENT_URI, ContentValues().apply {
            put(Data.RAW_CONTACT_ID, source); put(Data.MIMETYPE, GroupMembership.CONTENT_ITEM_TYPE); put(GroupMembership.GROUP_ROW_ID, groupId)
        })!!
        val beforePhoto = f.displayPhoto(source)
        assertEquals(1, f.visibility(source))
        assertEquals(ContactsResult(1), f.pass(f.personal))
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

    @Test fun multiplePhotosKeepTheirOwnFullSizeImagesAndPrimaryFlags() = scenario { f ->
        val source = f.insert("Two distinct photos")
        val photoIds = listOf(Color.RED, Color.BLUE).mapIndexed { index, color ->
            val image = Bitmap.createBitmap(800, 800, Bitmap.Config.ARGB_8888)
            image.eraseColor(color)
            val bytes = ByteArrayOutputStream().use { out -> image.compress(Bitmap.CompressFormat.PNG, 100, out); out.toByteArray() }
            image.recycle()
            ContentUris.parseId(f.resolver.insert(Data.CONTENT_URI, ContentValues().apply {
                put(Data.RAW_CONTACT_ID, source); put(Data.MIMETYPE, Photo.CONTENT_ITEM_TYPE); put(Photo.PHOTO, bytes)
                put(Data.IS_PRIMARY, if (index == 0) 1 else 0); put(Data.IS_SUPER_PRIMARY, if (index == 0) 1 else 0)
            })!!)
        }
        f.resolver.update(ContentUris.withAppendedId(Data.CONTENT_URI, photoIds.first()), ContentValues().apply {
            put(Data.IS_PRIMARY, 1); put(Data.IS_SUPER_PRIMARY, 1)
        }, null, null)
        val before = f.photos(source)
        assertEquals(setOf("red", "blue"), before.map { it.image }.toSet())
        assertEquals(2, before.size); assertEquals(1, before.count { it.primary == 1 && it.superPrimary == 1 })
        assertEquals(ContactsResult(1), f.pass(f.personal))
        val target = f.rawIn(f.personal).single()
        assertEquals(before.sortedBy { it.image }, f.photos(target).sortedBy { it.image })
        assertEquals(f.baselinePhone, f.rawIn(null).toSet()); assertEquals(f.baselineAggregates + 1, f.liveAggregateCount())
    }

    @Test fun phoneAndEmailDefaultsSurviveTheCopyAggregateDeleteTransaction() = scenario { f ->
        val source = f.insert("Selected fields")
        for ((mime, values) in listOf(Phone.CONTENT_ITEM_TYPE to listOf("2025550181", "2025550182"),
            Email.CONTENT_ITEM_TYPE to listOf("chosen@example.invalid", "other@example.invalid"))) {
            for ((index, value) in values.withIndex()) f.resolver.insert(Data.CONTENT_URI,
                row(mime, if (mime == Phone.CONTENT_ITEM_TYPE) f.number(value) else f.email(value), 1).apply {
                put(Data.RAW_CONTACT_ID, source); put(Data.IS_PRIMARY, if (index == 0) 1 else 0)
                put(Data.IS_SUPER_PRIMARY, if (index == 0) 1 else 0)
            })!!
        }
        val before = f.data(source)
        assertEquals(2, before.count { it[2] == "1" })
        assertEquals(ContactsResult(1), f.pass(f.personal))
        assertEquals(before, f.data(f.rawIn(f.personal).single()))
    }

    @Test fun movingJoinedRawContactDoesNotReplaceAnotherRawContactsDefault() = scenario { f ->
        val other = f.insert("Joined default", "2025550183", f.family)
        val source = f.insert("Joined default", "2025550183")
        assertEquals(f.aggregate(other), f.aggregate(source))
        f.resolver.update(Data.CONTENT_URI, ContentValues().apply { put(Data.IS_SUPER_PRIMARY, 1) },
            "${Data.RAW_CONTACT_ID} = ? AND ${Data.MIMETYPE} = ?", arrayOf(other.toString(), Phone.CONTENT_ITEM_TYPE))
        val beforeOther = f.data(other); val beforeSource = f.data(source)
        assertEquals(1, beforeOther.count { it[2] == "1" }); assertEquals(0, beforeSource.count { it[2] == "1" })
        assertEquals(ContactsResult(1), f.pass(f.personal))
        assertEquals(beforeOther, f.data(other)); assertEquals(beforeSource, f.data(f.rawIn(f.personal).single()))
        assertEquals(f.aggregate(other), f.aggregate(f.rawIn(f.personal).single()))
    }

    @Test fun rawContactChangeJobMovesToConfiguredPersonalAndRearms() = scenario { f ->
        requireIsolatedPhoneBook(f.context)
        f.config.write(ContactsSettings(f.personal))
        SyncRuntime.ensureArmed(f.context, queuePass = false)
        f.insert("Triggered contact")
        f.await { f.rawIn(f.personal).size == 1 }
        assertTrue(f.rawIn(null).isEmpty())
        f.await { f.context.getSystemService(JobScheduler::class.java).getPendingJob(SyncRuntime.CONTACTS_JOB) != null }
    }

    @Test fun oneTimeRuntimeActionUsesFamilyAndUnavailableTargetDoesNotDeletePhoneData() = scenario { f ->
        requireIsolatedPhoneBook(f.context)
        val id = f.insert("One-time")
        f.config.write(ContactsSettings(f.family))
        BridgeStore(f.context).use { it.syncStatus = it.syncStatus.copy(contactsWaiting = 3) }
        assertEquals(ContactsResult(1), SyncRuntime.moveExistingContacts(f.context).get(15, TimeUnit.SECONDS))
        BridgeStore(f.context).use {
            assertEquals(1, it.syncStatus.changes); assertNotNull(it.syncStatus.lastPassAt)
            assertTrue(it.syncStatus.stopReasons.isEmpty()); assertNull(it.pendingContactsMove)
            assertEquals(0, it.syncStatus.contactsWaiting)
        }
        assertEquals(1, f.rawIn(f.family).size)
        val remaining = f.insert("Keep")
        f.resolver.update(Settings.CONTENT_URI, ContentValues().apply { put(Settings.SHOULD_SYNC, 0) }, "account_name = ?", arrayOf(f.family.name))
        assertTrue(ContactsMover(f.port).moveExisting(f.family, f.ownedRawIds.toSet()).stopReasons.isNotEmpty())
        assertEquals(listOf(remaining), f.rawIn(null)); assertFalse(f.rawIn(null).contains(id))
    }

    @Test fun failedFamilyActionResumesAcrossReopenWithoutRoutingOldContactsToPersonalOrNewOnesToFamily() = scenario { f ->
        requireIsolatedPhoneBook(f.context)
        val old = f.insert("Existing")
        f.config.write(ContactsSettings(f.family))
        val failed = f.trackedContext(beforeBatch = { throw android.content.OperationApplicationException("Injected batch failure") })
        val result = SyncRuntime.moveExistingContacts(failed).get(15, TimeUnit.SECONDS)
        assertTrue(result.retry); assertEquals(0, result.changes); assertTrue(result.stopReasons.isNotEmpty())
        BridgeStore(f.context).use {
            assertEquals(ContactsMove(f.family, setOf(old)), it.pendingContactsMove)
            assertEquals(result.stopReasons, it.syncStatus.stopReasons); assertEquals(0, it.syncStatus.changes)
        }
        f.insert("New phone contact")
        f.config.write(ContactsSettings(f.personal))
        val status = SyncRuntime.pass(f.context)
        assertTrue(status.stopReasons.isEmpty()); assertEquals(2, status.changes)
        assertEquals(1, f.rawIn(f.family).size); assertEquals(1, f.rawIn(f.personal).size)
        BridgeStore(f.context).use { assertNull(it.pendingContactsMove); assertEquals(status, it.syncStatus) }
    }

    @Test fun contactsPermissionKeepsTheLastPassAndDoesNotDisableCalendarOrLoseThePendingAction() =
        scenario(::contactsPermissionRecovery)

    @Test fun contactsPermissionRecoveryRejectsAnUnrelatedPhoneSourceBeforeItCanMove() = scenario { f ->
        requireIsolatedPhoneBook(f.context)
        val unrelated = f.insert("Synthetic unrelated source")
        val rejection = assertThrows(AssertionError::class.java) { contactsPermissionRecovery(f) }
        assertTrue(rejection.message.orEmpty().startsWith("Fresh-emulator precondition:"))
        assertEquals(listOf(unrelated), f.rawIn(null))
        assertTrue(f.rawIn(f.personal).isEmpty())
        assertTrue(f.rawIn(f.family).isEmpty())
    }

    private fun contactsPermissionRecovery(f: Fixture) {
        requireIsolatedPhoneBook(f.context)
        val id = f.insert("Pending")
        BridgeStore(f.context).use {
            it.pendingContactsMove = ContactsMove(f.family, setOf(id))
            it.syncStatus = it.syncStatus.copy(contactsWaiting = 3)
        }
        val denied = object : ContextWrapper(f.context) {
            override fun checkSelfPermission(permission: String): Int = if (permission == Manifest.permission.WRITE_CONTACTS)
                android.content.pm.PackageManager.PERMISSION_DENIED else super.checkSelfPermission(permission)
        }
        val previous = BridgeStore(f.context).use { it.syncStatus }
        val status = SyncRuntime.pass(denied)
        assertEquals(setOf(SyncStopReason.CONTACTS_ACCESS_REQUIRED), status.stopReasons); assertEquals(0, status.changes)
        assertEquals(f.baselinePhone + id, f.rawIn(null).toSet())
        BridgeStore(f.context).use { assertNotNull(it.pendingContactsMove); assertEquals(previous, it.syncStatus) }
        assertEquals(1, SyncRuntime.pass(f.context).changes)
        BridgeStore(f.context).use { assertNull(it.pendingContactsMove); assertEquals(0, it.syncStatus.contactsWaiting) }
    }

    @Test fun confirmedContactsMovePersistsFreshWaitingCountButNotOnAccessDenial() = scenario { f ->
        requireIsolatedPhoneBook(f.context)
        repeat(3) { f.insert("Waiting $it") }
        f.config.write(ContactsSettings(f.family))
        val previous = SyncRuntime.pass(f.context)
        assertEquals(3, previous.contactsWaiting)
        val move = ContactsMove(f.family, f.rawIn(null).toSet())
        val denied = object : ContextWrapper(f.context) {
            override fun checkSelfPermission(permission: String): Int = if (permission == Manifest.permission.WRITE_CONTACTS)
                android.content.pm.PackageManager.PERMISSION_DENIED else super.checkSelfPermission(permission)
        }
        assertEquals(setOf(SyncStopReason.CONTACTS_ACCESS_REQUIRED),
            SyncRuntime.moveConfirmedContacts(denied, move).get(15, TimeUnit.SECONDS).stopReasons)
        BridgeStore(f.context).use { assertEquals(previous, it.syncStatus); assertEquals(move, it.pendingContactsMove) }
        assertEquals(3, SyncRuntime.moveConfirmedContacts(f.context, move).get(15, TimeUnit.SECONDS).changes)
        BridgeStore(f.context).use { assertEquals(0, it.syncStatus.contactsWaiting); assertNull(it.pendingContactsMove) }
        assertTrue(f.rawIn(null).isEmpty())
        assertEquals(3, f.rawIn(f.family).size)
    }

    private fun row(mime: String, value: String, type: Int? = null) = ContentValues().apply {
        put(Data.MIMETYPE, mime); put(Data.DATA1, value); if (type != null) put(Data.DATA2, type)
    }
    private fun scenario(body: (Fixture) -> Unit) {
        val f = Fixture()
        withFixtureCleanup(
            { f.context.getSystemService(JobScheduler::class.java).cancelAll(); SyncRuntime.enqueue {}.get(15, TimeUnit.SECONDS) },
            { f.config.write(f.previous) },
            { BridgeStore(f.context).use { it.pendingContactsMove = null } },
            { f.ownedRawIds += f.added.flatMap { f.rawIn(ContactAccount(it.name, it.type)) }
                for (id in f.ownedRawIds.distinct()) f.resolver.delete(ContentUris.withAppendedId(RawContacts.CONTENT_URI, id), null, null) },
            { for (account in f.added) {
                f.resolver.delete(Settings.CONTENT_URI, "account_name = ?", arrayOf(account.name))
                f.resolver.delete(Groups.CONTENT_URI, "account_name = ?", arrayOf(account.name))
            } },
            { for (id in f.ownedGroupIds) f.resolver.delete(ContentUris.withAppendedId(Groups.CONTENT_URI, id), null, null) },
            { for (account in f.added) check(AccountManager.get(f.context).removeAccountExplicitly(account)) },
        ) { f.setup(); body(f) }
    }
    private data class StoredPhoto(val image: String, val width: Int, val height: Int, val primary: Int, val superPrimary: Int)
    private class Fixture {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val resolver = context.contentResolver
        val config = PreferenceContactsConfig(context)
        val previous = config.read()
        private val identitySuffix = UUID.randomUUID().toString()
        private val numbers = mutableMapOf<String, String>()
        private val emails = mutableMapOf<String, String>()
        val personal = ContactAccount("Personal-${UUID.randomUUID()}", "com.alexindigo.kotatsu.sync.test")
        val family = ContactAccount("Family-${UUID.randomUUID()}", "com.alexindigo.kotatsu.sync.test")
        val added = mutableListOf<Account>()
        val ownedRawIds = mutableListOf<Long>()
        val ownedGroupIds = mutableListOf<Long>()
        var baselinePhone = emptySet<Long>()
        var baselineAggregates = 0
        val port = AndroidContacts(context)
        fun name(label: String) = "$label $identitySuffix"
        fun email(label: String) = emails.getOrPut(label) { "${UUID.randomUUID()}@example.invalid" }
        /** Cache by logical identity so only deliberate joined pairs reuse a number. */
        fun number(label: String): String = numbers.getOrPut(label) {
            var candidate: String
            do {
                val digits = ((UUID.randomUUID().leastSignificantBits and Long.MAX_VALUE) % 10_000_000).toString().padStart(7, '0')
                candidate = "+1202$digits"
            } while (candidate in numbers.values || resolver.query(ContactsContract.PhoneLookup.CONTENT_FILTER_URI.buildUpon().appendPath(candidate).build(),
                    arrayOf(ContactsContract.PhoneLookup._ID), null, null, null)!!.use { it.count != 0 })
            candidate
        }
        fun pass(destination: ContactAccount) = ContactsMover(port).pass(destination, ownedRawIds.toSet())
        fun setup() {
            context.getSystemService(JobScheduler::class.java).cancelAll(); SyncRuntime.enqueue {}.get(15, TimeUnit.SECONDS)
            for (p in listOf(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS, Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR))
                instrumentation.uiAutomation.grantRuntimePermission(context.packageName, p)
            config.write(ContactsSettings())
            baselinePhone = phoneSourceIds(context)
            baselineAggregates = liveAggregateCount()
            for (account in listOf(personal, family)) {
                val a = Account(account.name, account.type); check(AccountManager.get(context).addAccountExplicitly(a, null, null)); added.add(a)
                resolver.insert(Settings.CONTENT_URI, ContentValues().apply {
                    put(Settings.ACCOUNT_NAME, a.name); put(Settings.ACCOUNT_TYPE, a.type); put(Settings.SHOULD_SYNC, 1); put(Settings.UNGROUPED_VISIBLE, 1)
                })!!
            }
            check(personal in port.addressBooks() && family in port.addressBooks())
        }
        fun trackedContext(onAuthority: (String?) -> Unit = {}, beforeBatch: () -> Unit = {},
            batch: (ArrayList<ContentProviderOperation>) -> Array<android.content.ContentProviderResult> = {
                resolver.applyBatch(ContactsContract.AUTHORITY, it)
            }): android.content.Context {
            val tracked = ContentResolver.wrap(object : ContentProvider() {
                override fun onCreate() = true
                override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): android.database.Cursor? {
                    onAuthority(uri.authority)
                    return resolver.query(uri, projection, selection, selectionArgs, sortOrder)
                }
                override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
                    onAuthority(ContactsContract.AUTHORITY)
                    return resolver.call(ContactsContract.AUTHORITY_URI, method, arg, extras)
                }
                override fun applyBatch(operations: ArrayList<ContentProviderOperation>): Array<android.content.ContentProviderResult> {
                    operations.forEach { onAuthority(it.uri.authority) }; beforeBatch()
                    return batch(operations)
                }
                override fun openAssetFile(uri: Uri, mode: String): android.content.res.AssetFileDescriptor? {
                    onAuthority(uri.authority)
                    return resolver.openAssetFileDescriptor(uri, mode)
                }
                override fun getType(uri: Uri) = null
                override fun insert(uri: Uri, values: ContentValues?) = throw AssertionError("Expected atomic batch")
                override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = throw AssertionError("Expected atomic batch")
                override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = throw AssertionError("No SIM deletion")
            })
            return object : ContextWrapper(context) { override fun getContentResolver() = tracked }
        }
        fun insert(name: String?, number: String? = null, account: ContactAccount? = null): Long {
            val id = ContentUris.parseId(resolver.insert(RawContacts.CONTENT_URI, ContentValues().apply {
                put(RawContacts.ACCOUNT_NAME, account?.name); put(RawContacts.ACCOUNT_TYPE, account?.type)
            })!!); ownedRawIds.add(id)
            if (name != null) resolver.insert(Data.CONTENT_URI, ContentValues().apply {
                put(Data.RAW_CONTACT_ID, id); put(Data.MIMETYPE, StructuredName.CONTENT_ITEM_TYPE); put(StructuredName.DISPLAY_NAME, this@Fixture.name(name))
            })!!
            if (number != null) resolver.insert(Data.CONTENT_URI, ContentValues().apply {
                put(Data.RAW_CONTACT_ID, id); put(Data.MIMETYPE, Phone.CONTENT_ITEM_TYPE); put(Phone.NUMBER, this@Fixture.number(number)); put(Phone.TYPE, Phone.TYPE_MOBILE)
            })!!
            return id
        }
        fun rawIn(account: ContactAccount?): List<Long> = if (account == null) phoneSourceIds(context).sorted() else resolver.query(RawContacts.CONTENT_URI, arrayOf(RawContacts._ID),
            "${RawContacts.DELETED} = 0 AND account_name = ? AND account_type = ?",
            arrayOf(account.name, account.type), RawContacts._ID)!!.use { c -> buildList { while (c.moveToNext()) add(c.getLong(0)) } }
        fun aggregate(rawId: Long): Long = resolver.query(ContentUris.withAppendedId(RawContacts.CONTENT_URI, rawId),
            arrayOf(RawContacts.CONTACT_ID), null, null, null)!!.use { check(it.moveToFirst()); it.getLong(0) }
        fun liveAggregateCount() = resolver.query(Contacts.CONTENT_URI, arrayOf(Contacts._ID), null, null, null)!!.use { it.count }
        fun visibility(raw: Long): Int = resolver.query(ContentUris.withAppendedId(Contacts.CONTENT_URI, aggregate(raw)),
            arrayOf(Contacts.IN_VISIBLE_GROUP), null, null, null)!!.use { check(it.moveToFirst()); it.getInt(0) }
        fun displayPhoto(id: Long): ByteArray = resolver.openInputStream(ContentUris.withAppendedId(RawContacts.CONTENT_URI, id)
            .buildUpon().appendPath(RawContacts.DisplayPhoto.CONTENT_DIRECTORY).build())!!.use { it.readBytes() }
        fun photos(rawId: Long): List<StoredPhoto> = resolver.query(Data.CONTENT_URI,
            arrayOf(Photo.PHOTO_FILE_ID, Data.IS_PRIMARY, Data.IS_SUPER_PRIMARY),
            "${Data.RAW_CONTACT_ID} = ? AND ${Data.MIMETYPE} = ?", arrayOf(rawId.toString(), Photo.CONTENT_ITEM_TYPE), Data._ID)!!.use { c ->
            val fileIds = mutableSetOf<Long>()
            buildList { while (c.moveToNext()) {
                assertFalse(c.isNull(0)); assertTrue(fileIds.add(c.getLong(0)))
                val uri = ContentUris.withAppendedId(ContactsContract.DisplayPhoto.CONTENT_URI, c.getLong(0))
                val bitmap = resolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it) }!!
                withFixtureCleanup({ bitmap.recycle() }) {
                    val pixel = bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)
                    val image = when {
                        Color.red(pixel) > 200 && Color.blue(pixel) < 30 -> "red"
                        Color.blue(pixel) > 200 && Color.red(pixel) < 30 -> "blue"
                        else -> error("Full-size photo content changed")
                    }
                    add(StoredPhoto(image, bitmap.width, bitmap.height, c.getInt(1), c.getInt(2)))
                }
            } }
        }
        fun ids(uri: Uri): List<Long> = resolver.query(uri, arrayOf("_id"), null, null, "_id")!!.use { c ->
            buildList { while (c.moveToNext()) add(c.getLong(0)) }
        }
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
