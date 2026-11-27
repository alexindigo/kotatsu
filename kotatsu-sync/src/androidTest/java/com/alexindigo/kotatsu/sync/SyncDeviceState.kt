package com.alexindigo.kotatsu.sync

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.app.job.JobWorkItem
import android.content.Context
import android.content.ContextWrapper
import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract
import android.provider.ContactsContract.Settings
import android.provider.CalendarContract.Calendars
import org.junit.Assert.assertTrue

/** Read the same phone-origin contract as production, including a device's local account. */
internal fun phoneSourceIds(context: Context): Set<Long> = AndroidContacts(context).sources()
    .filter { it.origin == ContactOrigin.PHONE }.map { it.id }.toSet()

/** The seeded-isolation verifier requires this test to reject a nonempty phone book. */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class RequiresIsolatedPhoneBook

internal fun requireIsolatedPhoneBook(context: Context) {
    assertTrue("Fresh-emulator precondition: unscoped contacts test must not touch unrelated phone contacts",
        phoneSourceIds(context).isEmpty())
}

/** Owner-only tests exercise queued intents, not native dispatch. Never launch a real all-phone job. */
internal fun ownerTestContext(context: Context, bookNames: Set<String>? = null,
    readContactSources: Boolean = true,
): Context = object : ContextWrapper(context) {
    private val jobs = object : JobScheduler() {
        private val pending = mutableMapOf<Int, JobInfo>()
        override fun schedule(job: JobInfo): Int { pending[job.id] = job; return RESULT_SUCCESS }
        override fun enqueue(job: JobInfo, work: JobWorkItem): Int = schedule(job)
        override fun cancel(id: Int) { pending.remove(id) }
        override fun cancelAll() { pending.clear() }
        override fun getAllPendingJobs(): List<JobInfo> = pending.values.toList()
        override fun getPendingJob(id: Int): JobInfo? = pending[id]
    }
    override fun getApplicationContext(): Context = this
    override fun getSystemService(name: String): Any? = if (name == Context.JOB_SCHEDULER_SERVICE) jobs else super.getSystemService(name)
    private val scopedResolver = ContentResolver.wrap(object : ContentProvider() {
        override fun onCreate() = true
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? {
            if (uri == ContactsContract.RawContacts.CONTENT_URI && !readContactSources)
                return context.contentResolver.query(uri, projection, "0", null, sortOrder)
            if ((uri == Settings.CONTENT_URI || uri == Calendars.CONTENT_URI) && bookNames != null) {
                val filter = if (bookNames.isEmpty()) "0" else "account_name IN (${bookNames.joinToString { "?" }})"
                return context.contentResolver.query(uri, projection, "(${selection ?: "1"}) AND ($filter)",
                    (selectionArgs.orEmpty().toList() + bookNames).toTypedArray(), sortOrder)
            }
            return context.contentResolver.query(uri, projection, selection, selectionArgs, sortOrder)
        }
        override fun call(method: String, arg: String?, extras: Bundle?): Bundle? =
            context.contentResolver.call(ContactsContract.AUTHORITY_URI, method, arg, extras)
        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = error("Owner-only provider is read-only")
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = error("Owner-only provider is read-only")
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = error("Owner-only provider is read-only")
    })
    override fun getContentResolver(): ContentResolver = if (bookNames == null && readContactSources) context.contentResolver else scopedResolver
}
