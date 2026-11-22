package com.alexindigo.kotatsu.sync

import android.app.job.JobScheduler
import android.content.ContentProvider
import android.content.ContentValues
import android.net.Uri
import android.os.Bundle

/** Read-only external connected probe; opening it starts the real Application. */
class TestEntryProvider : ContentProvider() {
    override fun onCreate() = true
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        check(method == "probe")
        val context = checkNotNull(context)
        return BridgeStore(context).use { store -> Bundle().apply {
            putString("status", store.status)
            putLong("lastPassAt", store.syncStatus.lastPassAt ?: 0)
            putInt("changes", store.syncStatus.changes)
            putString("stopReason", store.syncStatus.stopReason)
            putString("jobs", context.getSystemService(JobScheduler::class.java).allPendingJobs.map { it.id }.sorted().joinToString(","))
        } }
    }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?) = null
    override fun getType(uri: Uri) = null
    override fun insert(uri: Uri, values: ContentValues?) = throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = throw UnsupportedOperationException()
}
