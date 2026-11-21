package com.alexindigo.kotatsu.sync

import android.Manifest
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import java.util.concurrent.Executors

/** All entry points share one lane, including future settings/manual-sync callers. */
object SyncRuntime {
    const val WATCH_JOB = 0x4b5301
    const val PERIODIC_JOB = 0x4b5302
    const val HORIZON_JOB = 0x4b5303
    private val lane = Executors.newSingleThreadExecutor()
    fun enqueue(work: () -> Unit) = lane.submit(work)

    fun pass(context: Context) {
        BridgeStore(context).use { store ->
            if (context.checkSelfPermission(Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED ||
                context.checkSelfPermission(Manifest.permission.WRITE_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
                store.status = "stopped: calendar permission not granted"; return
            }
            try {
                BridgeEngine(MuditaCalendar(context.contentResolver), StandardCalendar(context.contentResolver),
                    store, PreferenceSyncConfig(context)).pass(System.currentTimeMillis())
            } catch (error: RuntimeException) {
                store.status = "stopped: ${error.javaClass.simpleName}: ${error.message.orEmpty()}"
            }
        }
    }

    fun arm(context: Context, replaceWatch: Boolean = false) {
        val scheduler = context.getSystemService(JobScheduler::class.java)
        fun schedule(builder: JobInfo.Builder) {
            check(scheduler.schedule(builder.build()) == JobScheduler.RESULT_SUCCESS) { "Could not schedule calendar bridge" }
        }
        if (replaceWatch || scheduler.getPendingJob(WATCH_JOB) == null) {
            schedule(JobInfo.Builder(WATCH_JOB, ComponentName(context, CalendarChangedJob::class.java))
                .addTriggerContentUri(JobInfo.TriggerContentUri(CalendarContract.CONTENT_URI,
                    JobInfo.TriggerContentUri.FLAG_NOTIFY_FOR_DESCENDANTS))
                .setTriggerContentUpdateDelay(200).setTriggerContentMaxDelay(1000))
        }
        if (scheduler.getPendingJob(PERIODIC_JOB) == null)
            schedule(JobInfo.Builder(PERIODIC_JOB, ComponentName(context, PeriodicPassJob::class.java)).setPeriodic(15 * 60 * 1000L))
        if (scheduler.getPendingJob(HORIZON_JOB) == null)
            schedule(JobInfo.Builder(HORIZON_JOB, ComponentName(context, HorizonJob::class.java)).setPeriodic(24 * 60 * 60 * 1000L))
    }
}
