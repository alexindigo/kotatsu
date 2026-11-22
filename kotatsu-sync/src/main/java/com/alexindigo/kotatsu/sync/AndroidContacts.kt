package com.alexindigo.kotatsu.sync

import android.content.ContentProviderOperation
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.os.Build
import android.provider.ContactsContract
import android.provider.ContactsContract.AggregationExceptions
import android.provider.ContactsContract.CommonDataKinds.GroupMembership
import android.provider.ContactsContract.CommonDataKinds.Photo
import android.provider.ContactsContract.Data
import android.provider.ContactsContract.Groups
import android.provider.ContactsContract.RawContacts
import android.provider.ContactsContract.Settings

/** App writes leave target rows dirty for DAVx5. No caller_is_syncadapter bypass. */
class AndroidContacts(private val context: Context) : ContactsPort {
    private val resolver = context.contentResolver

    override fun addressBooks(): Set<ContactAccount> {
        val types = ContentResolver.getSyncAdapterTypes().filter {
            it.authority == ContactsContract.AUTHORITY && it.supportsUploading()
        }.map { it.accountType }.toSet()
        return query(Settings.CONTENT_URI, arrayOf(Settings.ACCOUNT_NAME, Settings.ACCOUNT_TYPE, Settings.DATA_SET),
            "${Settings.SHOULD_SYNC} = 1", null) { c -> account(c, 0) }.filterNotNull().filter { it.type in types }.toSet()
    }

    override fun sources(): List<PhoneContact> {
        val local = if (Build.VERSION.SDK_INT >= 30) ContactAccount(RawContacts.getLocalAccountName(context).orEmpty(),
            RawContacts.getLocalAccountType(context).orEmpty()) else null
        val sims = if (Build.VERSION.SDK_INT >= 31) ContactsContract.SimContacts.getSimAccounts(resolver)
            .map { ContactAccount(it.accountName, it.accountType) }.toSet() else emptySet()
        return query(RawContacts.CONTENT_URI, arrayOf(RawContacts._ID, RawContacts.VERSION,
            RawContacts.ACCOUNT_NAME, RawContacts.ACCOUNT_TYPE, RawContacts.DATA_SET),
            "${RawContacts.DELETED} = 0", null) { c ->
            val account = account(c, 2)
            val origin = when {
                account in sims -> ContactOrigin.SIM
                account == null || (account.dataSet == null && account == local) -> ContactOrigin.PHONE
                else -> ContactOrigin.OTHER
            }
            PhoneContact(c.getLong(0), c.getLong(1), account, origin)
        }
    }

    override fun move(source: PhoneContact, destination: ContactAccount): Boolean {
        if (source.origin != ContactOrigin.PHONE || destination !in addressBooks()) return false
        val uri = ContentUris.withAppendedId(RawContacts.CONTENT_URI, source.id)
        val data = query(Data.CONTENT_URI, DATA_COLUMNS, "${Data.RAW_CONTACT_ID} = ?", arrayOf(source.id.toString()), ::values)
        // DATA14 is a provider-owned file id. Supply the actual full-size image,
        // not that id or just the thumbnail, so the new raw contact owns its photo.
        for (row in data.filter { it.getAsString(Data.MIMETYPE) == Photo.CONTENT_ITEM_TYPE }) {
            if (row.getAsLong(Photo.PHOTO_FILE_ID) != null) {
                val photo = uri.buildUpon().appendPath(RawContacts.DisplayPhoto.CONTENT_DIRECTORY).build()
                row.put(Photo.PHOTO, checkNotNull(resolver.openInputStream(photo)).use { it.readBytes() })
            }
            row.remove(Photo.PHOTO_FILE_ID)
        }
        val metadata = query(uri, RAW_METADATA, null, null, ::values).singleOrNull() ?: return false
        val operations = arrayListOf(ContentProviderOperation.newAssertQuery(uri)
            .withValue(RawContacts.VERSION, source.version).withValue(RawContacts.DELETED, 0)
            .withValue(RawContacts.ACCOUNT_NAME, source.account?.name)
            .withValue(RawContacts.ACCOUNT_TYPE, source.account?.type)
            .withValue(RawContacts.DATA_SET, source.account?.dataSet).withExpectedCount(1).build())
        // Settings are part of the same provider transaction: a removed/disabled
        // address book cannot silently become a new orphan target account.
        operations.add(ContentProviderOperation.newAssertQuery(Settings.CONTENT_URI)
            .withSelection(accountSelection(destination), accountArgs(destination))
            .withValue(Settings.SHOULD_SYNC, 1).withExpectedCount(1).build())
        val insert = operations.size
        operations.add(ContentProviderOperation.newInsert(RawContacts.CONTENT_URI).withValues(metadata).apply {
            withValue(RawContacts.ACCOUNT_NAME, destination.name); withValue(RawContacts.ACCOUNT_TYPE, destination.type)
            withValue(RawContacts.DATA_SET, destination.dataSet)
        }.build())
        val groups = mutableMapOf<Long, Pair<Long?, Int?>>()
        for (row in data) {
            val builder = ContentProviderOperation.newInsert(Data.CONTENT_URI)
                .withValueBackReference(Data.RAW_CONTACT_ID, insert)
            if (row.getAsString(Data.MIMETYPE) == GroupMembership.CONTENT_ITEM_TYPE) {
                val old = checkNotNull(row.getAsLong(GroupMembership.GROUP_ROW_ID))
                val group = groups.getOrPut(old) { destinationGroup(old, destination, operations) }
                row.remove(GroupMembership.GROUP_ROW_ID)
                if (group.first != null) builder.withValue(GroupMembership.GROUP_ROW_ID, group.first)
                else builder.withValueBackReference(GroupMembership.GROUP_ROW_ID, checkNotNull(group.second))
            }
            operations.add(builder.withValues(row).build())
        }
        // Android aggregates immediately for an explicit exception, before the
        // source deletion. This keeps the existing aggregate/lookup identity even
        // for nameless contacts that automatic matching would not join.
        operations.add(ContentProviderOperation.newUpdate(AggregationExceptions.CONTENT_URI)
            .withValue(AggregationExceptions.TYPE, AggregationExceptions.TYPE_KEEP_TOGETHER)
            .withValue(AggregationExceptions.RAW_CONTACT_ID1, source.id)
            .withValueBackReference(AggregationExceptions.RAW_CONTACT_ID2, insert).withExpectedCount(1).build())
        operations.add(ContentProviderOperation.newDelete(uri).withExpectedCount(1).build())
        return try {
            val result = resolver.applyBatch(ContactsContract.AUTHORITY, operations)
            checkNotNull(result[insert].uri)
            true
        } catch (_: android.content.OperationApplicationException) { false }
    }

    /** Group ids are account-owned references, not portable data1 values. */
    private fun destinationGroup(id: Long, destination: ContactAccount, operations: ArrayList<ContentProviderOperation>): Pair<Long?, Int?> {
        val groupUri = ContentUris.withAppendedId(Groups.CONTENT_URI, id)
        val group = query(groupUri, arrayOf(Groups.TITLE, Groups.NOTES, Groups.GROUP_VISIBLE, Groups.VERSION), null, null, ::values).single()
        operations.add(ContentProviderOperation.newAssertQuery(groupUri).withValues(group)
            .withValue(Groups.DELETED, 0).withExpectedCount(1).build())
        group.remove(Groups.VERSION)
        val existing = query(Groups.CONTENT_URI, arrayOf(Groups._ID, Groups.TITLE, Groups.NOTES),
            "${accountSelection(destination)} AND ${Groups.DELETED} = 0", accountArgs(destination)) { c ->
            Triple(c.getLong(0), c.getString(1), c.getString(2))
        }.firstOrNull { it.second == group.getAsString(Groups.TITLE) && it.third == group.getAsString(Groups.NOTES) }
        if (existing != null) return existing.first to null
        val index = operations.size
        operations.add(ContentProviderOperation.newInsert(Groups.CONTENT_URI).withValues(group)
            .withValue(Groups.ACCOUNT_NAME, destination.name).withValue(Groups.ACCOUNT_TYPE, destination.type)
            .withValue(Groups.DATA_SET, destination.dataSet).build())
        return null to index
    }

    private fun account(c: Cursor, offset: Int): ContactAccount? {
        val name = c.getString(offset); val type = c.getString(offset + 1)
        return if (name.isNullOrEmpty() && type.isNullOrEmpty() && c.isNull(offset + 2)) null
            else ContactAccount(name.orEmpty(), type.orEmpty(), c.getString(offset + 2))
    }
    private fun accountSelection(a: ContactAccount) = "account_name = ? AND account_type = ? AND " +
        if (a.dataSet == null) "data_set IS NULL" else "data_set = ?"
    private fun accountArgs(a: ContactAccount) = (listOf(a.name, a.type) + listOfNotNull(a.dataSet)).toTypedArray()
    private fun values(c: Cursor) = ContentValues().apply {
        for ((index, column) in c.columnNames.withIndex()) when (c.getType(index)) {
            Cursor.FIELD_TYPE_NULL -> putNull(column)
            Cursor.FIELD_TYPE_STRING -> put(column, c.getString(index))
            Cursor.FIELD_TYPE_INTEGER -> put(column, c.getLong(index))
            Cursor.FIELD_TYPE_FLOAT -> put(column, c.getDouble(index))
            Cursor.FIELD_TYPE_BLOB -> put(column, c.getBlob(index))
        }
    }
    private fun <T> query(uri: android.net.Uri, columns: Array<String>, selection: String?, args: Array<String>?, read: (Cursor) -> T): List<T> =
        checkNotNull(resolver.query(uri, columns, selection, args, null)) { "ContactsProvider returned no cursor" }.use { c ->
            buildList { while (c.moveToNext()) add(read(c)) }
        }
    companion object {
        private val DATA_COLUMNS = (listOf(Data.MIMETYPE, Data.RES_PACKAGE, Data.IS_PRIMARY, Data.IS_SUPER_PRIMARY) + (1..15).map { "data$it" }).toTypedArray()
        private val RAW_METADATA = arrayOf(RawContacts.STARRED, RawContacts.CUSTOM_RINGTONE, RawContacts.SEND_TO_VOICEMAIL)
    }
}
