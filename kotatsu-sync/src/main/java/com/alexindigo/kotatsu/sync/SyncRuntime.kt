package com.alexindigo.kotatsu.sync

import android.Manifest
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import android.provider.ContactsContract
import java.util.concurrent.Executors

/** All entry points share one lane, including future settings/manual-sync callers. */
object SyncRuntime {
    const val WATCH_JOB = 0x4b5301
    const val PERIODIC_JOB = 0x4b5302
    const val HORIZON_JOB = 0x4b5303
    const val BOOT_JOB = 0x4b5304
    const val CONTACTS_JOB = 0x4b5305
    private val lane = Executors.newSingleThreadExecutor()
    fun <T> enqueue(work: () -> T): java.util.concurrent.Future<T> = lane.submit(java.util.concurrent.Callable { work() })

    fun pass(context: Context): SyncStatus = BridgeStore(context).use { store ->
        var changes = 0
        var retry = false
        val reasons = mutableListOf<String>()
        if (!permitted(context, Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)) {
            reasons.add("calendar permission not granted")
            store.status = "stopped: calendar permission not granted"
        } else {
            val calendar = BridgeEngine(MuditaCalendar(context.contentResolver), StandardCalendar(context.contentResolver),
                store, PreferenceSyncConfig(context))
            try {
                val result = calendar.pass(System.currentTimeMillis())
                retry = result.retry
                result.stopReason?.let(reasons::add)
            } catch (error: Exception) {
                val reason = "calendar pass incomplete: ${error.javaClass.simpleName}: ${error.message.orEmpty()}"
                store.status = reason; reasons.add(reason); retry = true
            } finally { changes += calendar.changes }
        }
        // Each provider owns its own permission/availability stop. A stopped
        // calendar does not discard an already-requested contacts transfer.
        val contacts = contactsPass(context, store, existing = false)
        changes += contacts.changes
        contacts.stopReason?.let {
            reasons.add(it); store.status = "stopped: ${reasons.joinToString("; ")}"
        }
        retry = retry || contacts.retry
        val status = SyncStatus(System.currentTimeMillis(), changes,
            reasons.distinct().takeIf { it.isNotEmpty() }?.joinToString("; "))
        store.syncStatus = status
        if (retry) throw RetryPass()
        status
    }

    private class RetryPass : Exception("Retryable bridge provider operation")
    private fun permitted(context: Context, vararg permissions: String) = permissions.all {
        context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
    }
    private fun contactsPass(context: Context, store: BridgeStore, existing: Boolean): ContactsResult {
        val engine = ContactsTransferEngine(AndroidContacts(context), store)
        return try {
            val settings = PreferenceContactsConfig(context).read()
            if (!existing && settings.target == null && store.pendingContactsMove == null) return ContactsResult()
            if (!permitted(context, Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS))
                return ContactsResult(stopReason = "contacts permission not granted")
            if (existing) engine.moveExisting(settings) else engine.pass(settings)
        } catch (error: Exception) {
            ContactsResult(engine.changes, "contacts pass incomplete: ${error.javaClass.simpleName}: ${error.message.orEmpty()}", retry = true)
        }
    }
    fun moveExistingContacts(context: Context) = enqueue {
        BridgeStore(context).use { store ->
            contactsPass(context, store, existing = true).also { result ->
                store.syncStatus = SyncStatus(System.currentTimeMillis(), result.changes, result.stopReason)
                store.status = result.stopReason?.let { "stopped: $it" } ?: "contacts pass complete"
            }
        }
    }

    /** Confirmation carries the exact destination and phone identities shown to the user. */
    fun moveConfirmedContacts(context: Context, confirmed: ContactsMove) = enqueue {
        BridgeStore(context).use { store ->
            val pending = store.pendingContactsMove
            check(pending == null || pending.destination == confirmed.destination && pending.sourceIds.containsAll(confirmed.sourceIds)) {
                "contacts move already pending"
            }
            if (pending == null) store.pendingContactsMove = confirmed
            contactsPass(context, store, existing = true).also { result ->
                store.syncStatus = SyncStatus(System.currentTimeMillis(), result.changes, result.stopReason)
                store.status = result.stopReason?.let { "stopped: $it" } ?: "contacts pass complete"
            }
        }
    }

    /** Explicit evidence from a future settings/manual-resolution caller; serialized with passes. */
    fun resolve(context: Context, id: String, resolution: WriteResolution) = enqueue {
        BridgeStore(context).use { store ->
            BridgeEngine(MuditaCalendar(context.contentResolver), StandardCalendar(context.contentResolver),
                store, PreferenceSyncConfig(context)).resolvePending(System.currentTimeMillis(), id, resolution)
        }
        pass(context)
    }

    /** A component can be started while the package has never received BOOT_COMPLETED. */
    fun start(context: Context) {
        try { ensureArmed(context) }
        catch (error: RuntimeException) {
            schedulingFailed(context, error)
        }
    }

    internal fun schedulingFailed(context: Context, error: RuntimeException) {
        BridgeStore(context).use { store ->
            val reason = "bridge scheduling: ${error.message.orEmpty()}"
            store.status = "stopped: $reason"
            store.syncStatus = store.syncStatus.copy(stopReason = reason)
        }
    }

    @Synchronized
    fun ensureArmed(context: Context, queuePass: Boolean = true, replaceWatch: Boolean = false, replaceContactsWatch: Boolean = false) {
        val scheduler = context.getSystemService(JobScheduler::class.java)
        fun schedule(builder: JobInfo.Builder) {
            check(scheduler.schedule(builder.build()) == JobScheduler.RESULT_SUCCESS) { "Could not schedule calendar bridge" }
        }
        fun recurring() = SyncArming.ensure(listOf(WATCH_JOB, CONTACTS_JOB, PERIODIC_JOB, HORIZON_JOB),
            { id -> !(replaceWatch && id == WATCH_JOB || replaceContactsWatch && id == CONTACTS_JOB) && scheduler.getPendingJob(id) != null }) { id ->
            when (id) {
            WATCH_JOB -> {
            schedule(JobInfo.Builder(WATCH_JOB, ComponentName(context, CalendarChangedJob::class.java))
                .addTriggerContentUri(JobInfo.TriggerContentUri(CalendarContract.CONTENT_URI,
                    JobInfo.TriggerContentUri.FLAG_NOTIFY_FOR_DESCENDANTS))
                .setTriggerContentUpdateDelay(200).setTriggerContentMaxDelay(1000))
            }
            PERIODIC_JOB -> schedule(JobInfo.Builder(id, ComponentName(context, PeriodicPassJob::class.java)).setPeriodic(15 * 60 * 1000L))
            HORIZON_JOB -> schedule(JobInfo.Builder(id, ComponentName(context, HorizonJob::class.java)).setPeriodic(24 * 60 * 60 * 1000L))
            CONTACTS_JOB -> schedule(JobInfo.Builder(id, ComponentName(context, ContactsChangedJob::class.java))
                .addTriggerContentUri(JobInfo.TriggerContentUri(ContactsContract.RawContacts.CONTENT_URI,
                    JobInfo.TriggerContentUri.FLAG_NOTIFY_FOR_DESCENDANTS))
                .setTriggerContentUpdateDelay(200).setTriggerContentMaxDelay(1000))
            }
        }
        // Queue the pass even if recurring-job scheduling is rejected; attempt both responsibilities.
        PassLifecycle.boot({ recurring() }, {
            if (queuePass) SyncArming.ensure(listOf(BOOT_JOB), { scheduler.getPendingJob(it) != null }) { id ->
                schedule(JobInfo.Builder(id, ComponentName(context, BootPassJob::class.java))
                    .setMinimumLatency(0).setOverrideDeadline(0))
            }
        })
    }
}
