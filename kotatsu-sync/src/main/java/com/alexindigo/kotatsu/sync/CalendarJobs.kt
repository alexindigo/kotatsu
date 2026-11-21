package com.alexindigo.kotatsu.sync

import android.app.job.JobParameters
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean

abstract class CalendarPassJob : JobService() {
    private data class Run(val stopped: AtomicBoolean = AtomicBoolean(), var future: Future<*>? = null)
    private var active: Run? = null
    override fun onStartJob(params: JobParameters): Boolean {
        val run = Run(); active = run
        run.future = SyncRuntime.enqueue {
            try { if (!run.stopped.get()) SyncRuntime.pass(applicationContext) }
            finally {
                // Content jobs are one-shot: register their successor before finishing.
                if (params.jobId == SyncRuntime.WATCH_JOB) SyncRuntime.arm(applicationContext, replaceWatch = true)
                Handler(Looper.getMainLooper()).post {
                    if (active === run) active = null
                    if (!run.stopped.get()) jobFinished(params, false)
                }
            }
        }
        return true
    }
    override fun onStopJob(params: JobParameters): Boolean {
        active?.also { it.stopped.set(true); it.future?.cancel(true) }; active = null
        return true
    }
}

class CalendarChangedJob : CalendarPassJob()
class PeriodicPassJob : CalendarPassJob()
class HorizonJob : CalendarPassJob()

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val pending = goAsync()
        SyncRuntime.enqueue {
            try { SyncRuntime.arm(context); SyncRuntime.pass(context) }
            finally { pending.finish() }
        }
    }
}
