package com.alexindigo.kotatsu.notes

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.UserManager
import android.util.Log
import java.util.concurrent.Executors

/** One serialized I/O lane for configuration, broadcasts and jobs; views only carry commands. */
object NotesRuntime {
    private val lane = Executors.newSingleThreadExecutor()
    internal const val WATCH_JOB = 0x4b4e01
    internal var notes: (Context) -> NotesPort = { MuditaNotes(it.contentResolver) }
    internal var unlocked: (Context) -> Boolean = { it.getSystemService(UserManager::class.java).isUserUnlocked }

    fun enqueue(work: () -> Unit) = lane.submit {
        try { work() } catch (error: RuntimeException) { Log.e("KotatsuNotes", "Widget update failed", error) }
    }

    fun ids(context: Context): IntArray = AppWidgetManager.getInstance(context)
        .getAppWidgetIds(ComponentName(context, NotesWidgetProvider::class.java))

    fun refresh(context: Context, widgetIds: IntArray = ids(context)) {
        val store = NotesStore(context)
        val manager = AppWidgetManager.getInstance(context)
        val live = ids(context).toSet()
        for (id in widgetIds) {
            if (id !in live) continue
            if (!unlocked(context)) {
                manager.updateAppWidget(id, NotesWidgetViews.message(context, R.string.unlock_once))
                continue
            }
            val snapshot = try { store.selected(id)?.let(notes(context)::read) } catch (_: RuntimeException) { null }
            publish(context, id, snapshot)
        }
        arm(context)
    }

    private fun publish(context: Context, id: Int, snapshot: TaskListSnapshot?) {
        if (id !in ids(context)) return
        val store = NotesStore(context)
        val manager = AppWidgetManager.getInstance(context)
        val views = if (snapshot == null || snapshot.id != store.selected(id) || snapshot.items == null || Build.VERSION.SDK_INT < 31) {
            store.forget(id)
            NotesWidgetViews.message(context, R.string.choose_list)
        } else NotesWidgetViews.list(context, id, store.remember(id, snapshot),
            manager.getAppWidgetOptions(id).getBoolean(NotesWidgetViews.HOST_TITLE, false))
        manager.updateAppWidget(id, views)
    }

    fun tick(context: Context, widgetId: Int, tick: WidgetTick) {
        if (widgetId !in ids(context)) return
        if (!unlocked(context)) { refresh(context, intArrayOf(widgetId)); return }
        val store = NotesStore(context)
        val result = tickRenderedList(notes(context), store.selected(widgetId), store.rendered(widgetId), tick, System.currentTimeMillis())
        // Reconcile all widgets of that list from read-back, not the optimistic CheckBox state.
        val selected = store.selected(widgetId)
        for (id in ids(context)) if (id == widgetId || selected != null && store.selected(id) == selected) {
            publish(context, id, result.persisted)
        }
        arm(context)
    }

    fun changed(context: Context, changedIds: Set<Long>?) {
        val store = NotesStore(context)
        refresh(context, affectedWidgets(ids(context).associateWith(store::selected), changedIds).toIntArray())
        QuoteRuntime.changed(context, changedIds)
    }

    fun arm(context: Context, replace: Boolean = false) {
        val scheduler = context.getSystemService(JobScheduler::class.java)
        if (ids(context).isEmpty() && QuoteRuntime.ids(context).isEmpty()) { scheduler.cancel(WATCH_JOB); return }
        if (!unlocked(context) || !replace && scheduler.getPendingJob(WATCH_JOB) != null) return
        val result = scheduler.schedule(JobInfo.Builder(WATCH_JOB, ComponentName(context, NotesChangedJob::class.java))
            .addTriggerContentUri(JobInfo.TriggerContentUri(MuditaNotes.URI, JobInfo.TriggerContentUri.FLAG_NOTIFY_FOR_DESCENDANTS))
            .setTriggerContentUpdateDelay(200).setTriggerContentMaxDelay(1000).build())
        if (result != JobScheduler.RESULT_SUCCESS) Log.e("KotatsuNotes", "Could not watch Notes changes")
    }
}
