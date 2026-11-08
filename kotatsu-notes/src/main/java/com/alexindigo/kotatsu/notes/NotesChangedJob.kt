package com.alexindigo.kotatsu.notes

import android.app.job.JobParameters
import android.app.job.JobService
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Future

class NotesChangedJob : JobService() {
    private var running: Future<*>? = null
    override fun onStartJob(params: JobParameters): Boolean {
        val changed = params.triggeredContentUris?.map { uri ->
            if (uri.authority == MuditaNotes.URI.authority && uri.pathSegments.size == 2 && uri.pathSegments[0] == "notes")
                uri.lastPathSegment?.toLongOrNull() else null
        }?.takeIf { it.isNotEmpty() && it.all { id -> id != null } }?.filterNotNull()?.toSet()
        running = NotesRuntime.enqueue {
            try { NotesRuntime.changed(this, changed) }
            finally {
                // Content jobs are one-shot. Register the successor before finishing this one,
                // as in the platform PhotosContentJob example, preserving accumulated changes.
                NotesRuntime.arm(this, replace = true)
                Handler(Looper.getMainLooper()).post { running = null; jobFinished(params, false) }
            }
        }
        return true
    }
    override fun onStopJob(params: JobParameters): Boolean {
        running?.cancel(true); running = null
        return NotesRuntime.ids(this).isNotEmpty() || QuoteRuntime.ids(this).isNotEmpty()
    }
}
