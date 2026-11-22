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

    fun pass(context: Context) {
        BridgeStore(context).use { store ->
            if (context.checkSelfPermission(Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED ||
                context.checkSelfPermission(Manifest.permission.WRITE_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
                store.status = "stopped: calendar permission not granted"; return
            }
            try {
                val result = BridgeEngine(MuditaCalendar(context.contentResolver), StandardCalendar(context.contentResolver),
                    store, PreferenceSyncConfig(context)).pass(System.currentTimeMillis())
                val destination = PreferenceContactsConfig(context).read().personal
                if (destination != null) {
                    val contacts = contactsPass(context, destination, existing = false)
                    if (contacts.stopReason != null) store.status = "contacts pass incomplete: ${contacts.stopReason}"
                }
                if (result.retry) throw RetryPass()
            } catch (error: Exception) {
                if (error !is RetryPass) store.status = "calendar pass incomplete: ${error.javaClass.simpleName}: ${error.message.orEmpty()}"
                throw error
            }
        }
    }

    private class RetryPass : Exception("Retryable calendar provider operation")

    private fun contactsPass(context: Context, destination: ContactAccount?, existing: Boolean): ContactsResult {
        if (context.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED ||
            context.checkSelfPermission(Manifest.permission.WRITE_CONTACTS) != PackageManager.PERMISSION_GRANTED)
            return ContactsResult(stopReason = "contacts permission not granted")
        val mover = ContactsMover(AndroidContacts(context))
        return if (existing) mover.moveExisting(destination) else mover.pass(destination)
    }
    fun moveExistingContacts(context: Context) = enqueue {
        contactsPass(context, PreferenceContactsConfig(context).read().family, existing = true)
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
            BridgeStore(context).use { it.status = "stopped: bridge scheduling: ${error.message.orEmpty()}" }
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
